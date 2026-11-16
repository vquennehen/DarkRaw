// File: app/src/main/java/com/darkroom/raw/data/PresetDatabase.kt
package com.darkroom.raw.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.darkroom.raw.model.DevelopParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "film_presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isBuiltIn: Boolean = false,
    val paramsJson: String
)

class PresetConverters {
    private val json = Json { ignoreUnknownKeys = true }
    fun fromParams(params: DevelopParams): String = json.encodeToString(params)
    fun toParams(data: String): DevelopParams = json.decodeFromString(data)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM film_presets ORDER BY isBuiltIn DESC, name ASC")
    fun observeAllPresets(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity): Long

    @Delete
    suspend fun deletePreset(preset: PresetEntity)
}

@Database(entities = [PresetEntity::class], version = 1, exportSchema = false)
@TypeConverters(PresetConverters::class)
abstract class PresetDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao

    companion object {
        @Volatile
        private var INSTANCE: PresetDatabase? = null

        fun getInstance(context: Context): PresetDatabase {
            return INSTANCE ?: synchronized(this) {
                val converter = PresetConverters()
                Room.databaseBuilder(
                    context.applicationContext,
                    PresetDatabase::class.java,
                    "darkroom_presets.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getInstance(context).presetDao()
                            dao.insertPreset(
                                PresetEntity(
                                    name = "Portra Warm",
                                    isBuiltIn = true,
                                    paramsJson = converter.fromParams(DevelopParams.PORTRA_WARM)
                                )
                            )
                            dao.insertPreset(
                                PresetEntity(
                                    name = "Tri-X B&W",
                                    isBuiltIn = true,
                                    paramsJson = converter.fromParams(DevelopParams.TRI_X_BW)
                                )
                            )
                            dao.insertPreset(
                                PresetEntity(
                                    name = "CineStill Cool",
                                    isBuiltIn = true,
                                    paramsJson = converter.fromParams(DevelopParams.CINESTILL_COOL)
                                )
                            )
                        }
                    }
                }).build().also { INSTANCE = it }
            }
        }
    }
}
