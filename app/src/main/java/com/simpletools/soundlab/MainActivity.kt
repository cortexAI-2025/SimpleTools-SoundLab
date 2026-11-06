package com.simpletools.soundlab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

class MainActivity : ComponentActivity() {
    internal val model by viewModels<SoundViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SoundLab(model) }
    }
    override fun onStart() { model.foreground(true); super.onStart() }
    override fun onStop() { model.foreground(false); super.onStop() }
}

private enum class Screen { HOME, METER, WAVE, FFT, PEAKS, VIBRATION, SESSION, HISTORY, COMPARE, CALIBRATE, SETTINGS }
private class Copy(val french: Boolean) {
    operator fun invoke(fr: String, en: String) = if (french) fr else en
    fun name(screen: Screen) = when (screen) {
        Screen.HOME -> "SimpleTools SoundLab"
        Screen.METER -> invoke("Sonomètre", "Sound meter")
        Screen.WAVE -> invoke("Oscilloscope", "Oscilloscope")
        Screen.FFT -> invoke("Spectre FFT", "FFT spectrum")
        Screen.PEAKS -> invoke("Fréquences dominantes", "Dominant frequencies")
        Screen.VIBRATION -> invoke("Vibrations acoustiques", "Acoustic vibrations")
        Screen.SESSION -> invoke("Session de mesure", "Measurement session")
        Screen.HISTORY -> invoke("Historique", "History")
        Screen.COMPARE -> invoke("Comparaison", "Comparison")
        Screen.CALIBRATE -> invoke("Calibration", "Calibration")
        Screen.SETTINGS -> invoke("Paramètres", "Settings")
    }
    fun mode(mode: String) = name(Screen.valueOf(mode))
    fun number(value: Double, digits: Int = 1) = String.format(if (french) Locale.FRANCE else Locale.UK, "%.${digits}f", value)
    fun date(value: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
        if (french) Locale.FRANCE else Locale.UK).format(Date(value))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundLab(model: SoundViewModel) {
    val context = LocalContext.current
    val tr = Copy(LocalConfiguration.current.locales[0].language != "en")
    val live by model.live.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val calibration by model.calibration.collectAsStateWithLifecycle()
    val appearance by model.appearance.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var fftSize by rememberSaveable { mutableIntStateOf(4096) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMode by rememberSaveable { mutableStateOf(Mode.METER) }
    var pendingCalibration by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.start(pendingMode, fftSize, tr("Mesure", "Measurement"), pendingCalibration)
        else model.permissionDenied()
    }
    fun start(mode: Mode, calibrating: Boolean = false) {
        pendingMode = mode
        pendingCalibration = calibrating
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            model.start(mode, fftSize, tr("Mesure", "Measurement"), calibrating)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun navigate(target: Screen) {
        if (screen == Screen.CALIBRATE && target != screen && live.calibrating) model.finish()
        screen = target
    }
    val back = {
        if (selected != null && screen == Screen.HISTORY) selected = null
        else navigate(Screen.HOME)
    }
    BackHandler(screen != Screen.HOME, onBack = back)
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val activeScreen = screen
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xff83d7e0))
        else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xff006875))) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(tr.name(activeScreen)) }, navigationIcon = {
                if (activeScreen != Screen.HOME) TextButton(onClick = back) { Text(tr("Retour", "Back")) }
            })
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                if (live.running) Text(tr("● Microphone actif", "● Microphone active"), color = MaterialTheme.colorScheme.primary)
                live.error?.let {
                    val error = when (it) {
                        CaptureFailure.PERMISSION -> tr("Microphone refusé. Autorisez-le dans les paramètres Android.", "Microphone denied. Allow it in Android settings.")
                        CaptureFailure.INITIALIZATION -> tr("Microphone indisponible ou format audio incompatible.", "Microphone unavailable or incompatible audio format.")
                        CaptureFailure.READ -> tr("Lecture audio interrompue. Vérifiez le microphone et réessayez.", "Audio read interrupted. Check the microphone and try again.")
                        CaptureFailure.ROUTE -> tr("Le périphérique audio a changé. Terminez la session avant une nouvelle mesure.", "The audio device changed. Finish the session before measuring again.")
                        CaptureFailure.SILENCED -> tr("Android a suspendu le microphone. Vérifiez les réglages de confidentialité.", "Android silenced the microphone. Check privacy settings.")
                    }
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (live.storageError) Text(tr("Une opération de stockage a échoué.", "A storage operation failed."), color = MaterialTheme.colorScheme.error)
                if (activeScreen == Screen.HISTORY) {
                    val session = sessions.firstOrNull { it.id == selected && it.end != null }
                    if (session != null) {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Detail(tr, model, session) { selected = null }
                        }
                    } else History(tr, sessions.filter { it.end != null }) { selected = it.id }
                } else key(activeScreen) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (activeScreen) {
                            Screen.HOME -> {
                                Text("by AIWORKPAY", style = MaterialTheme.typography.labelLarge)
                                Text(tr("Analyse acoustique locale", "Local acoustic analysis"), style = MaterialTheme.typography.headlineMedium)
                                Text(if (live.running) tr("Acquisition en cours", "Acquisition running") else tr("Microphone arrêté", "Microphone off"))
                                if (live.running && live.frame != null) Text("${tr.number(Dsp.dbfs(live.frame!!.power))} dBFS")
                                Text(if (calibration != null) tr("Profil de calibration enregistré", "Calibration profile saved")
                                    else tr("Mesure numérique dBFS par défaut", "Digital dBFS measurement by default"))
                                Button(onClick = { navigate(Screen.METER); if (!live.running) start(Mode.METER) }) { Text(tr("Mesurer", "Measure")) }
                                sessions.firstOrNull { it.end != null }?.let { session ->
                                    OutlinedButton(onClick = { selected = session.id; navigate(Screen.HISTORY) }) {
                                        Text(tr("Dernière session", "Last session") + " · " + session.name)
                                    }
                                }
                                for (item in Screen.entries.drop(1)) {
                                    OutlinedButton(onClick = { navigate(item) }, modifier = Modifier.fillMaxWidth()) { Text(tr.name(item)) }
                                }
                            }
                            Screen.CALIBRATE -> CalibrationPanel(tr, live, calibration, model) { start(Mode.METER, true) }
                            Screen.SETTINGS -> {
                                Text(tr("Apparence", "Appearance"), style = MaterialTheme.typography.titleLarge)
                                for (item in Appearance.entries) {
                                    FilterChip(selected = item == appearance, onClick = { model.theme(item) }, label = {
                                        Text(when (item) {
                                            Appearance.SYSTEM -> tr("Système", "System")
                                            Appearance.LIGHT -> tr("Clair", "Light")
                                            Appearance.DARK -> tr("Sombre", "Dark")
                                        })
                                    })
                                }
                                Text(tr("Les analyses et sessions restent sur cet appareil. Aucun fichier audio brut n’est conservé.",
                                    "Analysis and sessions stay on this device. Raw audio files are never saved."))
                                Text("SimpleTools SoundLab · 1.0.0")
                            }
                            Screen.COMPARE -> ComparePanel(tr, model, sessions.filter { it.end != null && it.count > 0 })
                            Screen.HISTORY -> Unit
                            else -> {
                                val mode = if (activeScreen == Screen.SESSION) Mode.METER else Mode.valueOf(activeScreen.name)
                                Controls(tr, live, model) { start(mode) }
                                when (activeScreen) {
                                    Screen.METER, Screen.SESSION -> Meter(tr, live)
                                    Screen.WAVE -> Wave(tr, live)
                                    Screen.FFT, Screen.PEAKS, Screen.VIBRATION -> {
                                        FftPanel(tr, live, fftSize) { fftSize = it }
                                        if (activeScreen != Screen.FFT) PeakHistory(tr, live.points, live.input?.rate ?: 48000)
                                        if (activeScreen == Screen.VIBRATION) {
                                            Text(tr("Observez les bruits répétitifs de ventilateurs, moteurs ou équipements. Les pics sont des indices acoustiques.",
                                                "Observe repeating sounds from fans, motors or equipment. Peaks are acoustic observations."))
                                            OutlinedButton(onClick = { navigate(Screen.COMPARE) }) { Text(tr("Comparer avant / après", "Compare before / after")) }
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(tr: Copy, live: Live, model: SoundViewModel, start: () -> Unit) {
    if (live.calibrating) {
        OutlinedButton(onClick = model::finish, enabled = !live.busy) { Text(tr("Fermer la calibration", "Close calibration")) }
        return
    }
    if (!live.running) {
        Button(onClick = start, enabled = !live.busy && (live.session?.duration ?: 0) < 3_600_000) {
            Text(if (live.session == null) tr("Démarrer", "Start") else tr("Reprendre", "Resume"))
        }
    } else OutlinedButton(onClick = model::pause) { Text(tr("Pause", "Pause")) }
    if (live.session != null || live.running) {
        OutlinedButton(onClick = model::finish, enabled = !live.busy) { Text(tr("Terminer", "Finish")) }
    }
    live.input?.let { Text("${it.rate} Hz · ${it.source} · ${it.route}") }
    live.session?.let { Text(tr("Temps actif", "Active time") + " · ${tr.number(it.duration / 1000.0)} s") }
    if (live.session == null && !live.running) Text(tr("Démarrez pour afficher un signal réel.", "Start to display a live signal."))
}

@Composable
private fun Meter(tr: Copy, live: Live) {
    val frame = live.frame ?: return
    val stats = live.stats
    val level = Dsp.dbfs(frame.power)
    Text("${tr.number(level)} dBFS", style = MaterialTheme.typography.displayMedium)
    val offset = live.session?.offset
    if (offset != null) Text("${tr.number(level + offset)} dB SPL · " + tr("indicatif", "indicative"))
    val recent = live.points.lastOrNull()?.let { last -> live.points.filter { it.elapsed >= last.elapsed - 1000 } }.orEmpty()
    if (recent.isNotEmpty()) Text(tr("Moyenne énergétique ~1 s", "~1 s energy average") + " · ${tr.number(Dsp.dbfs(recent.map { it.power }.average()))} dBFS")
    Text("Min ${tr.number(stats.min)} · Max ${tr.number(stats.max)} · Leq ${tr.number(Dsp.dbfs(stats.power))} dBFS")
    LevelHistory(tr, live.points)
    Text(tr("dBFS exprime le niveau numérique relatif à la pleine échelle.", "dBFS expresses the digital level relative to full scale."))
}

@Composable
private fun Wave(tr: Copy, live: Live) {
    var zoom by rememberSaveable { mutableFloatStateOf(100f) }
    var frozen by remember { mutableStateOf<Frame?>(null) }
    val frame = frozen ?: live.frame ?: return
    val rate = live.input?.rate ?: return
    val wave = frame.wave.takeLast((frame.wave.size * zoom * rate / (1000 * frame.waveSamples)).toInt().coerceIn(1, frame.wave.size))
    val span = frame.waveSamples * 1000.0 / rate * wave.size / frame.wave.size
    Text(tr("Amplitude normalisée", "Normalized amplitude"))
    Plot(tr("Forme d’onde", "Waveform"), "+1", "−1", "−${tr.number(span)} ms", "0 ms") { color ->
        val step = (wave.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
        for (i in wave.indices step step) {
            val x = i * size.width / (wave.size - 1).coerceAtLeast(1)
            val band = wave[i]
            val top = (1 - band.max) * size.height / 2
            val bottom = (1 - band.min) * size.height / 2
            drawLine(color, Offset(x, top), Offset(x, maxOf(top + 1f, bottom)), 2f)
        }
    }
    Text(tr("Fenêtre", "Window") + " · ${tr.number(zoom.toDouble(), 0)} ms")
    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 10f..200f,
        modifier = Modifier.semantics { contentDescription = tr("Fenêtre temporelle", "Time window") })
    OutlinedButton(onClick = { frozen = if (frozen == null) frame else null }) {
        Text(if (frozen == null) tr("Figer l’affichage", "Freeze display") else tr("Relancer l’affichage", "Unfreeze display"))
    }
    if (frame.clipped) Text(tr("Saturation PCM détectée", "PCM clipping detected"), color = MaterialTheme.colorScheme.error)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FftPanel(tr: Copy, live: Live, fft: Int, setFft: (Int) -> Unit) {
    var logarithmic by rememberSaveable { mutableStateOf(false) }
    val fftSize = live.session?.fftSize ?: fft
    Text("FFT · ${fftSize} · Hann")
    if (live.session == null && !live.running) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (n in listOf(1024, 2048, 4096, 8192)) {
                FilterChip(fft == n, { setFft(n) }, { Text(n.toString()) })
            }
        }
    }
    FilterChip(logarithmic, { logarithmic = !logarithmic },
        { Text(if (logarithmic) tr("Échelle logarithmique", "Logarithmic scale") else tr("Échelle linéaire", "Linear scale")) })
    val spectrum = live.frame?.spectrum ?: return
    val highest = spectrum.binHz * spectrum.levels.lastIndex
    Text(tr("Résolution", "Resolution") + " · ${tr.number(spectrum.binHz, 2)} Hz")
    Plot(tr("Spectre", "Spectrum"), "0 dBFS", "−120 dBFS",
        if (logarithmic) "${tr.number(spectrum.binHz)} Hz" else "0 Hz", "${tr.number(highest, 0)} Hz") { color ->
        val step = (spectrum.levels.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
        fun x(bin: Int): Float = if (logarithmic) size.width * (log10(bin.toDouble().coerceAtLeast(1.0)) /
            log10(spectrum.levels.lastIndex.toDouble())).toFloat() else size.width * bin / spectrum.levels.lastIndex
        var previous: Offset? = null
        for (bin in (if (logarithmic) 1 else 0)..spectrum.levels.lastIndex step step) {
            val db = (bin until minOf(bin + step, spectrum.levels.size)).maxOf { spectrum.levels[it] }
            val current = Offset(x(bin), size.height * (-db / 120).coerceIn(0f, 1f))
            previous?.let { drawLine(color, it, current, 2f) }
            previous = current
        }
    }
    if (spectrum.peaks.isEmpty()) Text(tr("Aucun pic au-dessus du seuil", "No peaks above threshold"))
    spectrum.peaks.forEachIndexed { index, peak ->
        Text("#${index + 1} · ${tr.number(peak.hz)} Hz · ${tr.number(peak.db)} dBFS")
    }
}

@Composable
private fun Plot(description: String, top: String, bottom: String, left: String, right: String,
                 draw: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.graphics.Color) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val axes = MaterialTheme.colorScheme.outline
    Text(top, style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
        drawLine(axes, Offset.Zero, Offset(0f, size.height))
        drawLine(axes, Offset(0f, size.height), Offset(size.width, size.height))
        drawLine(axes.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        draw(color)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$bottom · $left", style = MaterialTheme.typography.labelSmall)
        Text(right, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TimePlot(tr: Copy, points: List<Point>, title: String, top: String, bottom: String,
                     minimum: Double, maximum: Double, value: (Point) -> Double?) {
    if (points.isEmpty()) return
    val start = points.first().elapsed
    val end = points.last().elapsed
    Text(title)
    Plot(title, top, bottom, "${tr.number(start / 1000.0)} s", "${tr.number(end / 1000.0)} s") { color ->
        val step = (points.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
        var previous: Offset? = null
        for (index in points.indices step step) {
            val point = points[index]
            val level = value(point)
            if (level == null) { previous = null; continue }
            val x = size.width * (point.elapsed - start).toFloat() / (end - start).coerceAtLeast(1)
            val y = size.height * (1 - ((level - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f))
            val current = Offset(x, y)
            previous?.let { drawLine(color, it, current, 2f) } ?: drawCircle(color, 2f, current)
            previous = current
        }
    }
}

@Composable
private fun LevelHistory(tr: Copy, points: List<Point>) =
    TimePlot(tr, points, tr("Niveau numérique", "Digital level"), "0 dBFS", "−120 dBFS", -120.0, 0.0) { Dsp.dbfs(it.power) }

@Composable
private fun PeakHistory(tr: Copy, points: List<Point>, rate: Int) {
    TimePlot(tr, points, tr("Évolution du pic principal", "Dominant peak over time"), "${rate / 2} Hz", "0 Hz",
        0.0, rate / 2.0) { it.hz }
    TimePlot(tr, points, tr("Amplitude du pic principal", "Dominant peak amplitude"), "0 dBFS", "−120 dBFS",
        -120.0, 0.0) { it.peakDb }
}

@Composable
private fun CalibrationPanel(tr: Copy, live: Live, calibration: Calibration?, model: SoundViewModel, start: () -> Unit) {
    var reference by rememberSaveable { mutableStateOf("") }
    Text(tr("Utilisez une source stable et un sonomètre de référence. Lancez un échantillon de trois secondes.",
        "Use a stable source and a reference sound meter. Capture a three-second sample."))
    Text(tr("Un offset ne corrige ni la réponse en fréquence, ni la directivité, ni les traitements automatiques du microphone. SPL indicatif.",
        "An offset cannot correct frequency response, directionality or microphone processing. Indicative SPL."))
    if (calibration == null) Text(tr("Aucune calibration", "No calibration"))
    else {
        Text("${tr.number(calibration.reference)} dB SPL · ${tr.number(calibration.observed)} dBFS · Offset ${tr.number(calibration.offset)} dB")
        Text(tr.date(calibration.date) + " · ${calibration.input.source} · ${calibration.input.route} · ${calibration.input.rate} Hz")
        OutlinedButton(onClick = { model.resetCalibration() }) { Text(tr("Réinitialiser", "Reset")) }
    }
    if (live.session != null) {
        Text(tr("Terminez la session active pour calibrer.", "Finish the active session to calibrate."))
        return
    }
    if (live.running) OutlinedButton(onClick = model::pause) { Text(tr("Arrêter", "Stop")) }
    else Button(onClick = start) { Text(tr("Mesurer la référence", "Capture reference")) }
    live.calibrationSample?.let { sample ->
        Text("${tr.number(sample.duration / 1000.0)} s · ${tr.number(Dsp.dbfs(sample.power))} dBFS")
        if (sample.duration >= 3000 && !sample.stable) Text(tr("Signal instable. Recommencez.", "Unstable signal. Try again."))
    }
    OutlinedTextField(reference, { reference = it.take(12) }, label = { Text(tr("Référence (dB SPL)", "Reference (dB SPL)")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    val number = reference.replace(',', '.').toDoubleOrNull()
    Button(onClick = { number?.let(model::saveCalibration) }, enabled = !live.running &&
        live.calibrationSample?.let { it.stable && it.power > 1e-10 } == true && number != null && number in 20.0..140.0) {
        Text(tr("Enregistrer l’offset", "Save offset"))
    }
}

@Composable
private fun History(tr: Copy, sessions: List<Session>, select: (Session) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    OutlinedTextField(query, { query = it }, label = { Text(tr("Rechercher", "Search")) }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(tr("Type", "Type") + " · " + (type?.let(tr::mode) ?: tr("Tous", "All")))
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(tr("Tous les types", "All types")) }, onClick = { type = null; expanded = false })
            Mode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(tr.mode(mode.name)) }, onClick = { type = mode.name; expanded = false })
            }
        }
    }
    TextButton(onClick = { newest = !newest }) { Text(if (newest) tr("Plus récentes d’abord", "Newest first") else tr("Plus anciennes d’abord", "Oldest first")) }
    val filtered = sessions.filter {
        (type == null || it.mode == type) && (it.name.contains(query, true) || it.notes.contains(query, true))
    }.let { if (newest) it else it.asReversed() }
    if (filtered.isEmpty()) Text(tr("Aucune session à afficher.", "No sessions to show."))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(filtered, key = { it.id }) { session ->
            OutlinedCard(onClick = { select(session) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(session.name, style = MaterialTheme.typography.titleMedium)
                    Text(tr.date(session.start) + " · " + tr.mode(session.mode))
                }
            }
        }
    }
}

private data class LoadedPoints(val points: List<Point> = emptyList(), val failed: Boolean = false)
@Composable
private fun sessionPoints(model: SoundViewModel, id: String?): LoadedPoints {
    var state by remember(id) { mutableStateOf(LoadedPoints()) }
    LaunchedEffect(model, id) {
        if (id != null) state = try {
            LoadedPoints(model.points(id))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LoadedPoints(failed = true)
        }
    }
    return state
}

@Composable
private fun Detail(tr: Copy, model: SoundViewModel, session: Session, close: () -> Unit) {
    val loaded = sessionPoints(model, session.id)
    val points = loaded.points
    var name by rememberSaveable(session.id) { mutableStateOf(session.name) }
    var notes by rememberSaveable(session.id) { mutableStateOf(session.notes) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Text(tr.date(session.start) + " · " + tr.mode(session.mode))
    Text("${session.rate} Hz · ${session.source} · ${session.route}")
    Summary(tr, session)
    if (loaded.failed) Text(tr("Impossible de charger les mesures.", "Could not load measurements."))
    LevelHistory(tr, points)
    PeakHistory(tr, points, session.rate)
    OutlinedTextField(name, { name = it.take(100) }, label = { Text(tr("Nom", "Name")) }, singleLine = true)
    OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text(tr("Notes", "Notes")) })
    Button(enabled = name.isNotBlank() && (name.trim() != session.name || notes != session.notes),
        onClick = { model.edit(session.copy(name = name.trim(), notes = notes)) }) { Text(tr("Enregistrer les modifications", "Save changes")) }
    ExportPanel(tr, model, session)
    TextButton(onClick = { confirmDelete = true }) { Text(tr("Supprimer la session", "Delete session"), color = MaterialTheme.colorScheme.error) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Supprimer cette session ?", "Delete this session?")) },
        confirmButton = { TextButton(onClick = { model.delete(session); close() }) { Text(tr("Supprimer", "Delete")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Annuler", "Cancel")) } })
}

@Composable
private fun Summary(tr: Copy, session: Session) {
    Text("${tr.number(session.duration / 1000.0)} s")
    if (session.count > 0) {
        Text("RMS ${tr.number(Dsp.rms(session.power), 5)} · Leq ${tr.number(Dsp.dbfs(session.power))} dBFS")
        Text("Min ${tr.number(session.min)} · Max ${tr.number(session.max)} dBFS")
        session.offset?.let { Text("Leq ${tr.number(Dsp.dbfs(session.power) + it)} dB SPL · " + tr("indicatif", "indicative")) }
    }
}

@Composable
private fun ComparePanel(tr: Copy, model: SoundViewModel, sessions: List<Session>) {
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var second by rememberSaveable { mutableStateOf<String?>(null) }
    val before = sessions.firstOrNull { it.id == first }
    val after = sessions.firstOrNull { it.id == second }
    if (sessions.size < 2) {
        Text(tr("Enregistrez deux sessions avec des mesures.", "Save two sessions containing measurements."))
        return
    }
    SessionPicker(tr("Avant", "Before"), before, sessions.filter { it.id != second }) { first = it.id }
    SessionPicker(tr("Après", "After"), after, sessions.filter { it.id != first }) { second = it.id }
    val beforeData = sessionPoints(model, first)
    val afterData = sessionPoints(model, second)
    if (before == null || after == null) return
    val comparison = compare(before, after)
    if (!comparison.sameInput || !comparison.sameSpectrum || before.mode != after.mode) {
        Text(tr("Conditions différentes. Source, fréquence d’échantillonnage, FFT ou mode peuvent affecter l’écart.",
            "Conditions differ. Source, sample rate, FFT or mode can affect the difference."), color = MaterialTheme.colorScheme.error)
    }
    Text(tr("Delta après − avant", "After − before") + " · Leq ${tr.number(comparison.levelDifference)} dBFS · Max ${tr.number(comparison.maxDifference)} dBFS")
    Text(if (comparison.splComparable) tr("Profil SPL commun. Confirmez des conditions acoustiques identiques.",
        "Shared SPL profile. Confirm identical acoustic conditions.")
    else tr("Comparaison en dBFS. Les profils SPL ne sont pas compatibles.", "Comparison in dBFS. SPL profiles are incompatible."))
    if (beforeData.failed || afterData.failed) Text(tr("Impossible de charger les mesures.", "Could not load measurements."))
    listOf(before to beforeData.points, after to afterData.points).forEach { (session, points) ->
        Text(session.name, style = MaterialTheme.typography.titleLarge)
        Summary(tr, session)
        points.filter { it.hz != null }.maxByOrNull { it.peakDb ?: -120.0 }?.let {
            Text(tr("Pic le plus fort", "Strongest peak") + " · ${tr.number(it.hz!!)} Hz · ${tr.number(it.peakDb!!)} dBFS")
        }
        LevelHistory(tr, points)
        PeakHistory(tr, points, session.rate)
    }
}

@Composable
private fun SessionPicker(label: String, selected: Session?, sessions: List<Session>, select: (Session) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label + " · " + (selected?.name ?: "—")) }
        DropdownMenu(expanded, { expanded = false }) {
            sessions.forEach {
                DropdownMenuItem(text = { Text(it.name) }, onClick = { select(it); expanded = false })
            }
        }
    }
}

@Composable
private fun ExportPanel(tr: Copy, model: SoundViewModel, session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun write(format: ExportFormat, uri: Uri?, share: Boolean) {
        if (busy || (!share && uri == null)) return
        busy = true
        scope.launch {
            try {
                val points = model.points(session.id)
                val attachment = withContext(Dispatchers.IO) {
                    if (share) {
                        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
                        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                        val file = File.createTempFile("SoundLab-", ".${format.extension}", folder)
                        try {
                            file.outputStream().use { Exports.write(format, it, session, points, tr.french) }
                            FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                        } catch (error: Exception) {
                            file.delete()
                            throw error
                        }
                    } else {
                        val output = context.contentResolver.openOutputStream(uri!!, "wt") ?: throw IOException("No output")
                        output.use { Exports.write(format, it, session, points, tr.french) }
                        null
                    }
                }
                if (attachment != null) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = format.mime
                        putExtra(Intent.EXTRA_STREAM, attachment)
                        clipData = android.content.ClipData.newRawUri("SoundLab", attachment)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
                status = tr("Export prêt", "Export ready")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status = tr("Échec de l’export. Vérifiez la destination.", "Export failed. Check the destination.")
            } finally { busy = false }
        }
    }
    for (format in ExportFormat.entries) key(format) {
        val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { write(format, it, false) }
        OutlinedButton(enabled = !busy, onClick = { create.launch("SoundLab-${session.id.take(8)}.${format.extension}") }) {
            Text(tr("Exporter", "Export") + " " + format.name)
        }
        TextButton(enabled = !busy, onClick = { write(format, null, true) }) { Text(tr("Partager", "Share") + " " + format.name) }
    }
    if (status.isNotEmpty()) Text(status)
}
