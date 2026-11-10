package com.yunga.ultra3connect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local-only storage of the last 100 decoded watch records.
 *  No network transfer or external storage is used by this class.
 */
data class StoredMeasurement(
    val kind: ExperimentalVitalDecoder.Kind,
    val valueLabel: String,
    val receivedAtMillis: Long,
    val packetHex: String
)

class LocalMeasurementStore(context: Context) {
    private val prefs = context.getSharedPreferences("ultra3_measurements_v1", Context.MODE_PRIVATE)
    private val key = "history"

    fun load(): List<StoredMeasurement> = try {
        val data = JSONArray(prefs.getString(key, "[]") ?: "[]")
        val values = mutableListOf<StoredMeasurement>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val kind = when (o.optString("type")) {
                "heart" -> ExperimentalVitalDecoder.Kind.HEART_RATE
                "pressure" -> ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE
                else -> continue
            }
            val timestamp = o.optLong("time", 0L)
            val value = o.optString("value")
            if (timestamp <= 0L || value.isBlank()) continue
            values.add(
                StoredMeasurement(
                    kind, value, timestamp,
                    o.optString("raw")
                )
            )
        }
        values.take(100)
    } catch (_: Exception) {
        emptyList()
    }

    fun save(values: List<StoredMeasurement>) {
        val array = JSONArray()
        for (record in values.take(100)) {
            val obj = JSONObject()
            obj.put("type", if (record.kind == ExperimentalVitalDecoder.Kind.HEART_RATE)
                "heart" else "pressure")
            obj.put("time", record.receivedAtMillis)
            obj.put("value", record.valueLabel)
            obj.put("raw", record.packetHex)
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}
