package com.sparsh.sparshur.devices

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sparsh.sparshur.widget.DevicesWidgetProvider
import java.util.concurrent.Executors

class DevicesViewModel(application: Application, private val source: ConnectedDeviceSource) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AvailableDevices(application))
    var devices by mutableStateOf(emptyList<ConnectedDevice>())
        private set
    private val store = SmsPolicyStore(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    var primaryId by mutableStateOf<String?>(null)
        private set
    var primaryBusy by mutableStateOf(true)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var savingPolicy by mutableStateOf(false)
        private set
    var policies by mutableStateOf(devices.associate { it.id to store.load(it.id) })
        private set
    var selected by mutableStateOf<ConnectedDevice?>(null)
        private set
    // Deliberately not SavedStateHandle/rememberSaveable: user content stays in memory.
    var mode by mutableStateOf(SmsMode.OFF)
        private set
    var senders by mutableStateOf("")
        private set
    var keywords by mutableStateOf("")
        private set
    var sampleSender by mutableStateOf("")
        private set
    var sampleBody by mutableStateOf("")
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var filterResult by mutableStateOf<String?>(null)
        private set

    init { refresh() }

    fun refresh() {
        executor.execute {
            try {
                val current = source.devices()
                val savedId = DeviceDatabase.get(getApplication()).primaryDevice().read()?.deviceId
                val savedPolicies = current.associate { it.id to store.load(it.id) }
                main.post {
                    if (!closed) {
                        devices = current
                        primaryId = savedId?.takeIf { id -> current.any { it.id == id } }
                        primaryBusy = false
                        policies = savedPolicies
                        loading = false
                        loadError = null
                    }
                }
            } catch (_: Exception) {
                main.post { if (!closed) { primaryBusy = false; loading = false; loadError = "Device settings couldn’t be loaded. Try again." } }
            }
        }
    }

    fun makePrimary(deviceId: String?) {
        if (primaryBusy || (deviceId != null && devices.none { it.id == deviceId })) return
        primaryBusy = true
        executor.execute {
            try {
                val dao = DeviceDatabase.get(getApplication()).primaryDevice()
                if (deviceId == null) dao.clear() else dao.save(PrimaryDevice(deviceId = deviceId))
                main.post { if (!closed) { primaryId = deviceId; primaryBusy = false; notice = null } }
                DevicesWidgetProvider.updateAll(getApplication())
            } catch (_: Exception) {
                main.post { if (!closed) { primaryBusy = false; notice = "Could not save Primary. Please retry." } }
            }
        }
    }

    fun open(deviceId: String?) {
        selected = devices.firstOrNull { it.id == deviceId }
        sampleSender = ""
        sampleBody = ""
        notice = if (deviceId != null && selected == null) "That device is unavailable. Choose a device below." else null
        filterResult = null
        val policy = selected?.let { policies[it.id] } ?: SmsPolicy()
        mode = policy.mode
        senders = policy.senders.joinToString("\n")
        keywords = policy.keywords.joinToString("\n")
    }

    fun mode(value: SmsMode) { mode = value; filterResult = null; notice = null }
    fun senders(value: String) { if (value.length <= 5000) { senders = value; filterResult = null; notice = null } }
    fun keywords(value: String) { if (value.length <= 5000) { keywords = value; filterResult = null; notice = null } }
    fun sampleSender(value: String) { if (value.length <= 120) { sampleSender = value; filterResult = null } }
    fun sampleBody(value: String) { if (value.length <= 4096) { sampleBody = value; filterResult = null } }
    fun notice(value: String) { notice = value }
    fun clearNotice() { notice = null }

    private fun draftPolicy(): SmsPolicy {
        // Irrelevant malformed edits must never prevent switching SMS off.
        if (mode != SmsMode.ALLOWLIST) return (selected?.let { policies[it.id] } ?: SmsPolicy()).copy(mode = mode)
        return SmsPolicy(mode, SmsPolicy.parseRules(senders), SmsPolicy.parseRules(keywords))
    }
    fun hasUnsavedPolicy(): Boolean = selected?.let { device ->
        runCatching { draftPolicy() != (policies[device.id] ?: SmsPolicy()) }.getOrDefault(true)
    } ?: false

    fun savePolicy() {
        val device = selected ?: return
        if (savingPolicy) return
        try {
            val policy = draftPolicy()
            savingPolicy = true
            executor.execute {
                val saved = runCatching { store.save(device.id, policy) }.getOrDefault(false)
                main.post {
                    if (!closed) {
                        savingPolicy = false
                        if (saved) {
                            policies = policies + (device.id to policy)
                            notice = "Rules saved for ${device.name}."
                        } else notice = "Rules couldn’t be saved. Try again."
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            notice = e.message
        }
    }

    fun testPolicy() {
        try {
            val device = selected ?: return
            val draftPolicies = policies + (device.id to draftPolicy())
            val allowed = SmsRouting.recipients(listOf(device), draftPolicies, SmsSample(sampleSender, sampleBody)).isNotEmpty()
            filterResult = if (allowed) "Allowed for ${device.name} by these draft rules. Nothing sent."
                else "Blocked for ${device.name} by these draft rules. Nothing sent."
        } catch (e: IllegalArgumentException) {
            filterResult = e.message
        }
    }

    override fun onCleared() {
        closed = true
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
