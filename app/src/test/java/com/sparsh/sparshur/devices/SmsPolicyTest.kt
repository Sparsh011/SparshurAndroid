package com.sparsh.sparshur.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPolicyTest {
    private val org = SmsSample("VM-ACME", "Your Acme sign-in code is 123456")
    private val personal = SmsSample("+14155550199", "Personal bank code 987654")

    @Test fun newAndOffPoliciesBlockEverything() {
        assertFalse(SmsPolicy().allows(org))
        assertFalse(SmsPolicy(senders = listOf("VM-ACME")).allows(org))
        assertFalse(SmsPolicy(SmsMode.ALLOWLIST).allows(org))
        assertFalse(SmsPolicy(SmsMode.ALLOWLIST, listOf(" "), listOf("\n")).allows(personal))
        assertFalse(SmsPolicy(SmsMode.ALLOWLIST, listOf("()")).allows(org.copy(sender = "")))
    }

    @Test fun senderRulesAreExactAndCaseInsensitive() {
        val policy = SmsPolicy(SmsMode.ALLOWLIST, listOf(" vm-acme "))
        assertTrue(policy.allows(org))
        assertFalse(policy.allows(org.copy(sender = "VM-ACME-OTHER")))
        assertFalse(policy.allows(org.copy(sender = "ACME")))
        assertFalse(policy.allows(personal))
    }

    @Test fun phoneFormattingDoesNotPermitSuffixOrCountryCodeMatches() {
        val policy = SmsPolicy(SmsMode.ALLOWLIST, listOf("+1 (415) 555-0199"))
        assertTrue(policy.allows(personal))
        assertFalse(policy.allows(personal.copy(sender = "4155550199")))
        assertFalse(policy.allows(personal.copy(sender = "+4414155550199")))
    }

    @Test fun explicitKeywordOrSenderCanAllowButUnrelatedMessagesAreBlocked() {
        val policy = SmsPolicy(SmsMode.ALLOWLIST, listOf("HR-ACME"), listOf("Acme sign-in"))
        assertTrue(policy.allows(org.copy(body = org.body.uppercase())))
        assertTrue(policy.allows(SmsSample("hr-acme", "Meeting moved")))
        assertFalse(policy.allows(personal))
        assertTrue(SmsPolicy(SmsMode.ALL).allows(personal))
    }

    @Test fun routingChecksEachRecipientAndBlocksUnknownPolicies() {
        val devices = listOf(
            ConnectedDevice("personal", "Personal", "macOS"),
            ConnectedDevice("work", "Work", "macOS"),
            ConnectedDevice("other", "Other", "macOS")
        )
        val policies = mapOf(
            "personal" to SmsPolicy(SmsMode.ALL),
            "work" to SmsPolicy(SmsMode.ALLOWLIST, listOf("VM-ACME"))
        )
        assertEquals(listOf("personal"), SmsRouting.recipients(devices, policies, personal).map { it.id })
        assertEquals(listOf("personal", "work"), SmsRouting.recipients(devices, policies, org).map { it.id })
        assertTrue(SmsRouting.recipients(devices, emptyMap(), org).isEmpty())
    }

    @Test fun badPersistedSettingsFailClosed() {
        listOf(null, "", "{}", "not json", """{"mode":"ALL","senders":[],"keywords":[""]}""",
            """{"mode":"UNKNOWN","senders":[],"keywords":[]}""",
            """{"mode":"ALL","senders":[12],"keywords":[]}""").forEach {
            assertFalse(SmsPolicyStore.decode(it).allows(org))
        }
        assertTrue(SmsPolicyStore.decode("""{"mode":"ALLOWLIST","senders":["VM-ACME"],"keywords":[]}""").allows(org))
    }

    @Test fun parseRulesDropsEmptyEntriesAndRejectsOversizeRules() {
        assertEquals(listOf("VM-ACME", "HR-ACME"), SmsPolicy.parseRules(" ,VM-ACME\nHR-ACME,,VM-ACME\n"))
        assertTrue(runCatching { SmsPolicy.parseRules("x".repeat(121)) }.isFailure)
        assertTrue(runCatching { SmsPolicy.parseRules((1..41).joinToString(",")) }.isFailure)
    }
}
