package com.sparsh.sparshur.devices

import java.util.Locale

enum class SmsMode { OFF, ALL, ALLOWLIST }

data class SmsSample(val sender: String, val body: String)

/** Evaluated on the phone per recipient, before encryption, queueing or transmission. */
data class SmsPolicy(
    val mode: SmsMode = SmsMode.OFF,
    val senders: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
) {
    fun allows(sms: SmsSample): Boolean = when (mode) {
        SmsMode.OFF -> false
        SmsMode.ALL -> true
        SmsMode.ALLOWLIST -> {
            val sender = normalizeSender(sms.sender)
            (sender.isNotEmpty() && senders.any { it.isNotBlank() && normalizeSender(it) == sender }) ||
                keywords.any { it.isNotBlank() && sms.body.contains(it.trim(), ignoreCase = true) }
        }
    }

    companion object {
        /** Exact sender IDs; phone numbers may differ only in spacing and punctuation. */
        fun normalizeSender(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.matches(Regex("\\+?[0-9 ()\\-.]+"))) {
                trimmed.filter { it in '0'..'9' || it == '+' }
            } else {
                trimmed.uppercase(Locale.ROOT)
            }
        }

        fun parseRules(value: String): List<String> = value.split(',', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .also { rules ->
                require(rules.size <= 40 && rules.all { it.length <= 120 }) {
                    "Use at most 40 rules per field, each at most 120 characters."
                }
            }
    }
}

object SmsRouting {
    /** Missing policies are blocked. Never decide once and fan out an SMS to every device. */
    fun recipients(
        devices: List<ConnectedDevice>,
        policies: Map<String, SmsPolicy>,
        sms: SmsSample
    ): List<ConnectedDevice> = devices.filter { policies[it.id]?.allows(sms) == true }
}
