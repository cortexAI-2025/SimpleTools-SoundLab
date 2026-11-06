package com.simpletools.soundlab

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun permissionGateRejectsBeforeCapture() = runBlocking {
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        val failure = runCatching { AudioCapture(denied).stream(1024).collect() }.exceptionOrNull()
        assertEquals(CaptureFailure.PERMISSION, (failure as CaptureException).failure)
    }

    @Test fun persistenceRecoveryAndCascadeDeletion() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        val session = example()
        val point = point()
        fun open() = Room.databaseBuilder(context, SoundDatabase::class.java, name).build()
        try {
            open().use { database ->
                val repository = SessionRepository(database)
                repository.create(session)
                repository.checkpoint(session, listOf(point))
            }
            open().use { database ->
                val repository = SessionRepository(database)
                repository.recover()
                val restored = repository.sessions.first().single()
                assertEquals(session.lastSampleAt, restored.end ?: -1L)
                assertEquals(listOf(point), repository.points(session.id))
                repository.delete(restored)
                assertTrue(repository.points(session.id).isEmpty())
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun csvJsonAndPdfContainSavedMeasurements() {
        val session = example()
        val point = point()
        fun export(format: ExportFormat) = ByteArrayOutputStream().also {
            Exports.write(format, it, session, listOf(point), true)
        }.toByteArray()
        val csv = export(ExportFormat.CSV).decodeToString()
        assertTrue(csv.contains("dbspl_indicative"))
        assertTrue(csv.contains("101,125,0.1,-20.0,,1000.0,-18.0"))
        val json = JSONObject(export(ExportFormat.JSON).decodeToString())
        assertEquals(1, json.getInt("schema_version"))
        assertEquals(48000, json.getJSONObject("acquisition").getInt("sample_rate_hz"))
        assertTrue(json.getJSONArray("measurements").getJSONObject(0).isNull("dbspl_indicative"))
        val file = File.createTempFile("soundlab", ".pdf", context.cacheDir)
        try {
            file.writeBytes(export(ExportFormat.PDF))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { assertEquals(1, it.pageCount) }
            }
        } finally { file.delete() }
    }

    private fun example() = Session("test", "Test", 100L, null, "FFT", "MIC", "built-in", 1, 48000, 4096,
        null, null, null, 6000L, 60.0, -20.0, -20.0, lastSampleAt = 101L)
    private fun point() = Point("test", 125L, 101L, 0.01, 1000.0, -18.0, null, null, null, null, false)
}

@RunWith(AndroidJUnit4::class)
class CaptureLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pauseResumeFinishAndBackgroundReleaseSharedCapture() {
        val scenario = compose.activityRule.scenario
        val opens = AtomicInteger()
        val releases = AtomicInteger()
        scenario.onActivity { activity ->
            activity.model.capture = Capture {
                flow {
                    opens.incrementAndGet()
                    try {
                        emit(CaptureEvent.Ready(Input("test", 48000, "test", 0)))
                        emit(CaptureEvent.Signal(Frame(6000, 0.01, false, listOf(Band(-0.1f, 0.1f)), 6000, null)))
                        awaitCancellation()
                    } finally { releases.incrementAndGet() }
                }
            }
        }
        compose.onNodeWithText("SimpleTools SoundLab").assertExists()
        compose.onNodeWithText("Oscilloscope").performClick()
        scenario.onActivity { it.model.start(Mode.WAVE, 1024, "Lifecycle test") }
        compose.waitUntil(8000) { compose.activity.model.live.value.frame != null }
        compose.onNodeWithText("Pause").performClick()
        compose.waitUntil(8000) { !compose.activity.model.live.value.running && releases.get() == 1 }
        scenario.onActivity { it.model.start(Mode.WAVE, 1024, "Lifecycle test") }
        compose.waitUntil(8000) { opens.get() == 2 }
        scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(8000) { releases.get() == 2 }
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(8000) { !compose.activity.model.live.value.running }
        scenario.onActivity { it.model.finish() }
        compose.waitUntil(8000) { !compose.activity.model.live.value.busy && compose.activity.model.live.value.session == null }
    }

    @Test fun initializationFailureIsVisibleAndRecreationKeepsUi() {
        val scenario = compose.activityRule.scenario
        scenario.onActivity { activity ->
            activity.model.capture = Capture { flow { throw CaptureException(CaptureFailure.INITIALIZATION) } }
            activity.model.start(Mode.METER, 1024, "Test")
        }
        compose.waitUntil(8000) { compose.activity.model.live.value.error == CaptureFailure.INITIALIZATION }
        scenario.recreate()
        compose.onNodeWithText("SimpleTools SoundLab").assertExists()
        assertEquals(CaptureFailure.INITIALIZATION, compose.activity.model.live.value.error)
    }
}
