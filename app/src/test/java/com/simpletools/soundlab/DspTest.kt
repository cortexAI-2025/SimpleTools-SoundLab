package com.simpletools.soundlab

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class DspTest {
    private val size = 4096
    private val rate = 48000
    private val bin = rate.toDouble() / size
    private fun tone(vararg tones: Pair<Double, Double>): ShortArray = ShortArray(size) { index ->
        (tones.sumOf { (hz, amplitude) -> amplitude * sin(2 * PI * hz * index / rate) } * 32768).roundToInt().toShort()
    }

    @Test fun pcmBoundariesAndClipping() {
        assertEquals(-1.0, Dsp.normalize(Short.MIN_VALUE), 0.0)
        assertEquals(32767.0 / 32768, Dsp.normalize(Short.MAX_VALUE), 0.0)
        assertTrue(Dsp.clipped(Short.MIN_VALUE))
        assertTrue(Dsp.clipped(Short.MAX_VALUE))
        assertFalse(Dsp.clipped(32766))
    }

    @Test fun rmsAndDbfsOfKnownTone() {
        val signal = tone(80 * bin to 0.5)
        val power = signal.map { Dsp.normalize(it).pow(2) }.average()
        assertEquals(0.5 / sqrt(2.0), Dsp.rms(power), 0.00002)
        assertEquals(-9.0309, Dsp.dbfs(power), 0.002)
    }

    @Test fun digitalSilenceHasFiniteFloorAndNoPeaks() {
        assertEquals(-120.0, Dsp.dbfs(0.0), 0.0)
        val spectrum = Fft(size, rate).analyze(ShortArray(size))
        assertTrue(spectrum.peaks.isEmpty())
        assertTrue(spectrum.levels.all { it == -120f })
    }

    @Test fun hannAmplitudeResolutionAndNyquist() {
        val spectrum = Fft(size, rate).analyze(tone(80 * bin to 0.5))
        assertEquals(-6.0206, spectrum.levels[80].toDouble(), 0.002)
        assertEquals(-12.0412, spectrum.levels[81].toDouble(), 0.002)
        assertEquals(bin, spectrum.binHz, 0.0)
        assertEquals(rate / 2.0, spectrum.binHz * spectrum.levels.lastIndex, 0.0)
        assertEquals(80 * bin, spectrum.peaks.first().hz, 0.01)
    }

    @Test fun twoTonesAndInterpolatedPeak() {
        val spectrum = Fft(size, rate).analyze(tone(1000.0 to 0.4, 2000.0 to 0.2))
        assertEquals(1000.0, spectrum.peaks[0].hz, 0.4)
        assertEquals(2000.0, spectrum.peaks[1].hz, 0.4)
        assertTrue(spectrum.peaks[0].db > spectrum.peaks[1].db)
    }

    @Test fun deterministicWhiteNoiseEnergyAndThreshold() {
        val random = Random(7353)
        val signal = ShortArray(size) { random.nextInt(-8192, 8192).toShort() }
        val spectrum = Fft(size, rate).analyze(signal)
        assertTrue(spectrum.levels.all { it.isFinite() && it < -30f })
        assertTrue(spectrum.peaks.isEmpty())
        val power = signal.map { Dsp.normalize(it).pow(2) }.average()
        assertEquals(0.25 / sqrt(3.0), Dsp.rms(power), 0.005)
    }

    @Test fun framesPreserveEnergyAndWaveExtrema() {
        val analyzer = Analyzer(1024, 16000)
        val input = ShortArray(1024).apply { this[87] = Short.MIN_VALUE }
        assertNull(analyzer.add(input, input.size))
        val frame = analyzer.add(ShortArray(1024), 1024)!!
        assertTrue(frame.clipped)
        assertEquals(1.0 / 2048, frame.power, 0.0)
        assertTrue(frame.wave.any { it.min == -1f })
        assertNotNull(frame.spectrum)
    }

    @Test fun equivalentLevelAndCalibrationUsePowerAndSampleWeights() {
        val stats = LevelStats().add(100, 0.01).add(300, 0.09)
        assertEquals(400L, stats.count)
        assertEquals(0.07, stats.power, 1e-12)
        assertEquals(-20.0, stats.min, 1e-12)
        assertEquals(Dsp.dbfs(0.09), stats.max, 1e-12)
        assertEquals(94.0, Dsp.dbfs(stats.power) + Dsp.offset(94.0, stats.power), 1e-12)
    }

    @Test fun comparisonUsesMatchingProfiles() {
        val session = Session("one", "A", 10L, 20L, "FFT", "MIC", "internal", 1, 48000, 4096,
            94.0, -24.0, 1L, count = 100, energy = 1.0, max = -15.0)
        val other = session.copy(id = "two", energy = 4.0, max = -9.0)
        val result = compare(session, other)
        assertEquals(6.0206, result.levelDifference, 0.0001)
        assertEquals(6.0, result.maxDifference, 0.0)
        assertTrue(result.splComparable)
        assertFalse(compare(session, other.copy(deviceId = 2)).splComparable)
        assertFalse(compare(session, other.copy(fftSize = 2048)).sameSpectrum)
    }
}
