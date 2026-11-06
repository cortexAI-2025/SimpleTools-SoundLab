package com.simpletools.soundlab

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

enum class Mode { METER, WAVE, FFT, PEAKS, VIBRATION }
enum class Appearance { SYSTEM, LIGHT, DARK }
data class Calibration(val reference: Double, val observed: Double, val date: Long, val input: Input) {
    val offset get() = reference - observed
    fun matches(other: Input) = input == other
}

@Entity
data class Session(
    @PrimaryKey val id: String,
    val name: String,
    val start: Long,
    val end: Long?,
    val mode: String,
    val source: String,
    val route: String,
    val deviceId: Int,
    val rate: Int,
    val fftSize: Int,
    val reference: Double?,
    val observed: Double?,
    val calibrationDate: Long?,
    val count: Long = 0,
    val energy: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val notes: String = "",
    val lastSampleAt: Long = start
) {
    val input get() = Input(source, rate, route, deviceId)
    val duration get() = count * 1000 / rate
    val power get() = if (count == 0L) 0.0 else energy / count
    val offset get() = if (reference != null && observed != null) reference - observed else null
    fun update(stats: LevelStats, time: Long) = copy(count = stats.count, energy = stats.energy,
        min = stats.min, max = stats.max, lastSampleAt = time)
}

@Entity(primaryKeys = ["sessionId", "elapsed"],
    foreignKeys = [ForeignKey(entity = Session::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class Point(
    val sessionId: String,
    val elapsed: Long,
    val timestamp: Long,
    val power: Double,
    val hz: Double?,
    val peakDb: Double?,
    val hz2: Double?,
    val db2: Double?,
    val hz3: Double?,
    val db3: Double?,
    val clipped: Boolean
)

data class Comparison(val sameInput: Boolean, val sameSpectrum: Boolean, val splComparable: Boolean,
    val levelDifference: Double, val maxDifference: Double)

fun compare(before: Session, after: Session): Comparison {
    require(before.count > 0 && after.count > 0)
    val sameInput = before.input == after.input
    val spl = sameInput && before.calibrationDate != null && before.calibrationDate == after.calibrationDate &&
        before.offset != null && before.offset == after.offset
    return Comparison(sameInput, sameInput && before.fftSize == after.fftSize, spl,
        Dsp.dbfs(after.power) - Dsp.dbfs(before.power), after.max - before.max)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM Session ORDER BY start DESC") fun observe(): Flow<List<Session>>
    @Query("SELECT * FROM Point WHERE sessionId = :id ORDER BY elapsed") suspend fun points(id: String): List<Point>
    @Insert suspend fun insert(session: Session)
    @Insert suspend fun insertPoints(points: List<Point>)
    @Update suspend fun update(session: Session)
    @Delete suspend fun delete(session: Session)
    @Query("UPDATE Session SET `end` = lastSampleAt WHERE `end` IS NULL") suspend fun closeInterrupted()
}

@Database(entities = [Session::class, Point::class], version = 1, exportSchema = true)
abstract class SoundDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
}

class SessionRepository(private val database: SoundDatabase) {
    private val dao = database.sessions()
    val sessions = dao.observe()
    suspend fun create(session: Session) = dao.insert(session)
    suspend fun points(id: String) = dao.points(id)
    suspend fun edit(session: Session) = dao.update(session)
    suspend fun delete(session: Session) = dao.delete(session)
    suspend fun recover() = dao.closeInterrupted()
    suspend fun checkpoint(session: Session, points: List<Point>) = database.withTransaction {
        if (points.isNotEmpty()) dao.insertPoints(points)
        dao.update(session)
    }
}

private val Context.preferences by preferencesDataStore("soundlab")
private val calibrationKey = stringPreferencesKey("calibration")
private val appearanceKey = stringPreferencesKey("appearance")
class SettingsRepository(private val context: Context) {
    val calibration = context.preferences.data.map { preferences ->
        preferences[calibrationKey]?.let { value ->
            runCatching {
                val data = JSONObject(value)
                Calibration(data.getDouble("reference"), data.getDouble("observed"), data.getLong("date"),
                    Input(data.getString("source"), data.getInt("rate"), data.getString("route"), data.getInt("deviceId")))
            }.getOrNull()
        }
    }
    val appearance = context.preferences.data.map {
        it[appearanceKey]?.let(Appearance::valueOf) ?: Appearance.SYSTEM
    }
    suspend fun save(calibration: Calibration?) {
        context.preferences.edit { preferences ->
            if (calibration == null) preferences.remove(calibrationKey)
            else {
                preferences[calibrationKey] = JSONObject().put("reference", calibration.reference)
                    .put("observed", calibration.observed).put("date", calibration.date)
                    .put("source", calibration.input.source).put("rate", calibration.input.rate)
                    .put("route", calibration.input.route).put("deviceId", calibration.input.deviceId).toString()
            }
        }
    }
    suspend fun appearance(appearance: Appearance) {
        context.preferences.edit { it[appearanceKey] = appearance.name }
    }
}

data class CalibrationSample(val started: Long, val duration: Long, val power: Double, val spread: Double) {
    val stable get() = duration >= 3000 && spread <= 3.0
}
