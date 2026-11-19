package tk.glucodata.glucosecomplication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.text.NumberFormat
import tk.glucodata.R
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.ui.WearSensorSelection

class SensorBatteryDataSourceService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = buildData(type, 75)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val percent = runCatching {
            val id = WearSensorSelection.resolve() ?: return NoDataComplicationData()
            ManagedSensorRuntime.resolveUiSnapshot(id, id)?.batteryPercent?.takeIf { it in 0..100 }
        }.getOrNull() ?: return NoDataComplicationData()
        return buildData(request.complicationType, percent)
    }

    private fun buildData(type: ComplicationType, percent: Int): ComplicationData? {
        val value = NumberFormat.getPercentInstance().format(percent / 100.0)
        val valueText = text(value)
        val label = getString(R.string.wear_sensor_battery_complication)
        val description = text(getString(R.string.wear_sensor_battery_description, value))
        val image = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.wear_sensor_battery_icon),
        ).build()
        val tap = GlucoseComplicationData.tapAction()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(valueText, description)
                .setMonochromaticImage(image).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(valueText, description)
                .setTitle(text(label)).setMonochromaticImage(image).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = percent.toFloat(), min = 0f, max = 100f, contentDescription = description,
            ).setText(valueText).setMonochromaticImage(image).setTapAction(tap).build()
            else -> null
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
