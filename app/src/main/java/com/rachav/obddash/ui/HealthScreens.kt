package com.rachav.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rachav.obddash.obd.BatteryEval
import com.rachav.obddash.obd.BatteryReadings
import com.rachav.obddash.obd.MonState
import com.rachav.obddash.obd.Monitors
import com.rachav.obddash.obd.Verdict
import kotlinx.coroutines.delay
import java.util.Locale

private enum class BStep { Intro, Rest, Crank, Idle, High, Result }

private fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.Pass -> Palette.Green
    Verdict.Warn -> Palette.Amber
    Verdict.Fail -> Palette.Red
}

private fun verdictText(v: Verdict) = when (v) {
    Verdict.Pass -> "APROBADO"
    Verdict.Warn -> "ADVERTENCIA"
    Verdict.Fail -> "FALLA"
}

private fun v1(v: Double?) = if (v == null) "--" else String.format(Locale.US, "%.1f V", v)

// ============================================================================ BATERÍA Y ALTERNADOR

@Composable
fun BatteryScreen(state: UiState, vm: ObdViewModel) {
    var step by remember { mutableStateOf(BStep.Intro) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var readings by remember { mutableStateOf(BatteryReadings()) }
    var saved by remember { mutableStateOf(false) }

    val live = state.conn == ConnState.Connected || state.conn == ConnState.Demo
    val stopped = (state.data.speed ?: 0) == 0
    // Mientras corre un paso, solo se consultan voltaje y RPM para muestrear más rápido.
    WatchEffect(vm, setOf("VOLT", "0142", "010C"), running)

    suspend fun collect(ms: Long, pick: (UiState) -> Double?, cond: (UiState) -> Boolean = { true }): List<Double> {
        val out = mutableListOf<Double>()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val s = vm.ui.value
            if (cond(s)) pick(s)?.let { out.add(it) }
            delay(150)
        }
        return out
    }

    /** Espera hasta que las RPM estén en el rango (o se acabe el tiempo). */
    suspend fun waitRpm(lo: Double, hi: Double, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = vm.ui.value.live["010C"]
            if (r != null && r in lo..hi) return true
            delay(150)
        }
        return false
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        vm.clearSeries()
        when (step) {
            BStep.Rest -> {
                status = "Midiendo el voltaje en reposo…"
                val vals = collect(4000, { it.live["VOLT"] })
                readings = readings.copy(rest = vals.takeIf { it.isNotEmpty() }?.average())
                step = BStep.Crank
            }
            BStep.Crank -> {
                status = "Gira la llave y arranca el motor…"
                var min = Double.MAX_VALUE
                var seen = false
                var engineSince = 0L
                val end = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < end) {
                    val s = vm.ui.value
                    s.live["VOLT"]?.let { if (it < min) min = it }
                    val rpm = s.live["010C"]
                    if (rpm != null && rpm >= 500) {
                        if (engineSince == 0L) engineSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - engineSince > 2000) {
                            seen = true
                            break
                        }
                    }
                    delay(100)
                }
                readings = readings.copy(crankMin = min.takeIf { it != Double.MAX_VALUE })
                if (!seen) status = "No se detectó el arranque; se guardó el mínimo medido."
                step = BStep.Idle
            }
            BStep.Idle -> {
                status = "Esperando el ralentí…"
                if (waitRpm(450.0, 1100.0, 25_000)) {
                    status = "Estabilizando…"
                    delay(5000)
                    status = "Midiendo la carga en ralentí…"
                    val v = collect(8000, { it.live["VOLT"] })
                    val e = collect(1500, { it.live["0142"] })
                    readings = readings.copy(idle = v.takeIf { it.isNotEmpty() }?.average(), idleEcu = e.takeIf { it.isNotEmpty() }?.average())
                    step = BStep.High
                } else {
                    status = "No se detectó el motor en ralentí. Revisa que esté encendido."
                }
            }
            BStep.High -> {
                status = "Sube a unas 2500 RPM y mantenlas…"
                if (waitRpm(2000.0, 3200.0, 40_000)) {
                    delay(4000)
                    status = "Midiendo la carga a 2500 RPM…"
                    val v = collect(8000, { it.live["VOLT"] }, { (it.live["010C"] ?: 0.0) in 1800.0..3400.0 })
                    readings = readings.copy(high = v.takeIf { it.isNotEmpty() }?.average(), highRange = v.takeIf { it.size > 3 }?.let { it.max() - it.min() })
                    step = BStep.Result
                } else {
                    status = "No se alcanzaron las 2500 RPM."
                }
            }
            else -> Unit
        }
        running = false
    }

    AdaptivePane(
        first = {
            if (!live) Note("Conéctate al adaptador (o usa el modo demo) para hacer la prueba.", Palette.Amber)
            if (live && !stopped) Note("Detén el auto para hacer la prueba.", Palette.Amber)
            Section("Prueba guiada · paso ${step.ordinal.coerceAtLeast(1).coerceAtMost(5)} de 5") {
                Text(
                    when (step) {
                        BStep.Intro -> "Tres mediciones: reposo (motor apagado), arranque y carga del alternador en ralentí y a 2500 RPM. Termina con aprobado, advertencia o falla."
                        BStep.Rest -> "Apaga el motor y deja el contacto en ON. Si acabas de manejar, espera un minuto para que el voltaje se asiente."
                        BStep.Crank -> "Pulsa el botón y enseguida gira la llave para arrancar."
                        BStep.Idle -> "Con el motor en ralentí, enciende luces y ventilador al máximo. Así se evita que la carga inteligente del alternador falsee el resultado."
                        BStep.High -> "Acelera suave y mantén unas 2500 RPM hasta que termine la medición."
                        BStep.Result -> "Prueba terminada."
                    },
                    color = Palette.Text, fontSize = 15.sp, lineHeight = 21.sp,
                )
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (status.isNotEmpty()) Note(status, Palette.Cyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canRun = live && stopped && !running
                    when (step) {
                        BStep.Intro -> Button(onClick = { readings = BatteryReadings(); saved = false; status = ""; step = BStep.Rest }, enabled = live && stopped) { Text("Comenzar") }
                        BStep.Result -> {
                            Button(onClick = { vm.saveBattery(readings); saved = true }, enabled = !saved) { Text(if (saved) "Guardado" else "Guardar resultado") }
                            OutlinedButton(onClick = { step = BStep.Intro; status = "" }) { Text("Repetir") }
                        }
                        else -> {
                            Button(onClick = { status = ""; running = true }, enabled = canRun) {
                                Text(
                                    when (step) {
                                        BStep.Rest -> "Medir reposo"
                                        BStep.Crank -> "Medir arranque"
                                        BStep.Idle -> "Medir ralentí"
                                        else -> "Medir 2500 RPM"
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = { status = ""; step = if (step == BStep.High) BStep.Result else BStep.values()[step.ordinal + 1] },
                                enabled = !running,
                            ) { Text("Omitir") }
                        }
                    }
                }
            }
            Note("El adaptador mide el voltaje con precisión modesta (en clones puede errar 0.2 V o más) y con poca frecuencia: el mínimo del arranque puede quedar subestimado y el adaptador puede reiniciarse con la caída de voltaje. No detecta rizado por diodos dañados del alternador; eso pide un multímetro con medición de CA o un osciloscopio.")
        },
        second = {
            Section("Mediciones") {
                KeyValue("Reposo", v1(readings.rest))
                KeyValue("Arranque (mínimo)", v1(readings.crankMin))
                KeyValue("Ralentí", v1(readings.idle) + (readings.idleEcu?.let { " (ECU ${v1(it)})" } ?: ""))
                KeyValue("2500 RPM", v1(readings.high) + (readings.highRange?.let { " ±" + String.format(Locale.US, "%.2f", it / 2) } ?: ""))
            }
            if (step == BStep.Result) {
                val ev = BatteryEval.evaluate(readings)
                Section("Resultado") {
                    Text(verdictText(ev.verdict), color = verdictColor(ev.verdict), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    ev.notes.forEach { Text("• $it", color = Palette.Text, fontSize = 14.sp, lineHeight = 19.sp) }
                }
            }
            if (state.batteryHistory.isNotEmpty()) {
                Section("Historial de ${state.profile.name}") {
                    state.batteryHistory.take(8).forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtDay(r.date) + "  reposo " + v1(r.readings.rest), color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(verdictText(r.verdict), color = verdictColor(r.verdict), fontSize = 12.sp)
                        }
                    }
                    Note("Mira cómo cae el voltaje en reposo a lo largo de los meses.")
                }
            }
        },
    )
}

// ============================================================================ MONITORES I/M

@Composable
fun MonitorsScreen(state: UiState, vm: ObdViewModel) {
    val live = state.conn == ConnState.Connected || state.conn == ConnState.Demo
    LaunchedEffect(live) { if (live && state.monitors.isEmpty()) vm.loadMonitors() }

    val ready = Monitors.inspectionReady(state.monitors, state.imTolerance)
    val pending = state.monitors.count { it.sinceClear == MonState.NotReady }

    AdaptivePane(
        first = {
            if (!live) Note("Conéctate al adaptador (o usa el modo demo) para leer los monitores.", Palette.Amber)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::loadMonitors, enabled = live && !state.monitorsBusy) { Text("Actualizar") }
                if (state.monitorsBusy) LinearProgressIndicator(Modifier.weight(1f))
            }
            if (state.monitors.isNotEmpty()) {
                Section(if (ready) "Listo para inspección" else "Aún no está listo") {
                    Text(
                        if (ready) "Hay $pending monitor(es) sin completar, dentro de la tolerancia (${state.imTolerance})."
                        else "Hay $pending monitor(es) sin completar; la tolerancia es ${state.imTolerance}.",
                        color = if (ready) Palette.Green else Palette.Amber, fontSize = 15.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tolerancia:", color = Palette.TextDim, fontSize = 13.sp)
                        OutlinedButton(onClick = { vm.setImTolerance(state.imTolerance - 1) }) { Text("−") }
                        Text("${state.imTolerance}", color = Palette.Text, fontSize = 16.sp)
                        OutlinedButton(onClick = { vm.setImTolerance(state.imTolerance + 1) }) { Text("+") }
                    }
                    Note("En EE. UU. se permite un monitor sin completar en autos 2001 y posteriores. Las reglas cambian por país, por eso es un ajuste.")
                }
            }
            Note("Borrar códigos reinicia los monitores. El diálogo de borrado lo advierte y guarda antes el cuadro congelado.")
        },
        second = {
            state.monitors.forEach { m ->
                val (txt, col) = when (m.sinceClear) {
                    MonState.Ready -> "Listo" to Palette.Green
                    MonState.NotReady -> "No listo" to Palette.Amber
                    MonState.NotSupported -> "No soportado" to Palette.TextDim
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.CardBottom).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(txt, color = col, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (m.thisCycle != MonState.NotSupported) {
                        Text("Este ciclo de manejo: " + if (m.thisCycle == MonState.Ready) "listo" else "no listo", color = Palette.TextDim, fontSize = 12.sp)
                    }
                    if (m.sinceClear == MonState.NotReady && m.hint.isNotBlank()) {
                        Text("Cómo completarlo: ${m.hint}", color = Palette.Cyan, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        },
    )
}
