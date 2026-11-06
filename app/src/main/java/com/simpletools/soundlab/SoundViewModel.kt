package com.simpletools.soundlab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

private val captureDefaults = 4096
data class Live(
    val running: Boolean = false,
    val session: Session? = null,
    val input: Input? = null,
    val frame: Frame? = null,
    val stats: LevelStats = LevelStats(),
    val points: List<Point> = emptyList(),
    val error: CaptureFailure? = null,
    val storageError: Boolean = false,
    val busy: Boolean = false,
    val calibrating: Boolean = false,
    val calibrationSample: CalibrationSample? = null
)

class SoundViewModel(application: Application) : AndroidViewModel(application) {
    private val database = Room.databaseBuilder(application, SoundDatabase::class.java, "soundlab.db").build()
    private val repository = SessionRepository(database)
    private val settings = SettingsRepository(application)
    internal var capture: Capture = AudioCapture(application)
    private val initialized = viewModelScope.async { repository.recover() }
    private val mutable = MutableStateFlow(Live())
    val live = mutable.asStateFlow()
    val sessions = repository.sessions.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val calibration = settings.calibration.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val appearance = settings.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance.SYSTEM)
    private var job: Job? = null
    private val pending = mutableListOf<Point>()
    private var foreground = false
    private var referenceStats = LevelStats()
    private var referenceStart = 0L
    private var referenceDone = false

    fun foreground(active: Boolean) {
        foreground = active
        if (!active) pause()
    }

    fun permissionDenied() { mutable.update { it.copy(error = CaptureFailure.PERMISSION) } }

    fun start(mode: Mode, fftSize: Int = captureDefaults, name: String, forCalibration: Boolean = false) {
        val state = mutable.value
        if (!foreground || state.running || state.busy || (state.session != null && forCalibration)) return
        val resume = state.session
        if (resume == null) {
            referenceStats = LevelStats()
            referenceStart = System.currentTimeMillis()
            referenceDone = false
        }
        mutable.value = if (resume == null) Live(running = true, calibrating = forCalibration)
            else state.copy(running = true, error = null, storageError = false)
        job = viewModelScope.launch {
            try {
                initialized.await()
                capture.stream(resume?.fftSize ?: fftSize).collect { event ->
                    when (event) {
                        is CaptureEvent.Ready -> {
                            val input = event.input
                            if (resume != null && input != resume.input) throw CaptureException(CaptureFailure.ROUTE)
                            val session = resume ?: if (forCalibration) null else {
                                val profile = calibration.value?.takeIf { it.matches(input) }
                                Session(UUID.randomUUID().toString(), name, System.currentTimeMillis(), null,
                                    mode.name, input.source, input.route, input.deviceId, input.rate, fftSize,
                                    profile?.reference, profile?.observed, profile?.date).also {
                                    withContext(NonCancellable) {
                                        repository.create(it)
                                        mutable.update { state -> state.copy(session = it) }
                                    }
                                }
                            }
                            mutable.update { it.copy(input = input, session = session) }
                        }
                        is CaptureEvent.Signal -> accept(event.frame)
                    }
                }
            } catch (error: CaptureException) {
                mutable.update { it.copy(error = error.failure) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(storageError = true) }
            } finally {
                withContext(NonCancellable) {
                    safely { checkpoint() }
                    mutable.update { it.copy(running = false) }
                }
            }
        }
    }

    private suspend fun accept(frame: Frame) {
        val state = mutable.value
        val input = state.input ?: return
        val stats = state.stats.add(frame.count, frame.power)
        val now = System.currentTimeMillis()
        val session = state.session
        if (session == null) {
            if (referenceDone) return
            if (frame.clipped) {
                referenceStats = LevelStats()
                referenceStart = now
                mutable.value = state.copy(frame = frame, stats = stats, calibrationSample = null)
                return
            }
            referenceStats = referenceStats.add(frame.count, frame.power)
            val sample = CalibrationSample(referenceStart, referenceStats.count * 1000 / input.rate,
                referenceStats.power, referenceStats.max - referenceStats.min)
            referenceDone = sample.duration >= 3000
            if (referenceDone) pause()
            mutable.value = state.copy(frame = frame, stats = stats, calibrationSample = sample)
            return
        }
        val peaks = frame.spectrum?.peaks.orEmpty()
        val elapsed = stats.count * 1000 / input.rate
        val point = Point(session.id, elapsed, now, frame.power, peaks.getOrNull(0)?.hz, peaks.getOrNull(0)?.db,
            peaks.getOrNull(1)?.hz, peaks.getOrNull(1)?.db, peaks.getOrNull(2)?.hz, peaks.getOrNull(2)?.db, frame.clipped)
        pending.add(point)
        val updated = session.update(stats, now)
        mutable.value = state.copy(session = updated, frame = frame, stats = stats,
            points = (state.points + point).filter { it.elapsed >= elapsed - 30_000 })
        if (pending.size >= 8) withContext(NonCancellable) { checkpoint() }
        if (elapsed >= 60 * 60 * 1000) pause()
    }

    private suspend fun checkpoint(end: Boolean = false) {
        val session = mutable.value.session ?: return
        val snapshot = if (end) session.copy(end = System.currentTimeMillis()) else session
        repository.checkpoint(snapshot, pending.toList())
        pending.clear()
    }

    fun pause() {
        val active = job ?: return
        active.cancel()
    }

    fun finish() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            job?.cancelAndJoin()
            val success = safely { checkpoint(end = true) }
            mutable.update { if (success) Live() else it.copy(busy = false) }
        }
    }

    fun saveCalibration(reference: Double) {
        val state = mutable.value
        val input = state.input ?: return
        val sample = state.calibrationSample ?: return
        if (!state.calibrating || !sample.stable || sample.power <= 1e-10 || reference !in 20.0..140.0) return
        pause()
        viewModelScope.launch {
            safely { settings.save(Calibration(reference, Dsp.dbfs(sample.power), System.currentTimeMillis(), input)) }
        }
    }

    fun resetCalibration() = viewModelScope.launch { safely { settings.save(null) } }
    fun theme(appearance: Appearance) = viewModelScope.launch { safely { settings.appearance(appearance) } }
    suspend fun points(id: String) = repository.points(id)
    fun edit(session: Session) = viewModelScope.launch { safely { repository.edit(session) } }
    fun delete(session: Session) = viewModelScope.launch { safely { repository.delete(session) } }

    private suspend fun safely(action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        mutable.update { it.copy(storageError = true) }
        false
    }
}
