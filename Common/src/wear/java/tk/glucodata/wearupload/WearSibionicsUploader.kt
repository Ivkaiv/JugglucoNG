package tk.glucodata.wearupload

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.WearNightscoutUploadAccess

internal object WearSibionicsUploader : WearNightscoutUploadAccess.Output {
    private const val MAX_QUEUE = 4320
    private const val MAX_BATCH_SIZE = 100
    private const val MAX_BATCH_TIME_MS = 45_000L
    private const val MAX_LIVE_AGE_MS = 90_000L
    private const val MAX_FUTURE_MS = 120_000L
    private const val NOW_JOB = "wear-nightscout-upload"
    private const val RECOVERY_JOB = "wear-nightscout-recovery"
    private val lock = Any()
    private val sender = Any()
    private val capture = Executors.newSingleThreadExecutor()
    @Volatile private var captureGeneration = 0L
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private var configLoaded = false
    private var config: WearUploadConfig? = null
    private var configError = false
    private var recoveryScheduled = false
    private var store: Queue? = null

    data class Status(val address: String?, val pending: Long, val code: Int, val sentMs: Long) {
        val active: Boolean get() = address != null
    }
    class PendingOnPreviousServer : IllegalStateException()
    class NativeUploaderEnabled : IllegalStateException()
    private data class Point(val identifier: String, val sampleMs: Long, val payload: String)

    private class Queue(context: Context) : SQLiteOpenHelper(
        context, File(context.noBackupFilesDir, "wear-nightscout-queue.db").absolutePath, null, 1,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE queue (session TEXT NOT NULL, identifier TEXT NOT NULL, " +
                    "sample_ms INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (session, identifier))",
            )
            db.execSQL("CREATE INDEX queue_session_time ON queue (session, sample_ms)")
            db.execSQL(
                "CREATE TABLE delivery (session TEXT PRIMARY KEY, code INTEGER NOT NULL, sent_ms INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) = error("Unknown Nightscout queue schema")
    }

    private fun settings(context: Context): WearUploadConfig? {
        if (!configLoaded) {
            config = runCatching { WearUploadConfig.read(context) }
                .onFailure { configError = true }.getOrNull()
            configLoaded = true
        }
        return config
    }

    private fun database(context: Context): SQLiteDatabase =
        (store ?: Queue(context.applicationContext).also { store = it }).writableDatabase

    private fun count(db: SQLiteDatabase, session: String): Long = db.rawQuery(
        "SELECT count(*) FROM queue WHERE session=?", arrayOf(session),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private fun schedule(context: Context, policy: ExistingWorkPolicy) =
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_JOB, policy,
            OneTimeWorkRequestBuilder<WearNightscoutWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
                .build(),
        )

    private fun ensureRecovery(context: Context) {
        if (recoveryScheduled) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RECOVERY_JOB, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WearNightscoutWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
                .build(),
        ).result.get(5, TimeUnit.SECONDS)
        recoveryScheduled = true
    }

    fun readStatus(context: Context): Status = synchronized(lock) {
        val cfg = settings(context) ?: return@synchronized Status(null, 0, if (configError) -3 else 0, 0)
        val db = database(context)
        val pending = count(db, cfg.session)
        val nativeEnabled = Natives.getuseuploader()
        db.rawQuery("SELECT code, sent_ms FROM delivery WHERE session=?", arrayOf(cfg.session)).use {
            if (it.moveToFirst()) Status(cfg.url, pending, if (nativeEnabled) -4 else it.getInt(0), it.getLong(1))
            else Status(cfg.url, pending, if (nativeEnabled) -4 else 0, 0)
        }
    }

    fun saveSettings(context: Context, url: String, token: String): Unit = synchronized(lock) {
        if (Natives.getuseuploader()) throw NativeUploaderEnabled()
        val address = WearUploadConfig.address(url)
        val current = settings(context)
        val db = database(context)
        if (current != null && current.url != address && count(db, current.session) > 0) {
            throw PendingOnPreviousServer()
        }
        val saved = WearUploadConfig.save(context, address, token, current)
        if (saved.session != current?.session) captureGeneration++
        config = saved
        configError = false
        ensureRecovery(context)
        if (saved !== current && count(db, saved.session) > 0) {
            schedule(context, ExistingWorkPolicy.REPLACE)
        }
    }

    fun sendNow(context: Context): Boolean = synchronized(lock) {
        if (Natives.getuseuploader()) return@synchronized false
        val cfg = settings(context) ?: return@synchronized false
        if (count(database(context), cfg.session) == 0L) return@synchronized false
        schedule(context, ExistingWorkPolicy.REPLACE).result.get(5, TimeUnit.SECONDS)
        true
    }

    fun stop(context: Context) {
        synchronized(lock) {
            WearUploadConfig.stop(context)
            captureGeneration++
            config = null
            configLoaded = true
            configError = false
            recoveryScheduled = false
            database(context).delete("queue", null, null)
            database(context).delete("delivery", null, null)
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(NOW_JOB)
                cancelUniqueWork(RECOVERY_JOB)
            }
        }
        // The confirmation completes after any already-started request exits.
        synchronized(sender) { }
    }

    override fun offer(sensor: String, sampleMs: Long) {
        val app = Applic.app ?: return
        val generation = captureGeneration
        val acceptedAt = System.currentTimeMillis()
        capture.execute {
            synchronized(lock) {
                val cfg = settings(app) ?: return@synchronized
                if (generation != captureGeneration || acceptedAt < cfg.sinceMs) return@synchronized
                runCatching { queue(app, cfg, sensor, sampleMs) }
                    .onFailure { runCatching { record(database(app), cfg.session, -3) } }
            }
        }
    }

    private fun queue(context: Context, cfg: WearUploadConfig, sensor: String, sampleMs: Long) {
        val now = System.currentTimeMillis()
        if (Natives.getuseuploader() || sampleMs < cfg.sinceMs ||
            sampleMs < now - MAX_LIVE_AGE_MS || sampleMs > now + MAX_FUTURE_MS
        ) return
        // Register recovery before committing any reading. If immediate work is
        // lost between the commit and scheduling, the periodic worker can drain it.
        ensureRecovery(context)
        val db = database(context)
        if (count(db, cfg.session) >= MAX_QUEUE) {
            record(db, cfg.session, -2)
        } else {
            val payload = checkNotNull(Natives.wearNightscoutEntry(sensor, sampleMs / 1000L))
            val entry = JSONObject(payload)
            val identifier = entry.getString("identifier")
            val date = entry.getLong("date")
            check(entry.getString("type") == "sgv" && identifier.isNotBlank() && date == sampleMs / 1000L * 1000L)
            db.insertWithOnConflict("queue", null, ContentValues().apply {
                put("session", cfg.session)
                put("identifier", identifier)
                put("sample_ms", date)
                put("payload", payload)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        schedule(context, ExistingWorkPolicy.KEEP)
    }

    private fun record(db: SQLiteDatabase, session: String, code: Int, sampleMs: Long = 0) {
        val previous = db.rawQuery("SELECT sent_ms FROM delivery WHERE session=?", arrayOf(session)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        db.insertWithOnConflict("delivery", null, ContentValues().apply {
            put("session", session)
            put("code", code)
            put("sent_ms", maxOf(previous, sampleMs))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun drain(context: Context, stopped: () -> Boolean): Boolean = synchronized(sender) send@{
        val cfg = synchronized(lock) { settings(context) } ?: return@send true
        val http = WearNightscoutClient(URI(cfg.url), cfg.subjectToken) {
            !stopped() && !Natives.getuseuploader() && synchronized(lock) { settings(context) === cfg }
        }
        val started = SystemClock.elapsedRealtime()
        var sent = 0
        while (sent < MAX_BATCH_SIZE && SystemClock.elapsedRealtime() - started < MAX_BATCH_TIME_MS) {
            if (stopped()) return@send false
            val point = synchronized(lock) {
                if (settings(context) !== cfg || Natives.getuseuploader()) return@send true
                database(context).rawQuery(
                    "SELECT identifier, sample_ms, payload FROM queue WHERE session=? ORDER BY sample_ms LIMIT 1",
                    arrayOf(cfg.session),
                ).use { if (it.moveToFirst()) Point(it.getString(0), it.getLong(1), it.getString(2)) else null }
            } ?: return@send true
            val code = try {
                http.send(point.identifier, point.payload)
            } catch (_: Exception) {
                -1
            }
            synchronized(lock) {
                if (settings(context) !== cfg) return@send true
                val db = database(context)
                if (code != 200 && code != 201) {
                    record(db, cfg.session, code)
                    return@send false
                }
                db.beginTransaction()
                try {
                    db.delete("queue", "session=? AND identifier=?", arrayOf(cfg.session, point.identifier))
                    record(db, cfg.session, code, point.sampleMs)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            sent++
        }
        synchronized(lock) {
            settings(context) !== cfg || count(database(context), cfg.session) == 0L
        }
    }
}

class WearNightscoutWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (WearSibionicsUploader.drain(applicationContext) { isStopped }) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }
}
