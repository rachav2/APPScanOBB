package com.rachav.obddash.report

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.rachav.obddash.obd.MonState
import com.rachav.obddash.obd.Pids
import com.rachav.obddash.obd.TripMath
import com.rachav.obddash.obd.Verdict
import com.rachav.obddash.obd.BatteryEval
import com.rachav.obddash.ui.UiState
import com.rachav.obddash.ui.unitsOf
import com.rachav.obddash.ui.fmtDateTime
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reportes en PDF con PdfDocument de Android (sin librerías extra, desde Android 4.4). */
object PdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val DISCLAIMER =
        "Consumo, voltajes y chequeo del catalizador son estimaciones y no sustituyen un diagnóstico profesional."

    private class Writer(private val doc: PdfDocument, private val title: String) {
        private var page: PdfDocument.Page? = null
        private var pageNo = 0
        private var y = 0f
        private val body = Paint().apply { isAntiAlias = true; textSize = 11f; color = Color.BLACK }
        private val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        private val h1 = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 20f }
        private val h2 = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 14f; color = Color.rgb(20, 60, 140) }
        private val small = Paint(body).apply { textSize = 9f; color = Color.DKGRAY }

        init { newPage() }

        private fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M + 10f
        }

        private fun finishPage() {
            val p = page ?: return
            val c = p.canvas
            c.drawText(DISCLAIMER, M, H - 26f, small)
            c.drawText("$title · página $pageNo", M, H - 14f, small)
            doc.finishPage(p)
            page = null
        }

        private fun ensure(h: Float) {
            if (y + h > H - 50f) newPage()
        }

        private fun wrap(text: String, paint: Paint, width: Float): List<String> {
            val out = mutableListOf<String>()
            for (para in text.split('\n')) {
                var rest = para
                if (rest.isEmpty()) { out.add(""); continue }
                while (rest.isNotEmpty()) {
                    val n = paint.breakText(rest, true, width, null)
                    if (n >= rest.length) { out.add(rest); break }
                    var cut = rest.lastIndexOf(' ', n)
                    if (cut <= 0) cut = n
                    out.add(rest.substring(0, cut))
                    rest = rest.substring(cut).trimStart()
                }
            }
            return out
        }

        fun title(text: String) { ensure(30f); page!!.canvas.drawText(text, M, y + 16f, h1); y += 30f }

        fun heading(text: String) { y += 8f; ensure(24f); page!!.canvas.drawText(text, M, y + 12f, h2); y += 22f }

        fun line(text: String, bolded: Boolean = false, indent: Float = 0f) {
            val paint = if (bolded) bold else body
            for (l in wrap(text, paint, W - 2 * M - indent)) {
                ensure(15f)
                page!!.canvas.drawText(l, M + indent, y + 11f, paint)
                y += 14f
            }
        }

        fun bullet(text: String, indent: Float = 10f) = line("• $text", false, indent)

        fun close() { finishPage() }
    }

    private fun f(v: Double?, unit: String = "", d: Int = 1) =
        if (v == null) "--" else String.format(Locale.US, "%.${d}f", v) + if (unit.isNotEmpty()) " $unit" else ""

    private fun outFile(context: Context, prefix: String): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return File(dir, "${prefix}_$stamp.pdf")
    }

    private fun write(doc: PdfDocument, file: File): File {
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun health(context: Context, s: UiState): File {
        val doc = PdfDocument()
        val w = Writer(doc, "Reporte de salud")
        val p = s.profile
        w.title("Reporte de salud del vehículo")
        w.line("Fecha: ${fmtDateTime(System.currentTimeMillis())}")

        w.heading("Vehículo")
        w.line("Nombre: ${p.name}")
        w.line("Marca y modelo: ${listOf(p.brand, p.model, p.year?.toString()).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "--" }}")
        w.line("Cilindrada: ${f(p.displacementL, "L")}   Combustible: ${p.fuel}")
        w.line("VIN: ${p.vin ?: "no entregado por el auto"}")
        w.line("Adaptador: ${s.adapter ?: p.adapterName ?: "--"}")

        w.heading("Códigos de falla")
        if (!s.dtcsRead) w.line("No se leyeron códigos en esta sesión.")
        else if (s.dtcs.isEmpty()) w.line("Sin códigos almacenados ni pendientes.")
        s.dtcs.forEach { e ->
            val i = e.info
            w.line("${i.code} (${if (e.kind.name == "Stored") "almacenado" else "pendiente"}): ${i.description}", true)
            if (i.detailed) {
                w.line("Gravedad: ${when (i.severity) { 3 -> "grave"; 2 -> "atención"; else -> "informativo" }} · ${i.drivable}", false, 10f)
                if (i.causes.isNotEmpty()) { w.line("Causas probables:", true, 10f); i.causes.forEach { w.bullet(it, 20f) } }
                if (i.fixes.isNotEmpty()) { w.line("Qué revisar:", true, 10f); i.fixes.forEach { w.bullet(it, 20f) } }
            }
        }
        if (s.permanent.isNotEmpty()) w.line("Códigos permanentes: ${s.permanent.joinToString(", ")}")

        s.freeze?.let { ff ->
            w.heading("Cuadro congelado (${ff.code})")
            ff.values.forEach { (k, v) ->
                val info = Pids.info(k)
                w.line("${info.label}: ${f(v, info.unit, if (k == "010C" || k == "010D" || k == "010B") 0 else 1)}")
            }
        }

        w.heading("Monitores de emisiones (I/M)")
        if (s.monitors.isEmpty()) w.line("No se leyeron los monitores en esta sesión.")
        s.monitors.forEach { m ->
            w.line("${m.name}: " + when (m.sinceClear) { MonState.Ready -> "listo"; MonState.NotReady -> "no listo"; MonState.NotSupported -> "no soportado" })
        }

        w.heading("Prueba de batería y alternador")
        val b = s.batteryHistory.firstOrNull()
        if (b == null) w.line("Sin pruebas guardadas.") else {
            val ev = BatteryEval.evaluate(b.readings)
            w.line("Fecha: ${fmtDateTime(b.date)} · resultado: ${when (b.verdict) { Verdict.Pass -> "APROBADO"; Verdict.Warn -> "ADVERTENCIA"; Verdict.Fail -> "FALLA" }}", true)
            w.line("Reposo ${f(b.readings.rest, "V")} · arranque ${f(b.readings.crankMin, "V")} · ralentí ${f(b.readings.idle, "V")} · 2500 RPM ${f(b.readings.high, "V")}")
            ev.notes.forEach { w.bullet(it) }
        }

        w.heading("Fuel trims")
        val trims = listOf("0106" to "Corto B1", "0107" to "Largo B1", "0108" to "Corto B2", "0109" to "Largo B2").filter { s.live.containsKey(it.first) }
        if (trims.isEmpty()) w.line("Sin lecturas de fuel trims en esta sesión.")
        trims.forEach { (k, n) -> w.line("$n: ${f(s.live[k], "%")}") }

        w.heading("Sensores de oxígeno")
        val o2 = (Pids.O2_NARROW + Pids.O2_WIDE).filter { s.live.containsKey(it) }
        if (o2.isEmpty()) w.line("Sin lecturas de sensores de oxígeno en esta sesión.")
        o2.forEach { k ->
            val info = Pids.info(k)
            w.line("${info.label}: ${f(s.live[k], info.unit, if (info.unit == "λ") 3 else 2)}")
        }

        w.line("")
        w.line(DISCLAIMER)
        w.close()
        return write(doc, outFile(context, "reporte_salud"))
    }

    fun trip(context: Context, s: UiState): File {
        val doc = PdfDocument()
        val w = Writer(doc, "Reporte de viaje")
        val p = s.profile
        val u = unitsOf(p)
        val t = s.trip
        w.title("Reporte de viaje")
        w.line("Fecha: ${fmtDateTime(System.currentTimeMillis())}")
        w.heading("Vehículo")
        w.line("${p.name} · ${f(p.displacementL, "L")} · ${p.fuel}")
        w.heading("Viaje en curso")
        w.line("Distancia: ${f(u.dist(t.distanceKm), u.distLabel)}")
        w.line("Tiempo: ${t.timeText}")
        w.line("Velocidad promedio: ${t.avgSpeed} km/h · máxima: ${t.maxSpeed} km/h")
        w.line("Combustible estimado: ${f(if (t.fuelKnown) u.vol(t.fuelL) else null, u.volLabel, 2)}")
        w.line("Consumo promedio: ${t.avgConsumption?.let { u.consumption(it) } ?: "--"}")
        p.pricePerL?.let { price ->
            w.line("Precio por ${u.volLabel}: ${u.currency}${f(u.price(price), "", 2)}")
            if (t.fuelKnown) w.line("Costo del viaje: ${u.currency}${f(t.fuelL * price, "", 2)}", true)
        }
        w.heading("Calibración con tanqueos")
        w.line("Factor de corrección del consumo: ${f(p.fuelCorrection, "", 3)} (litros reales entre litros estimados)")
        if (s.fillups.isEmpty()) w.line("Sin tanqueos registrados.")
        s.fillups.take(8).forEach { fu ->
            w.line("${fmtDateTime(fu.date)}: ${f(u.vol(fu.liters), u.volLabel, 2)} a ${u.currency}${f(u.price(fu.pricePerL), "", 2)} · ${f(u.dist(fu.km), u.distLabel)} · factor ${f(fu.factor, "", 3)}")
        }
        w.line("")
        w.line(DISCLAIMER)
        w.close()
        return write(doc, outFile(context, "reporte_viaje"))
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartir reporte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
