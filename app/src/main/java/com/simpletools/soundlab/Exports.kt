package com.simpletools.soundlab

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val mime: String, val extension: String) {
    PDF("application/pdf", "pdf"), CSV("text/csv", "csv"), JSON("application/json", "json")
}

object Exports {
    fun write(format: ExportFormat, out: OutputStream, session: Session, points: List<Point>, french: Boolean) {
        when (format) {
            ExportFormat.PDF -> pdf(out, session, points, french)
            ExportFormat.CSV -> out.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("timestamp_unix_ms,elapsed_ms,rms,dbfs,dbspl_indicative,peak_hz,peak_dbfs,peak2_hz,peak2_dbfs,peak3_hz,peak3_dbfs,clipped")
                for (point in points) {
                    writer.appendLine(listOf(point.timestamp, point.elapsed, Dsp.rms(point.power), Dsp.dbfs(point.power),
                        session.offset?.let { Dsp.dbfs(point.power) + it }, point.hz, point.peakDb,
                        point.hz2, point.db2, point.hz3, point.db3, point.clipped).joinToString(",") { it?.toString().orEmpty() })
                }
            }
            ExportFormat.JSON -> out.bufferedWriter(Charsets.UTF_8).use { it.write(json(session, points).toString()) }
        }
    }

    private fun json(session: Session, points: List<Point>): JSONObject {
        val data = JSONArray()
        for (p in points) {
            val peaks = JSONArray()
            fun add(hz: Double?, db: Double?) {
                if (hz != null && db != null) peaks.put(JSONObject().put("hz", hz).put("dbfs_peak", db))
            }
            add(p.hz, p.peakDb); add(p.hz2, p.db2); add(p.hz3, p.db3)
            data.put(JSONObject().put("timestamp_unix_ms", p.timestamp).put("elapsed_ms", p.elapsed)
                .put("rms", Dsp.rms(p.power)).put("dbfs", Dsp.dbfs(p.power))
                .put("dbspl_indicative", session.offset?.let { Dsp.dbfs(p.power) + it } ?: JSONObject.NULL)
                .put("clipped", p.clipped).put("peaks", peaks))
        }
        return JSONObject().put("schema_version", 1).put("id", session.id).put("name", session.name)
            .put("started_unix_ms", session.start).put("ended_unix_ms", session.end ?: JSONObject.NULL)
            .put("duration_ms", session.duration).put("mode", session.mode).put("notes", session.notes)
            .put("acquisition", JSONObject().put("source", session.source).put("microphone", session.route)
                .put("sample_rate_hz", session.rate).put("fft_size", session.fftSize).put("window", "Hann"))
            .put("calibration", session.offset?.let {
                JSONObject().put("reference_dbspl", session.reference).put("observed_dbfs", session.observed)
                    .put("offset_db", it).put("date_unix_ms", session.calibrationDate)
            } ?: JSONObject.NULL)
            .put("statistics", if (session.count == 0L) JSONObject.NULL else JSONObject().put("rms", Dsp.rms(session.power))
                .put("equivalent_dbfs", Dsp.dbfs(session.power)).put("minimum_dbfs", session.min).put("maximum_dbfs", session.max))
            .put("measurements", data)
    }

    private fun pdf(out: OutputStream, session: Session, points: List<Point>, french: Boolean) {
        fun label(fr: String, en: String) = if (french) fr else en
        fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, if (french) Locale.FRANCE else Locale.UK)
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var y = 54f
        fun finishPage() {
            page.canvas.drawText("AIWORKPAY  ·  SimpleTools SoundLab  ·  ${pageNumber}", 42f, 805f, paint)
            pdf.finishPage(page)
        }
        fun space(height: Float) {
            if (y + height > 775f) {
                finishPage()
                page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                y = 54f
            }
        }
        fun line(text: String) {
            for (paragraph in text.lines()) {
                var remaining = paragraph
                do {
                    space(19f)
                    val length = paint.breakText(remaining, true, 511f, null).coerceAtLeast(1).coerceAtMost(remaining.length)
                    val boundary = if (length < remaining.length) remaining.lastIndexOf(' ', length).takeIf { it > 0 } ?: length else length
                    page.canvas.drawText(remaining.take(boundary), 42f, y, paint)
                    remaining = remaining.drop(boundary).trimStart()
                    y += 19f
                } while (remaining.isNotEmpty())
            }
        }
        try {
            paint.textSize = 21f
            line("SimpleTools SoundLab")
            paint.textSize = 12f
            y += 12f
            line(session.name)
            line(dates.format(Date(session.start)) + "  ·  " + number(session.duration / 1000.0) + " s")
            line("${session.source} / ${session.route}  ·  ${session.rate} Hz  ·  FFT ${session.fftSize}")
            line(label("Calibration", "Calibration") + "  ·  " +
                (session.offset?.let { number(it) + " dB / " + label("SPL indicatif", "indicative SPL") } ?: label("absente", "none")))
            if (session.count > 0) {
                line("RMS ${number(Dsp.rms(session.power))}  ·  Leq ${number(Dsp.dbfs(session.power))} dBFS")
                line("Min ${number(session.min)} dBFS  ·  Max ${number(session.max)} dBFS")
            }
            y += 8f
            if (points.isNotEmpty()) {
                space(155f)
                line(label("Niveau numérique (dBFS) / temps actif (s)", "Digital level (dBFS) / active time (s)"))
                chart(page.canvas, paint, y, points, session.duration, -120.0, 0.0) { Dsp.dbfs(it.power) }
                y += 134f
            }
            if (points.any { it.hz != null }) {
                space(155f)
                line(label("Pic principal (Hz) / temps actif (s)", "Dominant peak (Hz) / active time (s)"))
                chart(page.canvas, paint, y, points, session.duration, 0.0, session.rate / 2.0) { it.hz }
                y += 134f
            }
            line(label("Observations", "Notes"))
            line(session.notes.ifBlank { "—" })
            line(label("Mesure indicative. Un offset ne corrige ni la réponse en fréquence ni les traitements du microphone.",
                "Indicative measurement. An offset does not correct frequency response or microphone processing."))
            finishPage()
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun chart(canvas: Canvas, paint: Paint, top: Float, points: List<Point>, duration: Long,
                      min: Double, max: Double, value: (Point) -> Double?) {
        val left = 88f
        val right = 545f
        val bottom = top + 100
        canvas.drawLine(left, top, left, bottom, paint)
        canvas.drawLine(left, bottom, right, bottom, paint)
        canvas.drawText(max.toInt().toString(), 45f, top + 10, paint)
        canvas.drawText(min.toInt().toString(), 45f, bottom, paint)
        canvas.drawText("0", left, bottom + 15, paint)
        canvas.drawText("${duration / 1000.0} s", right - 62f, bottom + 15, paint)
        val step = (points.size / 450).coerceAtLeast(1)
        paint.color = Color.rgb(15, 102, 123)
        var previous: Pair<Float, Float>? = null
        for (i in points.indices step step) {
            val level = value(points[i])
            if (level == null) {
                previous = null
                continue
            }
            val x = left + (right - left) * (points[i].elapsed.toFloat() / duration.coerceAtLeast(1))
            val y = bottom - 100 * ((level - min) / (max - min)).toFloat().coerceIn(0f, 1f)
            previous?.let { canvas.drawLine(it.first, it.second, x, y, paint) } ?: canvas.drawCircle(x, y, 1f, paint)
            previous = x to y
        }
        paint.color = Color.BLACK
    }
}
