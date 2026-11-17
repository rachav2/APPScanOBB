package com.rachav.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rachav.obddash.data.DtcRecord
import com.rachav.obddash.obd.FreezeFrame
import com.rachav.obddash.obd.Pids
import java.util.Locale

private enum class DtcTab(val label: String) { Fallas("Fallas"), Historial("Historial"), Modulos("Módulos") }

private fun severityColor(sev: Int) = when (sev) {
    3 -> Palette.Red
    2 -> Palette.Amber
    else -> Palette.Cyan
}

private fun severityText(sev: Int) = when (sev) {
    3 -> "Grave"
    2 -> "Atención"
    else -> "Informativo"
}

@Composable
fun DtcScreen(state: UiState, vm: ObdViewModel, onOpenRelated: (String) -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var sub by rememberSaveable { mutableStateOf(DtcTab.Fallas) }
    var filter by rememberSaveable { mutableStateOf("*") }
    val connected = state.conn == ConnState.Connected || state.conn == ConnState.Demo
    // Borrar códigos solo con el auto detenido (evita distraer al conductor).
    val stopped = (state.data.speed ?: 0) == 0
    val count = state.dtcs.size
    val iconColor = when {
        !state.dtcsRead -> Palette.TextDim
        count == 0 -> Palette.Green
        else -> Palette.Red
    }
    val headline = when {
        !state.dtcsRead -> "Sin lectura"
        count == 0 -> "Sin códigos de error"
        count == 1 -> "1 Código de Error"
        else -> "$count Códigos de Error"
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ObdIcon(if (state.dtcsRead && count == 0) Ico.Check else Ico.Warning, Modifier.size(56.dp), iconColor)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(headline, color = iconColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::readDtcs, enabled = connected && !state.dtcBusy) { Text("Leer códigos") }
                    OutlinedButton(
                        onClick = { confirm = true },
                        enabled = connected && !state.dtcBusy && count > 0 && stopped,
                    ) { Text("Borrar DTC") }
                }
            }
        }
        if (state.dtcBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        if (connected && !stopped && count > 0) {
            Text("Para borrar códigos el auto debe estar detenido.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DtcTab.values().forEach { t -> Chip(t.label, sub == t, { sub = t }) }
        }

        when (sub) {
            DtcTab.Fallas -> FallasPane(state, vm, filter, { filter = it }, connected, onOpenRelated)
            DtcTab.Historial -> HistorialPane(state, vm)
            DtcTab.Modulos -> ModulosPane(state, vm, connected, stopped)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar códigos de falla?") },
            text = {
                Text(
                    "Se apagará la luz de check engine y la ECU perderá el cuadro congelado, " +
                        "pero la app lo guarda antes en el historial. También se reinician los monitores " +
                        "de emisiones: tardarán varios ciclos de manejo en volver a estar listos. " +
                        "Si la falla persiste, el código volverá a aparecer.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.clearDtcs() }) { Text("Guardar cuadro y borrar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

// ============================================================================ FALLAS

@Composable
private fun FallasPane(
    state: UiState,
    vm: ObdViewModel,
    filter: String,
    onFilter: (String) -> Unit,
    connected: Boolean,
    onOpenRelated: (String) -> Unit,
) {
    val perm = state.permanent.map { com.rachav.obddash.obd.DtcDatabase.lookup(it) }
    val counts = mapOf(
        "P" to state.dtcs.count { it.info.code.startsWith("P") },
        "C" to state.dtcs.count { it.info.code.startsWith("C") },
        "B" to state.dtcs.count { it.info.code.startsWith("B") },
        "U" to state.dtcs.count { it.info.code.startsWith("U") },
    )
    val shown = if (filter == "*") state.dtcs else state.dtcs.filter { it.info.code.startsWith(filter) }

    AdaptivePane(
        first = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Todos ${state.dtcs.size}", filter == "*", { onFilter("*") })
                Chip("Motor (P) ${counts["P"]}", filter == "P", { onFilter("P") })
                Chip("Chasis (C) ${counts["C"]}", filter == "C", { onFilter("C") })
                Chip("Carrocería (B) ${counts["B"]}", filter == "B", { onFilter("B") })
                Chip("Red (U) ${counts["U"]}", filter == "U", { onFilter("U") })
            }
            when {
                !connected -> Note("Conéctate al adaptador (o usa el modo demo) para leer los códigos de falla.")
                !state.dtcsRead -> Note("Pulsa \"Leer códigos\" para consultar la ECU.")
                shown.isEmpty() -> Note(if (state.dtcs.isEmpty()) "La ECU no reporta fallas almacenadas ni pendientes." else "Ningún código en esta ficha.")
                else -> shown.forEach { DtcCard(it, onOpenRelated) }
            }
            if (state.dtcsRead && filter != "*" && filter != "P") {
                Note("El modo estándar solo devuelve códigos del motor (P) y algunos de red (U). Los de chasis y carrocería piden la exploración de módulos (experimental).")
            }
            if (perm.isNotEmpty()) {
                Section("Códigos permanentes (no se borran con un escáner)") {
                    perm.forEach { Text("${it.code}  ${it.description}", color = Palette.Text, fontSize = 14.sp) }
                }
            }
        },
        second = {
            state.freeze?.let { FreezeCard(it) }
            MonitorSummary(state)
        },
    )
}

@Composable
private fun MonitorSummary(state: UiState) {
    val total = state.monitorsTotal
    if (!state.dtcsRead || total == null) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.CardBottom).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val allReady = state.monitorsReady == total
        ObdIcon(Ico.Check, Modifier.size(26.dp), if (allReady) Palette.Green else Palette.Amber)
        Text("Monitores: ${state.monitorsReady ?: 0} / $total", color = Palette.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
        state.mil?.let { on ->
            Text(
                if (on) "Check engine: ENCENDIDA" else "Check engine: apagada",
                color = if (on) Palette.Amber else Palette.Green,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun DtcCard(entry: DtcEntry, onOpenRelated: (String) -> Unit) {
    val info = entry.info
    var open by rememberSaveable(info.code) { mutableStateOf(false) }
    val accent = if (entry.kind == DtcKind.Stored) Palette.Red else Palette.Amber
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Palette.CardTop, Palette.CardBottom)))
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.Blue, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(info.code, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (entry.kind == DtcKind.Stored) "ALMACENADO" else "PENDIENTE",
                    color = accent,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(info.description, color = Palette.Text, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    info.system + " · " + if (info.generic) "Genérico" else "Fabricante",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                )
                if (info.detailed) {
                    Text("${severityText(info.severity)} · ${info.drivable}", color = severityColor(info.severity), fontSize = 12.sp)
                }
            }
        }
        if (open) {
            if (!info.detailed) {
                Note("Este código todavía no tiene una guía propia. Lo común es revisar el sensor o circuito indicado, sus conectores y cableado, y buscar \"${info.code}\" con la marca y el modelo.")
            }
            BulletBlock("Síntomas típicos", info.symptoms)
            BulletBlock("Causas probables (de la más a la menos común)", info.causes)
            BulletBlock("Qué hacer (de lo más fácil y barato a lo más caro)", info.fixes)
            info.related?.let { r ->
                OutlinedButton(onClick = { onOpenRelated(r) }) {
                    Text(
                        when (r) {
                            "trims" -> "Ver fuel trims"
                            "o2" -> "Ver sensores de oxígeno"
                            "map" -> "Ver MAP / MAF"
                            else -> "Probar batería y alternador"
                        },
                    )
                }
            }
            Note("Guía orientativa: no sustituye un diagnóstico de taller.")
        } else {
            Text("Toca para ver causas y soluciones", color = Palette.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BulletBlock(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Palette.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        items.forEach { Text("• $it", color = Palette.Text, fontSize = 14.sp, lineHeight = 19.sp) }
    }
}

@Composable
internal fun FreezeCard(ff: FreezeFrame) {
    Section("Cuadro congelado · disparado por ${ff.code}") {
        ff.values.forEach { (k, v) ->
            val info = Pids.info(k)
            KeyValue(info.label, String.format(Locale.US, if (k == "010C" || k == "010D" || k == "010B") "%.0f %s" else "%.1f %s", v, info.unit))
        }
        Note("Es lo que midió la ECU en el momento de la falla.")
    }
}

// ============================================================================ HISTORIAL

@Composable
private fun HistorialPane(state: UiState, vm: ObdViewModel) {
    var editing by remember { mutableStateOf<DtcRecord?>(null) }
    ScrollPage {
        Note("Cada vez que lees los códigos se anotan aquí. La ECU no entrega la fecha de la falla, así que se usa la fecha de la lectura.")
        if (state.dtcRecords.isEmpty()) Note("Aún no hay historial para ${state.profile.name}.")
        state.dtcRecords.forEach { r ->
            val info = com.rachav.obddash.obd.DtcDatabase.lookup(r.code)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.CardBottom).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.code, color = if (r.repaired) Palette.Green else Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(10.dp))
                    Text(if (r.repaired) "REPARADO" else "ABIERTO", color = if (r.repaired) Palette.Green else Palette.Amber, fontSize = 11.sp)
                }
                Text(info.description, color = Palette.Text, fontSize = 14.sp)
                Text(
                    "Primera lectura ${fmtDateTime(r.firstSeen)} · última ${fmtDateTime(r.lastSeen)} · borrado ${r.timesCleared} ${if (r.timesCleared == 1) "vez" else "veces"}",
                    color = Palette.TextDim, fontSize = 12.sp,
                )
                if (r.note.isNotBlank()) Text("Nota: ${r.note}", color = Palette.Cyan, fontSize = 13.sp)
                r.freeze?.let { f ->
                    Text(
                        "Cuadro congelado: " + f.values.entries.take(4).joinToString(", ") {
                            Pids.info(it.key).label + " " + String.format(Locale.US, "%.0f", it.value)
                        },
                        color = Palette.TextDim, fontSize = 12.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.setDtcRepaired(r.code, !r.repaired) }) { Text(if (r.repaired) "Reabrir" else "Ya reparado") }
                    TextButton(onClick = { editing = r }) { Text("Nota") }
                }
            }
        }
    }
    editing?.let { r ->
        var text by remember(r.code) { mutableStateOf(r.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Nota para ${r.code}") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = { TextButton(onClick = { vm.setDtcNote(r.code, text.trim()); editing = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

// ============================================================================ MÓDULOS (EXPERIMENTAL)

@Composable
private fun ModulosPane(state: UiState, vm: ObdViewModel, connected: Boolean, stopped: Boolean) {
    ScrollPage {
        Note(
            "EXPERIMENTAL. El modo estándar solo habla con el motor. Aquí se prueba preguntar a otros módulos " +
                "(transmisión, ABS, airbag, carrocería) con UDS 0x19 sobre CAN. Las direcciones cambian de marca en marca: " +
                "si un módulo no contesta no es un error de la app. Hazla con el auto detenido y el contacto encendido.",
            Palette.Amber,
        )
        Button(onClick = vm::scanModules, enabled = connected && stopped && !state.modulesBusy) { Text("Explorar módulos") }
        if (state.modulesBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.modules.forEach { m ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.CardBottom).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row {
                    Text("${m.header}  ${m.name}", color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(if (m.responded) "Responde" else "Sin respuesta", color = if (m.responded) Palette.Green else Palette.TextDim, fontSize = 13.sp)
                }
                if (m.responded) {
                    if (m.codes.isEmpty()) Text("Sin códigos", color = Palette.TextDim, fontSize = 13.sp)
                    m.codes.forEach { c ->
                        Text("$c  ${com.rachav.obddash.obd.DtcDatabase.lookup(c).description}", color = Palette.Text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
