package com.sparsh.sparshur

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.sparsh.sparshur.devices.DeviceDatabase
import com.sparsh.sparshur.devices.PrimaryDevice
import org.junit.Assert.*
import org.junit.Test

class PrimaryDatabaseTest {
    @Test fun onlyOnePrimaryPersistsAcrossDatabaseCloseAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "primary-persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, DeviceDatabase::class.java, name).build()
        fun withDatabase(block: (DeviceDatabase) -> Unit) {
            val db = open()
            try { block(db) } finally { db.close() }
        }
        try {
            withDatabase { db ->
                assertNull(db.primaryDevice().read())
                db.primaryDevice().save(PrimaryDevice(deviceId = "fixture:first"))
                db.primaryDevice().save(PrimaryDevice(deviceId = "fixture:second"))
            }
            withDatabase { db ->
                assertEquals("fixture:second", db.primaryDevice().read()?.deviceId)
                assertEquals(1L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM primary_device").use { c -> c.moveToFirst(); c.getLong(0) })
                db.primaryDevice().clear()
            }
            withDatabase { assertNull(it.primaryDevice().read()) }
        } finally { context.deleteDatabase(name) }
    }
}
