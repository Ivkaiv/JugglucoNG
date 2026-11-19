package tk.glucodata

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import tk.glucodata.alerts.AlertRuntimeManager
import tk.glucodata.drivers.sibionics.SibionicsBleManager
import tk.glucodata.drivers.sibionics.SibionicsConstants
import tk.glucodata.ui.WearGlucoseStore

internal object WearSibionicsCalibration {
    private const val MAX_POINTS = 32
    private const val MAX_AGE_MS = 10 * 60 * 1000L
    private const val MGDL_PER_MMOLL = 18.0182
    private val empty = WearCalibrationMode(DoubleArray(0))
    private val storageLock = Any()
    private val saved = ConcurrentHashMap<Session, Saved>()
    private val overlays = ConcurrentHashMap<Session, Overlay>()

    internal data class Session(val sensorId: String, val driver: SibionicsBleManager.LocalCalibrationSession)
    internal data class Reference(val session: Session, val sample: SibionicsBleManager.LocalCalibrationSample)
    private data class Saved(val payload: WearCalibrationPayload? = null, val readable: Boolean = true)
    private data class Overlay(
        val phone: WearCalibrationPayload?,
        val local: WearCalibrationPayload?,
        val effective: WearCalibrationPayload?,
    )

    @JvmStatic
    fun register() {
        if (BuildConfig.APPLICATION_ID == "tk.glucodata.ng.wearpilot") {
            SyncedWearCalibrationProvider.registerLocalOverlay(::effectivePayload)
        }
    }

    private fun driverFor(sensorId: String?): SibionicsBleManager? {
        val identity = SibionicsConstants.canonicalSensorId(sensorId)
        if (identity.isBlank()) return null
        return SensorBluetooth.mygatts().filterIsInstance<SibionicsBleManager>()
            .singleOrNull { SibionicsConstants.canonicalSensorId(it.SerialNumber) == identity }
    }

    private fun exactPhone(session: Session, candidate: WearCalibrationPayload?): WearCalibrationPayload? =
        candidate?.takeIf {
            SibionicsConstants.canonicalSensorId(it.sensorId) ==
                SibionicsConstants.canonicalSensorId(session.sensorId)
        }

    private fun sessionFor(driver: SibionicsBleManager): Session? {
        val serial = driver.SerialNumber ?: return null
        if (SensorOwnershipRuntime.hasStoodDown(serial)) return null
        return driver.localCalibrationSession()?.let { Session(serial, it) }
    }

    // Retain the local route during a brief BLE drop. A lost local session must
    // never turn the same button press into a write to the phone.
    fun selectedSession(): Session? =
        if (BuildConfig.APPLICATION_ID == "tk.glucodata.ng.wearpilot") {
            driverFor(WearGlucoseStore.currentSensor())?.let(::sessionFor)
        } else null

    fun reference(session: Session, requestedTimestamp: Long): Reference? {
        val driver = driverFor(session.sensorId) ?: return null
        if (selectedSession() != session || !SensorOwnershipRuntime.readsLocally(session.sensorId) ||
            !isAutoSelected()
        ) return null
        val sample = driver.localCalibrationSample() ?: return null
        val now = System.currentTimeMillis()
        if (sample.session != session.driver ||
            sample.timestampMs < session.driver.sensorStartMs ||
            sample.timestampMs !in (now - MAX_AGE_MS)..now ||
            (requestedTimestamp > 0L && requestedTimestamp / 1000L != sample.timestampMs / 1000L)
        ) return null
        return Reference(session, sample)
    }

    fun hasLocalPoint(session: Session, timestamp: Long): Boolean =
        read(session).payload?.auto?.let { timestamp in byTime(it) } == true

    fun localAnchors(session: Session): DoubleArray =
        read(session).payload?.auto?.anchorsMgdl?.copyOf() ?: DoubleArray(0)

    fun isAutoSelected(): Boolean = WearGlucoseStore.viewMode().let { it != 1 && it != 3 }

    fun isSuspended(session: Session): Boolean =
        localAnchors(session).isNotEmpty() &&
            !compatible(exactPhone(session, SyncedWearCalibrationProvider.phonePayload(session.sensorId)),
                session.driver.sensorStartMs)

    fun save(reference: Reference, userMgdl: Float): Boolean = change(
        session = reference.session,
        timestamp = reference.sample.timestampMs,
        userMgdl = userMgdl,
        reference = reference,
    )

    fun edit(session: Session, timestamp: Long, userMgdl: Float): Boolean =
        change(session, timestamp, userMgdl, reference = null)

    fun delete(session: Session, timestamp: Long): Boolean =
        change(session, timestamp, userMgdl = null, reference = null)

    private fun change(
        session: Session,
        timestamp: Long,
        userMgdl: Float?,
        reference: Reference?,
    ): Boolean {
        if (selectedSession() != session ||
            (userMgdl != null && (!userMgdl.isFinite() || userMgdl !in 39f..500f))
        ) return false
        if (reference != null && (
            !SensorOwnershipRuntime.readsLocally(session.sensorId) ||
                !isAutoSelected() ||
                timestamp !in (System.currentTimeMillis() - MAX_AGE_MS)..System.currentTimeMillis()
            )
        ) return false
        val phone = exactPhone(session, SyncedWearCalibrationProvider.phonePayload(session.sensorId))
        if (userMgdl != null && !compatible(phone, session.driver.sensorStartMs)) return false
        val changed = synchronized(storageLock) {
            val current = read(session)
            if (!current.readable) return@synchronized false
            val display = current.payload?.auto?.let(::byTime) ?: sortedMapOf()
            val stock = current.payload?.autoIntegration?.let(::byTime) ?: sortedMapOf()
            if (reference == null && (timestamp !in display || timestamp !in stock)) {
                return@synchronized false
            }
            if (userMgdl == null) {
                display.remove(timestamp)
                stock.remove(timestamp)
            } else {
                val shown = reference?.sample?.displayedMgdl?.toDouble() ?: display.getValue(timestamp)[0]
                val measured = reference?.sample?.stockMgdl?.toDouble() ?: stock.getValue(timestamp)[0]
                if (timestamp !in display && display.size >= MAX_POINTS) return@synchronized false
                display[timestamp] = doubleArrayOf(shown, userMgdl.toDouble(), timestamp.toDouble())
                stock[timestamp] = doubleArrayOf(measured, userMgdl.toDouble(), timestamp.toDouble())
            }
            if (selectedSession() != session) return@synchronized false
            write(session, WearCalibrationPayload(
                sensorId = session.sensorId,
                revision = maxOf(System.currentTimeMillis(), (current.payload?.revision ?: 0L) + 1L),
                valuesPrecalibrated = false,
                hideInitialWhenCalibrated = false,
                auto = packed(display.values),
                raw = empty,
                autoIntegratedByDriver = true,
                autoIntegration = packed(stock.values),
                sourceUnitMgdlPerUnit = current.payload?.sourceUnitMgdlPerUnit
                    ?: if (Applic.unit == 1) MGDL_PER_MMOLL else 1.0,
            ))
        }
        if (changed) {
            AlertRuntimeManager.onDisplayCalibrationChanged()
            SyncedWearCalibrationProvider.localCalibrationChanged()
        }
        return changed
    }

    private fun effectivePayload(sensorId: String, phone: WearCalibrationPayload?): WearCalibrationPayload? {
        val session = driverFor(sensorId)?.let(::sessionFor) ?: return phone
        val local = read(session).payload
        overlays[session]?.let {
            if (it.phone === phone && it.local === local) return it.effective
        }
        val start = session.driver.sensorStartMs
        val currentPhone = exactPhone(session, phone)?.let { currentSession(it, start) }
        val merged = if (local == null || local.auto.anchorsMgdl.isEmpty() || !compatible(currentPhone, start)) {
            currentPhone
        } else {
            (currentPhone ?: local).copy(
                revision = (currentPhone?.revision ?: 0L) xor local.revision,
                auto = merge(currentPhone?.auto ?: empty, local.auto, start),
                autoIntegration = merge(currentPhone?.autoIntegration ?: empty, local.autoIntegration, start),
                autoIntegratedByDriver = true,
            )
        }
        overlays[session] = Overlay(phone, local, merged)
        return merged
    }

    private fun currentSession(phone: WearCalibrationPayload, start: Long): WearCalibrationPayload {
        fun filter(mode: WearCalibrationMode): WearCalibrationMode {
            val rows = byTime(mode)
            return if (rows.keys.removeAll { it < start }) packed(rows.values) else mode
        }
        return phone.copy(
            auto = filter(phone.auto),
            autoIntegration = filter(phone.autoIntegration),
            raw = filter(phone.raw),
            rawIntegration = filter(phone.rawIntegration),
        )
    }

    private fun compatible(phone: WearCalibrationPayload?, start: Long): Boolean {
        if (phone == null) return true
        if (phone.valuesPrecalibrated || phone.overwriteSensorValues) return false
        val phoneTimes = byTime(phone.auto).keys.filter { it >= start }
        val stockTimes = byTime(phone.autoIntegration).keys.filter { it >= start }
        return phoneTimes == stockTimes
    }

    private fun merge(phone: WearCalibrationMode, local: WearCalibrationMode, start: Long): WearCalibrationMode {
        val rows = byTime(phone)
        rows.keys.removeAll { it < start }
        rows.putAll(byTime(local))
        return packed(rows.values.toList().takeLast(MAX_POINTS))
    }

    private fun byTime(mode: WearCalibrationMode) = sortedMapOf<Long, DoubleArray>().apply {
        val anchors = mode.anchorsMgdl
        for (i in 0 until anchors.size - 2 step 3) {
            put(anchors[i + 2].toLong(), anchors.copyOfRange(i, i + 3))
        }
    }

    private fun packed(rows: Collection<DoubleArray>) =
        WearCalibrationMode(rows.flatMap { it.asIterable() }.toDoubleArray())

    private fun read(session: Session): Saved {
        saved[session]?.let { return it }
        return synchronized(storageLock) {
            saved[session] ?: load(session).also { if (it.readable) saved[session] = it }
        }
    }

    private fun load(session: Session): Saved {
        val file = fileFor(session) ?: return Saved(readable = false)
        return try {
            val payload = WearCalibrationPayload.decode(file.readFully())
                ?.takeIf { it.sensorId == session.sensorId }
            Saved(payload, readable = payload != null)
        } catch (_: FileNotFoundException) {
            Saved(readable = !file.baseFile.exists())
        } catch (_: java.io.IOException) {
            Saved(readable = false)
        }
    }

    private fun write(session: Session, payload: WearCalibrationPayload): Boolean {
        val file = fileFor(session) ?: return false
        val stream = try { file.startWrite() } catch (_: java.io.IOException) { return false }
        try {
            stream.write(WearCalibrationPayload.encode(payload))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (_: java.io.IOException) {
            file.failWrite(stream)
            return false
        }
        val committed = load(session)
        if (committed.payload?.revision != payload.revision) return false
        saved[session] = committed
        return true
    }

    private fun fileFor(session: Session): AtomicFile? {
        val directory = Applic.app?.filesDir ?: return null
        val identity = "${session.sensorId}|${session.driver}"
        val name = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return AtomicFile(File(directory, "wear-calibration-$name"))
    }
}
