package com.rachav.obddash.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rachav.obddash.data.AlertAction
import com.rachav.obddash.data.AlertOp
import com.rachav.obddash.data.AlertRule
import com.rachav.obddash.obd.Pids
import java.util.Locale

private val KEY_CHOICES = listOf("0105", "VOLT", "FUEL", "010C", "TRIM", "010F", "0104", "010B")

private fun keyLabel(k: String) = when (k) {
    "VOLT" -> "Voltaje (V)"
    "FUEL" -> "Combustible (%)"
    "TRIM" -> "Ajuste de combustible total (%)"
    else -> Pids.info(k).label + " (" + Pids.info(k).unit + ")"
}

private fun summary(r: AlertRule): String {
    val th = if (r.threshold % 1.0 == 0.0) r.threshold.toInt().toString() else String.format(Locale.US, "%.1f", r.threshold)
    return "${keyLabel(r.key)} ${r.op.label} $th · ${r.minSeconds} s · pausa ${r.pauseSeconds} s"
}

@Composable
fun AlertsScreen(state: UiState, vm: ObdViewModel, onRequestNotifications: () -> Unit) {
    var editing by remember { mutableStateOf<AlertRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val rules = state.alertRules

    fun replace(r: AlertRule) = vm.saveAlertRules(rules.map { if (it.id == r.id) r else it })

    ScrollPage {
        Note(
            "Cada regla vigila una variable: si la condición dura más que la duración mínima, se avisa (y no se repite hasta pasar la pausa). " +
                "Las alertas son una ayuda, no un sistema de seguridad.",
        )
        rules.forEach { r ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.CardBottom).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = if (r.critical) Palette.Red else Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(summary(r), color = Palette.TextDim, fontSize = 12.sp)
                        Text(r.actions.joinToString(", ") { it.label }, color = Palette.TextDim, fontSize = 12.sp)
                    }
                    Switch(checked = r.enabled, onCheckedChange = { replace(r.copy(enabled = it)) })
                }
                Row {
                    TextButton(onClick = { editing = r }) { Text("Editar") }
                    TextButton(onClick = { vm.saveAlertRules(rules.filter { it.id != r.id }) }) { Text("Quitar") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("Nueva regla") }
            OutlinedButton(onClick = vm::resetAlertRules) { Text("Reglas iniciales") }
        }

        Section("Con la app cerrada") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alertas en segundo plano", color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.backgroundAlerts,
                    onCheckedChange = {
                        vm.setBackgroundAlerts(it)
                        if (it && Build.VERSION.SDK_INT >= 33) onRequestNotifications()
                    },
                )
            }
            Note("Usa un servicio en primer plano con una notificación permanente. Android 13 pide permiso de notificaciones y Android 14 el tipo de servicio de dispositivo conectado. Las pantallas de auto suelen cerrar servicios en segundo plano: excluye la app del ahorro de batería.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Iniciar al encender el sistema", color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.autostart, onCheckedChange = vm::setAutostart)
            }
            Note("Para la pantalla del auto. Algunos sistemas Android de auto bloquean este permiso; entonces lo resuelve mejor el lanzador de la pantalla.")
        }
    }

    editing?.let { r ->
        RuleDialog(r, isNew = false, onDismiss = { editing = null }, onSave = { replace(it); editing = null })
    }
    if (creating) {
        RuleDialog(
            AlertRule(name = "Nueva alerta", key = "0105", op = AlertOp.Above, threshold = 100.0),
            isNew = true,
            onDismiss = { creating = false },
            onSave = { vm.saveAlertRules(rules + it); creating = false },
        )
    }
}

@Composable
private fun RuleDialog(initial: AlertRule, isNew: Boolean, onDismiss: () -> Unit, onSave: (AlertRule) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var key by remember { mutableStateOf(initial.key) }
    var op by remember { mutableStateOf(initial.op) }
    var threshold by remember { mutableStateOf(initial.threshold.toString()) }
    var minS by remember { mutableStateOf(initial.minSeconds.toString()) }
    var pauseS by remember { mutableStateOf(initial.pauseSeconds.toString()) }
    var actions by remember { mutableStateOf(initial.actions) }
    var critical by remember { mutableStateOf(initial.critical) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nueva regla" else "Editar regla") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Nombre", name) { name = it }
                if (isNew) {
                    Text("Variable", fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KEY_CHOICES.forEach { k -> Chip(keyLabel(k), key == k, { key = k }) }
                    }
                }
                Text("Condición", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlertOp.values().forEach { o -> Chip(o.label, op == o, { op = o }) }
                }
                Field("Umbral", threshold, number = true) { threshold = it }
                Field("Duración mínima (s)", minS, number = true) { minS = it }
                Field("Pausa entre avisos (s)", pauseS, number = true) { pauseS = it }
                Text("Acciones", fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlertAction.values().forEach { a ->
                        Chip(a.label, a in actions, { actions = if (a in actions) actions - a else actions + a })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Crítica", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = critical, onCheckedChange = { critical = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && num(threshold) != null && actions.isNotEmpty(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(), key = key, op = op,
                            threshold = num(threshold) ?: initial.threshold,
                            minSeconds = minS.trim().toIntOrNull()?.coerceIn(0, 600) ?: initial.minSeconds,
                            pauseSeconds = pauseS.trim().toIntOrNull()?.coerceIn(0, 3600) ?: initial.pauseSeconds,
                            actions = actions, critical = critical,
                            needsEngineOn = initial.needsEngineOn || (isNew && key == "VOLT"),
                        ),
                    )
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
