package com.sparsh.sparshur.devices

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** One row means there can be only one Primary choice. Never stores message content or keys. */
@Entity(tableName = "primary_device")
data class PrimaryDevice(@PrimaryKey val slot: Int = 1, val deviceId: String)

@Dao
interface PrimaryDeviceDao {
    @Query("SELECT * FROM primary_device WHERE slot = 1")
    fun read(): PrimaryDevice?
    @Upsert fun save(choice: PrimaryDevice)
    @Query("DELETE FROM primary_device") fun clear()
}

@Database(entities = [PrimaryDevice::class], version = 1, exportSchema = true)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun primaryDevice(): PrimaryDeviceDao
    companion object {
        @Volatile private var instance: DeviceDatabase? = null
        fun get(context: Context): DeviceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DeviceDatabase::class.java, "device-choices.db")
                .build().also { instance = it }
        }
    }
}
