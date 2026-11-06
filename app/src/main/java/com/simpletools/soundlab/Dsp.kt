package com.simpletools.soundlab

import kotlin.math.*

object Dsp {
    const val floor = -120.0
    fun normalize(sample: Short) = sample / 32768.0
    fun dbfs(power: Double) = (10.0 * log10(power.coerceAtLeast(1e-12)))
    fun rms(power: Double) = sqrt(power.coerceAtLeast(0.0))
    fun clipped(sample: Short) = sample == Short.MIN_VALUE || sample == Short.MAX_VALUE
    fun offset(reference: Double, power: Double) = reference - dbfs(power)
    fun resolution(rate: Int, size: Int) = rate.toDouble() / size
}

data class Peak(val hz: Double, val db: Double)
data class Band(val min: Float, val max: Float)
data class Spectrum(val levels: FloatArray, val peaks: List<Peak>, val binHz: Double)

class Fft(private val size: Int, private val rate: Int) {
    init { require(size >= 4 && size.countOneBits() == 1 && rate > 0) }

    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val gain = window.sum()
    private val bitShift = 32 - size.countTrailingZeroBits()
    private val cosine = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
    private val sine = DoubleArray(size / 2) { sin(-2 * PI * it / size) }

    // Oldest sample is at cursor in the circular buffer.
    fun analyze(samples: ShortArray, cursor: Int = 0): Spectrum {
        require(samples.size == size && cursor in samples.indices)
        for (i in 0 until size) {
            val reversed = Integer.reverse(i) ushr bitShift
            real[reversed] = Dsp.normalize(samples[(cursor + i) % size]) * window[i]
            imaginary[reversed] = 0.0
        }
        var span = 2
        while (span <= size) {
            val half = span / 2
            for (start in 0 until size step span) {
                for (j in 0 until half) {
                    val first = start + j
                    val second = first + half
                    val phase = j * (size / span)
                    val re = real[second] * cosine[phase] - imaginary[second] * sine[phase]
                    val im = real[second] * sine[phase] + imaginary[second] * cosine[phase]
                    real[second] = real[first] - re
                    imaginary[second] = imaginary[first] - im
                    real[first] += re
                    imaginary[first] += im
                }
            }
            span *= 2
        }
        val levels = FloatArray(size / 2 + 1) { i ->
            val scale = if (i == 0 || i == size / 2) 1.0 else 2.0
            val amplitude = hypot(real[i], imaginary[i]) * scale / gain
            Dsp.dbfs(amplitude * amplitude).toFloat()
        }
        val bin = Dsp.resolution(rate, size)
        val sorted = levels.sortedArray()
        val threshold = max(-95f, sorted[sorted.size / 2] + 12f)
        val maxima = (1 until levels.lastIndex).filter { i ->
            levels[i] >= threshold && levels[i] > levels[i - 1] && levels[i] >= levels[i + 1]
        }.sortedByDescending { levels[it] }
        val selected = mutableListOf<Int>()
        for (index in maxima) {
            if (selected.all { abs(it - index) >= 3 }) selected.add(index)
            if (selected.size == 3) break
        }
        val peaks = selected.map { index ->
            val left = levels[index - 1].toDouble()
            val center = levels[index].toDouble()
            val right = levels[index + 1].toDouble()
            val curvature = left - 2 * center + right
            val shift = if (curvature < -1e-6) (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5) else 0.0
            Peak((index + shift) * bin, center - 0.25 * (left - right) * shift)
        }
        return Spectrum(levels, peaks, bin)
    }
}

data class Frame(
    val count: Int,
    val power: Double,
    val clipped: Boolean,
    val wave: List<Band>,
    val waveSamples: Int,
    val spectrum: Spectrum?
)

class Analyzer(private val size: Int, private val rate: Int) {
    private val spectral = ShortArray(size)
    private val waveform = ShortArray(rate / 5)
    private val fft = Fft(size, rate)
    private var position = 0
    private var wavePosition = 0
    private var total = 0L
    private var count = 0
    private var energy = 0.0
    private var saturated = false

    // About eight updates per second. Long FFT windows overlap at lower sample rates.
    fun add(samples: ShortArray, length: Int): Frame? {
        for (index in 0 until length) {
            val sample = samples[index]
            val normalized = Dsp.normalize(sample)
            energy += normalized * normalized
            saturated = saturated || Dsp.clipped(sample)
            spectral[position] = sample
            position = (position + 1) % size
            waveform[wavePosition] = sample
            wavePosition = (wavePosition + 1) % waveform.size
        }
        count += length
        total += length
        if (count < rate / 8) return null

        val waveLength = min(total, waveform.size.toLong()).toInt()
        val buckets = min(waveLength, 480)
        val wave = List(buckets) { bucket ->
            val start = bucket * waveLength / buckets
            val end = (bucket + 1) * waveLength / buckets
            var minimum = 1f
            var maximum = -1f
            for (i in start until end) {
                val index = (wavePosition + waveform.size - waveLength + i) % waveform.size
                val normalized = Dsp.normalize(waveform[index]).toFloat()
                minimum = min(minimum, normalized)
                maximum = max(maximum, normalized)
            }
            Band(minimum, maximum)
        }
        val frame = Frame(count, energy / count, saturated, wave, waveLength,
            if (total >= size) fft.analyze(spectral, position) else null)
        count = 0
        energy = 0.0
        saturated = false
        return frame
    }
}

data class LevelStats(
    val count: Long = 0,
    val energy: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0
) {
    val power get() = if (count == 0L) 0.0 else energy / count
    fun add(samples: Int, power: Double): LevelStats {
        val level = Dsp.dbfs(power)
        return LevelStats(count + samples, energy + samples * power,
            if (count == 0L) level else min(min, level),
            if (count == 0L) level else max(max, level))
    }
}
