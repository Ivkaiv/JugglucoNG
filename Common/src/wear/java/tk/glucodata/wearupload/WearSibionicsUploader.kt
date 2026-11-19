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
import tk.glucodata.Applic
import tk.glucodata.ExchangeTrend
import tk.glucodata.Natives
import tk.glucodata.WearSibionicsUploadAccess
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal object WearSibionicsUploader : WearSibionicsUploadAccess.Output {
    private const val LIMIT = 4320
    private const val MAX_BATCH_SIZE = 100
    private const val MAX_BATCH_TIME_MS = 45_000L
    private const val RETRY_SECONDS = 30L
    private const val WORK_REVISION = 2
    private const val MAX_LIVE_AGE_MS = 90_000L
    private const val FUTURE_TOLERANCE_MS = 2 * 60_000L
    private const val NOW_JOB = "wear-sibionics-upload"
    private const val RECOVERY_JOB = "wear-sibionics-recovery"
    private val lock = Any()
    private val sender = Any()
    private val serialIo = Executors.newSingleThreadExecutor()
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private var configLoaded = false
    private var config: WearUploadConfig? = null
    private var configError = false
    private var store: Queue? = null

    data class Status(val address: String?, val pending: Long, val code: Int, val sentMs: Long) {
        val active: Boolean get() = address != null
    }

    class PendingOnPreviousServer : IllegalStateException()

    private data class Point(
        val id: String, val device: String, val timeMs: Long, val mgdl: Int, val direction: String?,
    )

    private class Queue(context: Context) : SQLiteOpenHelper(
        context, File(context.noBackupFilesDir, "wear-sibionics-queue.db").absolutePath, null, 2,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE queue (id TEXT PRIMARY KEY, session TEXT NOT NULL, " +
                    "device TEXT NOT NULL, sample_ms INTEGER NOT NULL, mgdl INTEGER NOT NULL, direction TEXT)",
            )
            db.execSQL("CREATE INDEX queue_session_time ON queue (session, sample_ms)")
            db.execSQL("CREATE TABLE capture (sensor_session TEXT PRIMARY KEY, last_index INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE delivery (id INTEGER PRIMARY KEY, code INTEGER NOT NULL, sent_ms INTEGER NOT NULL)")
            db.execSQL("INSERT INTO delivery VALUES (1, 0, 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
            check(from == 1 && to == 2) { "Unknown queue schema" }
            db.execSQL("ALTER TABLE queue ADD COLUMN direction TEXT")
        }
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

    private fun immediateUpload() = OneTimeWorkRequestBuilder<WearNightscoutWorker>()
        .setConstraints(network)
        .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun recoveryUpload() = PeriodicWorkRequestBuilder<WearNightscoutWorker>(15, TimeUnit.MINUTES)
        .setConstraints(network)
        .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_SECONDS, TimeUnit.SECONDS)
        .build()

    // Migrate scheduled work as well as the local queue. The old WorkSpecs keep
    // the accumulated exponential backoff even after an APK update.
    private fun migrateSchedule(context: Context, db: SQLiteDatabase, cfg: WearUploadConfig) {
        val prefs = context.getSharedPreferences("wear-upload-schedule", Context.MODE_PRIVATE)
        if (prefs.getInt("revision", 0) >= WORK_REVISION) return
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            RECOVERY_JOB, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, recoveryUpload(),
        ).result.get(5, TimeUnit.SECONDS)
        if (count(db, cfg.session) > 0) {
            workManager.enqueueUniqueWork(NOW_JOB, ExistingWorkPolicy.REPLACE, immediateUpload())
                .result.get(5, TimeUnit.SECONDS)
        }
        prefs.edit().putInt("revision", WORK_REVISION).apply()
    }

    fun readStatus(context: Context): Status = synchronized(lock) {
        val cfg = settings(context) ?: return@synchronized Status(null, 0, if (configError) -3 else 0, 0)
        val db = database(context)
        runCatching { migrateSchedule(context, db, cfg) }
        db.rawQuery("SELECT code, sent_ms FROM delivery WHERE id=1", null).use {
            check(it.moveToFirst())
            Status(cfg.url, count(db, cfg.session), it.getInt(0), it.getLong(1))
        }
    }

    fun saveSettings(context: Context, url: String, token: String): Unit = synchronized(lock) {
        val address = WearUploadConfig.address(url)
        val current = settings(context)
        val db = database(context)
        if (current != null && current.url != address && count(db, current.session) > 0) {
            throw PendingOnPreviousServer()
        }
        val saved = WearUploadConfig.save(context, address, token, current)
        if (saved === current) return@synchronized
        config = saved
        configError = false
        configLoaded = true
        if (saved.session != current?.session) {
            db.execSQL("UPDATE delivery SET code=0, sent_ms=0 WHERE id=1")
        }
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            RECOVERY_JOB, ExistingPeriodicWorkPolicy.KEEP, recoveryUpload(),
        )
        if (count(db, saved.session) > 0) {
            workManager.enqueueUniqueWork(NOW_JOB, ExistingWorkPolicy.REPLACE, immediateUpload())
        }
    }

    fun sendNow(context: Context): Boolean = synchronized(lock) {
        val current = settings(context) ?: return@synchronized false
        if (count(database(context), current.session) == 0L) return@synchronized false
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_JOB, ExistingWorkPolicy.REPLACE, immediateUpload())
            .result.get(5, TimeUnit.SECONDS)
        true
    }

    fun stop(context: Context) {
        synchronized(lock) {
            WearUploadConfig.stop(context)
            config = null
            configLoaded = true
            configError = false
            database(context).delete("queue", null, null)
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(NOW_JOB)
            work.cancelUniqueWork(RECOVERY_JOB)
        }
        // Do not report completion until an already-started request has exited.
        synchronized(sender) { }
    }

    override fun offer(sensor: String, sensorStartMs: Long, index: Int, timeMs: Long, mgdl: Float) {
        val context = Applic.app ?: return
        if (!mgdl.isFinite() || mgdl <= 0f || mgdl > 1000f ||
            timeMs <= 0 || sensorStartMs <= 0 || index < 0) return
        serialIo.execute {
            // An export failure must never interrupt the CGM driver's data or alarms.
            runCatching { queue(context, sensor, sensorStartMs, index, timeMs, mgdl.roundToInt()) }
                .onFailure { runCatching { synchronized(lock) { record(database(context), -3) } } }
        }
    }

    private fun directionForReading(sensor: String, sensorStartMs: Long, timeMs: Long, mgdl: Int): String? {
        val oldest = maxOf(sensorStartMs, timeMs - ExchangeTrend.MAX_DERIVE_GAP_MS)
        val newest = timeMs - ExchangeTrend.MIN_DERIVE_GAP_MS
        if (oldest > newest) return null
        val history = Natives.getGlucoseHistoryForSensor(sensor, (oldest / 1000L).coerceAtLeast(1L))
            ?: return null
        var previousTime = 0L
        var previousMgdl = 0
        for (i in 0 until history.size - 2 step 3) {
            val timestamp = history[i] * 1000L
            val value = (history[i + 1] / 10.0).roundToInt()
            if (timestamp in oldest..newest && timestamp > previousTime && value > 0) {
                previousTime = timestamp
                previousMgdl = value
            }
        }
        return ExchangeTrend.fromSamples(previousMgdl, previousTime, mgdl, timeMs)
            .name.takeIf { it.isNotEmpty() }
    }

    private fun queue(context: Context, sensor: String, sensorStartMs: Long, index: Int, timeMs: Long, mgdl: Int) {
        synchronized(lock) {
            val cfg = settings(context) ?: return
            val now = System.currentTimeMillis()
            if (mgdl <= 0 || timeMs < cfg.sinceMs || timeMs < now - MAX_LIVE_AGE_MS ||
                timeMs > now + FUTURE_TOLERANCE_MS) return
            val db = database(context)
            runCatching { migrateSchedule(context, db, cfg) }
            if (count(db, cfg.session) >= LIMIT) {
                record(db, -2)
            } else {
                val device = WearNightscoutProtocol.device(sensor)
                val sensorSession = "$device/$sensorStartMs"
                val last = db.rawQuery("SELECT last_index FROM capture WHERE sensor_session=?",
                    arrayOf(sensorSession)).use { if (it.moveToFirst()) it.getInt(0) else -1 }
                if (index > last) {
                    val direction = runCatching { directionForReading(sensor, sensorStartMs, timeMs, mgdl) }.getOrNull()
                    db.beginTransaction()
                    try {
                        db.insertOrThrow("queue", null, ContentValues().apply {
                            put("id", WearNightscoutProtocol.readingId(device, timeMs))
                            put("session", cfg.session)
                            put("device", device)
                            put("sample_ms", timeMs)
                            put("mgdl", mgdl)
                            put("direction", direction)
                        })
                        db.insertWithOnConflict("capture", null, ContentValues().apply {
                            put("sensor_session", sensorSession)
                            put("last_index", index)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_JOB, ExistingWorkPolicy.KEEP,
                immediateUpload(),
            )
        }
    }

    private fun record(db: SQLiteDatabase, code: Int, sampleMs: Long = 0) {
        db.update("delivery", ContentValues().apply {
            put("code", code)
            if (sampleMs > 0) put("sent_ms", sampleMs)
        }, "id=1", null)
    }

    fun drain(context: Context, stopped: () -> Boolean): Boolean = synchronized(sender) send@{
        val cfg = synchronized(lock) { settings(context) } ?: return@send true
        val http = WearNightscoutProtocol(cfg.url, cfg.subjectToken) {
            !stopped() && synchronized(lock) { settings(context) === cfg }
        }
        val started = SystemClock.elapsedRealtime()
        var sent = 0
        while (sent < MAX_BATCH_SIZE && SystemClock.elapsedRealtime() - started < MAX_BATCH_TIME_MS) {
            if (stopped()) return@send false
            val point = synchronized(lock) {
                if (settings(context) !== cfg) return@send true
                val db = database(context)
                db.rawQuery("SELECT id,device,sample_ms,mgdl,direction FROM queue WHERE session=? ORDER BY sample_ms LIMIT 1",
                    arrayOf(cfg.session)).use {
                    if (it.moveToFirst()) Point(it.getString(0), it.getString(1), it.getLong(2), it.getInt(3), it.getString(4)) else null
                }
            } ?: return@send true
            val code = try {
                http.send(point.device, point.timeMs, point.mgdl, point.direction)
            } catch (_: Exception) {
                -1
            }
            synchronized(lock) {
                if (settings(context) !== cfg) return@send true
                val db = database(context)
                if (code == 200 || code == 201) {
                    db.delete("queue", "id=? AND session=?", arrayOf(point.id, cfg.session))
                    record(db, code, point.timeMs)
                } else {
                    record(db, code)
                    return@send false
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
