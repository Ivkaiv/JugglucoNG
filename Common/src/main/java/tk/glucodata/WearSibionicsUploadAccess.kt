package tk.glucodata

/** Optional Wear-only consumer of new readings decoded by the local Sibionics driver. */
object WearSibionicsUploadAccess {
    fun interface Output {
        fun offer(sensor: String, sensorStartMs: Long, index: Int, timeMs: Long, mgdl: Float)
    }

    @Volatile private var output: Output? = null

    @JvmStatic
    fun register(consumer: Output) {
        output = consumer
    }

    fun offer(sensor: String?, sensorStartMs: Long, index: Int, timeMs: Long, mgdl: Float) {
        if (!sensor.isNullOrBlank()) output?.offer(sensor, sensorStartMs, index, timeMs, mgdl)
    }
}
