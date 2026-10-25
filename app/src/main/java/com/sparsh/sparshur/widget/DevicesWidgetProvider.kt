package com.sparsh.sparshur.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sparsh.sparshur.MainActivity
import com.sparsh.sparshur.R
import com.sparsh.sparshur.devices.AvailableDevices
import com.sparsh.sparshur.devices.ConnectedDevice
import com.sparsh.sparshur.devices.DeviceDatabase
import com.sparsh.sparshur.devices.DevicesActivity
import java.util.concurrent.Executors

/** The host only receives device labels and immutable actions, never clipboard or SMS content. */
class DevicesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        executor.execute { try { renderIds(context, manager, appWidgetIds) } finally { pending.finish() } }
    }
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        fun updateAll(context: Context) {
            val app = context.applicationContext
            executor.execute {
                val manager = AppWidgetManager.getInstance(app)
                renderIds(app, manager, manager.getAppWidgetIds(ComponentName(app, DevicesWidgetProvider::class.java)))
            }
        }
        private fun renderIds(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val widget = try {
                val primary = DeviceDatabase.get(context).primaryDevice().read()?.deviceId
                views(context, AvailableDevices(context).devices(), primary)
            } catch (_: Exception) {
                RemoteViews(context.packageName, R.layout.widget_devices).apply {
                    setTextViewText(R.id.widget_subtitle, "Device list unavailable. Open the app.")
                    setOnClickPendingIntent(R.id.widget_manage, manage(context))
                }
            }
            ids.forEach { manager.updateAppWidget(it, widget) }
        }
        internal fun views(context: Context, devices: List<ConnectedDevice> = AvailableDevices(context).devices(), primaryId: String? = null): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_devices).apply {
                removeAllViews(R.id.widget_device_list)
                if (devices.isEmpty()) addView(R.id.widget_device_list, RemoteViews(context.packageName, R.layout.widget_empty))
                devices.sortedByDescending { it.id == primaryId }.forEach { device ->
                    val row = RemoteViews(context.packageName, R.layout.widget_device_row).apply {
                        setTextViewText(R.id.widget_device_name, device.name + if (device.id == primaryId) " · Primary" else "")
                        setTextViewText(R.id.widget_device_status, "${device.platform} · Paired")
                        setContentDescription(R.id.widget_device_row, "Send clipboard to ${device.name}")
                        setOnClickPendingIntent(R.id.widget_device_row, openDevice(context, device.id))
                    }
                    addView(R.id.widget_device_list, row)
                }
                setOnClickPendingIntent(R.id.widget_manage, manage(context))
            }
        private fun manage(context: Context): PendingIntent = PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        internal fun openDevice(context: Context, deviceId: String): PendingIntent = PendingIntent.getActivity(context, 0,
            DevicesActivity.intent(context, deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
