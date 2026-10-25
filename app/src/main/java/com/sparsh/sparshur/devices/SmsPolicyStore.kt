package com.sparsh.sparshur.devices

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores rules only. Clipboard text and SMS samples are never persisted. */
class SmsPolicyStore(context: Context) {
    private val preferences = context.getSharedPreferences("device_sms_policies_v1", Context.MODE_PRIVATE)

    fun load(deviceId: String): SmsPolicy = try {
        decode(preferences.getString(deviceId, null))
    } catch (_: ClassCastException) {
        SmsPolicy()
    }

    fun save(deviceId: String, policy: SmsPolicy): Boolean {
        val json = JSONObject().put("mode", policy.mode.name)
            .put("senders", JSONArray(policy.senders)).put("keywords", JSONArray(policy.keywords))
        return preferences.edit().putString(deviceId, json.toString()).commit()
    }

    companion object {
        /** Invalid or missing settings fail closed instead of silently allowing SMS. */
        internal fun decode(value: String?): SmsPolicy = try {
            if (value == null) SmsPolicy() else {
                val json = JSONObject(value)
                fun rules(key: String): List<String> {
                    val array = json.getJSONArray(key)
                    require(array.length() <= 40)
                    return (0 until array.length()).map {
                        (array.get(it) as String).also { rule -> require(rule.length in 1..120 && rule.isNotBlank()) }
                    }
                }
                SmsPolicy(SmsMode.valueOf(json.getString("mode")), rules("senders"), rules("keywords"))
            }
        } catch (_: Exception) {
            SmsPolicy()
        }
    }
}
