package com.rachav.obddash.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rachav.obddash.obd.CatalystResult
import com.rachav.obddash.obd.CatalystCheck
import com.rachav.obddash.obd.CatalystStatus
import com.rachav.obddash.obd.FuelModel
import com.rachav.obddash.obd.ObdData
import com.rachav.obddash.obd.Pids
import com.rachav.obddash.obd.TrimHint
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/** Pide al ViewModel que lea estas variables mientras la pantalla está abierta. */
@Composable
fun WatchEffect(vm: ObdViewModel, keys: Set<String>, exclusive: Boolean = false) {
    DisposableEffect(keys, exclusive) {
        vm.watch(keys, exclusive)
        onDispose { vm.unwatch() }
    }
}

private fun isLive(state: UiState) = state.conn == ConnState.Connected || state.conn == ConnState.Demo

@Composable
private fun NeedConnection(state: UiState) {
    if (!isLive(state)) Note("Conéctate al adaptador (o usa el modo demo) para ver datos en vivo.", Palette.Amber)
}

private fun f1(v: Double?, unit: String = ""): String =
    if (v == null) "--" else String.format(Locale.US, "%.1f", v) + if (unit.isNotEmpty()) " $unit" else ""

// ============================================================================ GRÁFICAS

class ChartSeries(val key: String, val samples: List<Sample>, val color: Color)

/** Gráfico propio en Canvas: hasta dos variables con ejes independientes (izquierdo y derecho). */
@Composable
fun LineChart(series: List<ChartSeries>, windowMs: Long, endMs: Long, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val padL = 56.dp.toPx()
        val padR = if (series.size > 1) 56.dp.toPx() else 12.dp.toPx()
        val padT = 10.dp.toPx()
        val padB = 22.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        if (w <= 0f || h <= 0f) return@Canvas
        for (i in 0..4) {
            val y = padT + h * i / 4f
            drawLine(Palette.Track, Offset(padL, y), Offset(padL + w, y), 1f)
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 11.sp.toPx()
        }
        val start = endMs - windowMs
        series.forEachIndexed { idx, s ->
            val pts = s.samples.filter { it.t >= start }
            if (pts.isEmpty()) return@forEachIndexed
            var mn = pts.minOf { it.v }
            var mx = pts.maxOf { it.v }
            if (mx - mn < 1e-6) {
                mn -= 1.0
                mx += 1.0
            }
            val pad = (mx - mn) * 0.08
            mn -= pad
            mx += pad
            val path = Path()
            pts.forEachIndexed { i, p ->
                val x = padL + w * ((p.t - start).toFloat() / windowMs.toFloat())
                val y = padT + h * (1f - ((p.v - mn) / (mx - mn)).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
            paint.color = s.color.toArgb()
            paint.textAlign = if (idx == 0) android.graphics.Paint.Align.LEFT else android.graphics.Paint.Align.RIGHT
            val tx = if (idx == 0) 4.dp.toPx() else size.width - 4.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(axisText(mx), tx, padT + 10.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText(axisText(mn), tx, padT + h, paint)
        }
        paint.color = Palette.TextDim.toArgb()
        paint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("-${windowMs / 1000} s", padL, size.height - 5.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText("ahora", padL + w, size.height - 5.dp.toPx(), paint)
    }
}

private fun axisText(v: Double): String = if (abs(v) >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

@Composable
fun GraphScreen(state: UiState, vm: ObdViewModel) {
    val context = LocalContext.current
    val avail = Pids.GRAPHABLE.filter { it == "VOLT" || state.supported.isEmpty() || it in state.supported }
    var a by rememberSaveable { mutableStateOf("010C") }
    var b by rememberSaveable { mutableStateOf("VOLT") }
    var windowS by rememberSaveable { mutableStateOf(60) }
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Map<String, List<Sample>>>(emptyMap()) }
    var frozenAt by remember { mutableStateOf(0L) }

    val keys = if (b.isEmpty()) setOf(a) else setOf(a, b)
    WatchEffect(vm, keys, true)

    val data = if (paused) frozen else state.series
    val end = if (paused) frozenAt else System.currentTimeMillis()
    val colors = listOf(Palette.Cyan, Palette.Amber)
    val shown = keys.toList().mapIndexed { i, k -> ChartSeries(k, data[k].orEmpty(), colors[i % 2]) }

    AdaptivePane(
        first = {
            NeedConnection(state)
            LineChart(
                shown, windowS * 1000L, end,
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(Palette.CardBottom),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                shown.forEach { s ->
                    val info = Pids.info(s.key)
                    Text("${info.label}: ${f1(state.live[s.key], info.unit)}", color = s.color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        second = {
            Text("Variable 1 (eje izquierdo)", color = Palette.TextDim, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                avail.forEach { k -> Chip(Pids.info(k).label, a == k, { a = k }) }
            }
            Text("Variable 2 (eje derecho)", color = Palette.TextDim, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Ninguna", b.isEmpty(), { b = "" })
                avail.filter { it != a }.forEach { k -> Chip(Pids.info(k).label, b == k, { b = k }) }
            }
            Text("Ventana", color = Palette.TextDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("30 s", windowS == 30, { windowS = 30 })
                Chip("1 min", windowS == 60, { windowS = 60 })
                Chip("5 min", windowS == 300, { windowS = 300 })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!paused) {
                        frozen = state.series
                        frozenAt = System.currentTimeMillis()
                    }
                    paused = !paused
                }) { Text(if (paused) "Reanudar" else "Pausa") }
                OutlinedButton(onClick = { vm.setRecording(!state.recording) }) { Text(if (state.recording) "Detener grabación" else "Grabar CSV") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareText(context, "Sesión OBD (CSV)", vm.recordingCsv()) }, enabled = !state.recording && vm.recordingCsv().length > 30) {
                    Text("Compartir CSV")
                }
                OutlinedButton(onClick = vm::clearSeries) { Text("Limpiar") }
            }
            Note("En modo gráfica solo se consultan las variables elegidas, para subir la frecuencia de muestreo (el Bluetooth da unos 5 a 10 datos por segundo en total). Con dos variables cada una se actualiza más despacio que con una.")
            if (state.recording) Note("● Grabando…", Palette.Red)
        },
    )
}

// ============================================================================ FUEL TRIMS

private fun trimColor(total: Double): Color = when {
    abs(total) <= 10 -> Palette.Green
    abs(total) <= 20 -> Palette.Amber
    else -> Palette.Red
}

/** Medidor centrado en 0 %: verde hasta ±10 %, ámbar hasta ±20 %, rojo por encima. */
@Composable
fun CenteredBar(value: Double, maxAbs: Double = 30.0, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val r = 6.dp.toPx()
        drawRoundRect(Palette.Track, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        val cx = size.width / 2f
        val v = value.coerceIn(-maxAbs, maxAbs)
        val x = cx + (v / maxAbs).toFloat() * cx
        val left = minOf(cx, x)
        drawRect(trimColor(value), Offset(left, 0f), Size(abs(x - cx), size.height))
        for (t in listOf(-20.0, -10.0, 10.0, 20.0)) {
            val tx = cx + (t / maxAbs).toFloat() * cx
            drawLine(Palette.Bg, Offset(tx, 0f), Offset(tx, size.height), 2f)
        }
        drawLine(Palette.Text, Offset(cx, 0f), Offset(cx, size.height), 3f)
    }
}

@Composable
fun TrimsScreen(state: UiState, vm: ObdViewModel) {
    WatchEffect(vm, setOf("0106", "0107", "0108", "0109", "0103", "010C"), false)
    val live = state.live
    val b2 = "0108" in state.supported || live.containsKey("0108")

    // Pista automática: ajuste total contra RPM.
    val rpm = state.series["010C"].orEmpty()
    val shorts = state.series["0106"].orEmpty()
    val longTerm = live["0107"] ?: 0.0
    val pairs = if (rpm.isEmpty()) emptyList() else shorts.map { sm ->
        val near = rpm.minByOrNull { abs(it.t - sm.t) }!!
        near.v to (sm.v + longTerm)
    }
    val hint = TrimHint.analyze(pairs)

    AdaptivePane(
        first = {
            NeedConnection(state)
            BankCard("Banco 1", live["0106"], live["0107"])
            if (b2) BankCard("Banco 2", live["0108"], live["0109"])
            val status = live["0103"]?.toInt()
            if (status != null) {
                Section("Estado del sistema de combustible") {
                    Text(
                        when {
                            status and 2 != 0 -> "Lazo cerrado: la ECU corrige con el sensor de oxígeno."
                            status and 4 != 0 -> "Lazo abierto por carga del motor."
                            status and 1 != 0 -> "Lazo abierto: motor frío o sensor aún sin calentar."
                            status and 8 != 0 -> "Lazo abierto por una falla del sistema."
                            status and 16 != 0 -> "Lazo cerrado con falla en el sensor."
                            else -> "Estado $status"
                        },
                        color = Palette.Text, fontSize = 14.sp,
                    )
                }
            }
        },
        second = {
            hint?.let { Section("Pista automática") { Text(it, color = Palette.Cyan, fontSize = 14.sp, lineHeight = 19.sp) } }
            Note("Positivo: la ECU agrega combustible (mezcla pobre: aire falso, MAF sucio o presión de combustible baja). Negativo: lo quita (mezcla rica). Verde hasta ±10 %, ámbar hasta ±20 %, rojo por encima.")
            if (hint == null) Note("Para la pista automática deja el motor en ralentí un rato y luego sube las RPM a unas 2000.")
        },
    )
}

@Composable
private fun BankCard(title: String, short: Double?, long: Double?) {
    val total = if (short == null && long == null) null else (short ?: 0.0) + (long ?: 0.0)
    Section(title) {
        KeyValue("Ajuste corto", f1(short, "%"))
        KeyValue("Ajuste largo", f1(long, "%"))
        KeyValue("Total", f1(total, "%"))
        CenteredBar(total ?: 0.0)
    }
}

// ============================================================================ OXÍGENO Y CATALIZADOR

@Composable
fun OxygenScreen(state: UiState, vm: ObdViewModel) {
    fun has(k: String) = if (state.supported.isEmpty()) state.live.containsKey(k) else k in state.supported
    val narrow = Pids.O2_NARROW.filter { has(it) }
    val wide = Pids.O2_WIDE.filter { has(it) }
    val cats = Pids.CAT_TEMP.filter { has(it) }

    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CatalystResult?>(null) }
    val normal = (narrow + wide + cats + listOf("010C", "0105")).toSet().let { if (it.size <= 2) it + setOf("0114", "0115", "013C") else it }
    val keys = if (testing) setOf("0114", "0115", "010C", "0105") else normal
    WatchEffect(vm, keys, testing)

    LaunchedEffect(testing) {
        if (!testing) return@LaunchedEffect
        vm.clearSeries()
        delay(30_000)
        val s = vm.ui.value
        val coolant = s.live["0105"]
        val rpms = s.series["010C"].orEmpty()
        val okRpm = rpms.isNotEmpty() && rpms.count { it.v in 2000.0..3000.0 } >= rpms.size * 0.8
        result = when {
            coolant != null && coolant < 80 -> CatalystResult(CatalystStatus.Inconclusive, "El motor no está caliente (${coolant.toInt()} °C). Caliéntalo a unos 85 °C y repite.")
            !okRpm -> CatalystResult(CatalystStatus.Inconclusive, "Las RPM no se mantuvieron cerca de 2500. Repite con el pie firme.")
            else -> CatalystCheck.evaluate(
                s.series["0114"].orEmpty().map { it.v },
                s.series["0115"].orEmpty().map { it.v },
            )
        }
        testing = false
    }

    AdaptivePane(
        first = {
            NeedConnection(state)
            if (narrow.isEmpty() && wide.isEmpty()) Note("La ECU no reporta sensores de oxígeno por los PIDs estándar.")
            narrow.forEach { k ->
                val v = state.live[k]
                val series = state.series[k].orEmpty().map { it.v }
                Section(Pids.info(k).label + " (banda angosta)") {
                    KeyValue("Voltaje", f1(v, "V"))
                    if (series.size > 3) KeyValue("Oscilación reciente", f1(series.max() - series.min(), "V"))
                    CenteredBar(((v ?: 0.45) - 0.45) * 60.0, 30.0)
                }
            }
            wide.forEach { k ->
                Section(Pids.info(k).label + " (banda ancha)") {
                    KeyValue("Lambda", String.format(Locale.US, "%.3f", state.live[k] ?: 0.0).takeIf { state.live[k] != null } ?: "--")
                    Note("1.000 es la mezcla ideal; menos de 1 es rica y más de 1 es pobre.")
                }
            }
            if (cats.isNotEmpty()) {
                Section("Temperatura del catalizador") {
                    cats.forEach { KeyValue(Pids.info(it).label, f1(state.live[it], "°C")) }
                }
            }
        },
        second = {
            Section("Chequeo estimado del catalizador") {
                Note("Con el motor caliente, mantén unas 2500 RPM estables durante 30 s. El sensor posterior debe verse casi plano mientras el anterior oscila. Si el posterior oscila casi igual, el catalizador pierde eficiencia (relacionado con P0420). Es una estimación.")
                if ("0114" !in state.supported && state.supported.isNotEmpty()) {
                    Note("Este auto no reporta el sensor anterior de banda angosta (0114): puede ser de banda ancha y el chequeo no aplica.", Palette.Amber)
                }
                Button(onClick = { result = null; testing = true }, enabled = isLive(state) && !testing) {
                    Text(if (testing) "Midiendo 30 s… RPM ${state.live["010C"]?.toInt() ?: "--"}" else "Iniciar chequeo")
                }
                result?.let {
                    Text(
                        it.text,
                        color = when (it.status) {
                            CatalystStatus.Ok -> Palette.Green
                            CatalystStatus.Weak -> Palette.Red
                            CatalystStatus.Inconclusive -> Palette.Amber
                        },
                        fontSize = 14.sp, lineHeight = 19.sp,
                    )
                }
            }
            Note("La eficiencia exacta del catalizador viene del modo 06 (pruebas internas de los monitores). Queda para más adelante porque cada fabricante lo codifica distinto.")
        },
    )
}

// ============================================================================ MAP, MAF Y BOOST

@Composable
fun AirScreen(state: UiState, vm: ObdViewModel) {
    WatchEffect(vm, setOf("010B", "0110", "0133", "010C", "010F"), false)
    val live = state.live
    val map = live["010B"]
    val maf = live["0110"]
    val baro = live["0133"]
    val rpm = live["010C"] ?: state.data.rpm?.toDouble()

    AdaptivePane(
        first = {
            NeedConnection(state)
            Section("Presiones") {
                KeyValue("MAP (múltiple)", f1(map, "kPa"))
                KeyValue("Presión barométrica", f1(baro, "kPa"))
                KeyValue("MAF (flujo de aire)", f1(maf, "g/s"))
            }
            if (map != null && baro != null) {
                if (map > baro + 5) {
                    val boost = map - baro
                    Section("Boost (turbo)") {
                        KeyValue("Presión", f1(boost, "kPa"))
                        KeyValue("En psi", f1(boost * 0.145038, "psi"))
                    }
                } else {
                    val vac = baro - map
                    val inHg = vac * 0.2953
                    Section("Vacío del múltiple") {
                        KeyValue("Vacío", f1(vac, "kPa"))
                        KeyValue("En inHg", f1(inHg, "inHg"))
                        if (rpm != null && rpm in 400.0..1200.0) {
                            Text(
                                when {
                                    inHg < 17 -> "En ralentí un motor sano marca 17 a 22 inHg: este vacío es bajo, puede haber una fuga o el encendido está retrasado."
                                    inHg > 22 -> "Vacío por encima de lo habitual en ralentí. Revisa que el ralentí sea estable."
                                    else -> "Vacío normal en ralentí (17 a 22 inHg)."
                                },
                                color = if (inHg in 17.0..22.0) Palette.Green else Palette.Amber, fontSize = 14.sp, lineHeight = 19.sp,
                            )
                        }
                    }
                }
            }
        },
        second = {
            val calc = if (map != null && rpm != null) {
                FuelModel.airflowGs(
                    ObdData(rpm = rpm.toInt(), map = map.toInt(), intake = (live["010F"] ?: state.data.intake?.toDouble() ?: 30.0).toInt()),
                    state.profile.displacementL,
                )
            } else {
                null
            }
            Section("MAF medido contra calculado") {
                KeyValue("MAF medido", f1(maf, "g/s"))
                KeyValue("Calculado (MAP y RPM)", f1(calc, "g/s"))
                if (maf != null && calc != null && calc > 1.0) {
                    val ratio = maf / calc
                    Text(
                        when {
                            ratio < 0.8 -> "El MAF mide menos de lo calculado: puede estar sucio (estimación)."
                            ratio > 1.25 -> "El MAF mide más de lo calculado: revisa el MAP o fugas (estimación)."
                            else -> "El MAF y el cálculo coinciden razonablemente."
                        },
                        color = if (ratio in 0.8..1.25) Palette.Green else Palette.Amber, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                }
                Note("El cálculo usa la cilindrada del perfil (${state.profile.displacementL} L) y una eficiencia volumétrica supuesta; sirve para comparar, no como medida exacta.")
            }
        },
    )
}

fun shareCsv(context: Context, csv: String) = shareText(context, "Sesión OBD (CSV)", csv)
