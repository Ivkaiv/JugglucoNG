package tk.glucodata

/** Optional consumer of fresh readings received by the local Wear BLE driver. */
object WearNightscoutUploadAccess {
    fun interface Output {
        fun offer(sensor: String, sampleMs: Long)
    }

    @Volatile private var output: Output? = null

    @JvmStatic
    fun register(consumer: Output) {
        output = consumer
    }

    fun offer(sensor: String?, sampleMs: Long) {
        if (!sensor.isNullOrBlank()) output?.offer(sensor, sampleMs)
    }
}
