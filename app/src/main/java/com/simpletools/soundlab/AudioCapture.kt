package com.simpletools.soundlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

data class Input(val source: String, val rate: Int, val route: String, val deviceId: Int)
sealed interface CaptureEvent {
    data class Ready(val input: Input) : CaptureEvent
    data class Signal(val frame: Frame) : CaptureEvent
}
enum class CaptureFailure { PERMISSION, INITIALIZATION, READ, ROUTE, SILENCED }
class CaptureException(val failure: CaptureFailure) : IOException(failure.name)

fun interface Capture {
    fun stream(fftSize: Int): Flow<CaptureEvent>
}

class AudioCapture(private val context: Context) : Capture {
    private val audio = context.getSystemService(AudioManager::class.java)

    @SuppressLint("MissingPermission")
    override fun stream(fftSize: Int): Flow<CaptureEvent> = flow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw CaptureException(CaptureFailure.PERMISSION)
        }
        val (record, source) = open()
        try {
            val block = ShortArray(1024)
            val analyzer = Analyzer(fftSize, record.sampleRate)
            var input: Input? = null
            var emptyReads = 0
            var reportedFrames = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val length = record.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                if (length < 0 || emptyReads >= 500) throw CaptureException(CaptureFailure.READ)
                if (length == 0) {
                    emptyReads++
                    delay(10)
                    continue
                }
                emptyReads = 0
                val frame = analyzer.add(block, length) ?: continue
                val route = record.routedDevice
                val current = Input(source, record.sampleRate, route.label(), route?.id ?: -1)
                if (input == null) {
                    input = current
                    emit(CaptureEvent.Ready(current))
                } else if (input != current) {
                    throw CaptureException(CaptureFailure.ROUTE)
                }
                if (reportedFrames++ % 4 == 0 && record.activeRecordingConfiguration?.isClientSilenced == true) {
                    throw CaptureException(CaptureFailure.SILENCED)
                }
                emit(CaptureEvent.Signal(frame))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, String> {
        val sources = buildList {
            if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
            add(MediaRecorder.AudioSource.MIC to "MIC")
        }
        for ((source, label) in sources) {
            for (rate in listOf(48000, 44100, 16000)) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) continue
                val record = try {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 4096))
                } catch (_: IllegalArgumentException) {
                    continue
                } catch (_: SecurityException) {
                    throw CaptureException(CaptureFailure.PERMISSION)
                }
                var started = false
                try {
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.startRecording()
                        started = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                        if (started) return record to label
                    }
                } catch (_: IllegalStateException) {
                    // Try the next supported rate/source.
                } catch (_: SecurityException) {
                    throw CaptureException(CaptureFailure.PERMISSION)
                } finally {
                    if (!started) record.release()
                }
            }
        }
        throw CaptureException(CaptureFailure.INITIALIZATION)
    }

    private fun AudioDeviceInfo?.label(): String =
        this?.let { "${it.type}: ${it.productName}" } ?: "Android default"
}
