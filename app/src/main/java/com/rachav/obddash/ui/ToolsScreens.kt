package com.rachav.obddash.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rachav.obddash.data.VehicleProfile
import com.rachav.obddash.obd.CompatReport
import com.rachav.obddash.obd.PidCatalog
import com.rachav.obddash.obd.toText
import java.util.Locale

/** Abre el menú de compartir de Android con un texto (WhatsApp, correo, notas...). */
fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title))
}

// ============================================================================ COMPATIBILIDAD

@Composable
fun CompatScreen(state: UiState, onRun: () -> Unit) {
    val context = LocalContext.current
    val connected = state.conn == ConnState.Connected || state.conn == ConnState.Demo
    val report = state.compat

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Revisa qué servicios y datos responde tu auto con este adaptador. " +
                "El resultado se puede compartir para ajustar la app a lo que tu ECU realmente reporta. " +
                "Hazla con el motor en ralentí y el auto detenido.",
            color = Palette.TextDim,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRun, enabled = connected && !state.compatBusy) { Text("Ejecutar prueba") }
            if (report != null) {
                OutlinedButton(onClick = {
                    shareText(context, "Compatibilidad OBD", report.toText(state.profile.name))
                }) { Text("Compartir") }
            }
        }
        if (!connected) {
            Text("Conéctate al adaptador (o usa el modo demo) para correr la prueba.", color = Palette.TextDim, fontSize = 14.sp)
        }
        if (state.compatBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Consultando la ECU… puede tardar unos segundos.", color = Palette.TextDim, fontSize = 13.sp)
        }
        if (report != null) CompatResult(report)
    }
}

@Composable
private fun CompatResult(r: CompatReport) {
    if (r.demo) Text("Datos de ejemplo del modo demo.", color = Palette.Amber, fontSize = 13.sp)

    Section("Conexión") {
        KeyValue("Adaptador", r.adapter ?: "--")
        KeyValue("Protocolo", r.protocol ?: "--")
        KeyValue("ECU", r.ecuId ?: "--")
        KeyValue("VIN", r.vin ?: "No lo entrega")
        KeyValue("Voltaje (adaptador)", r.voltageAdapter?.let { String.format(Locale.US, "%.1f V", it) } ?: "--")
        KeyValue("Voltaje (ECU, 0142)", r.voltagePid?.let { String.format(Locale.US, "%.1f V", it) } ?: "No soportado")
    }

    Section("Servicios OBD") {
        r.modes.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${m.mode}  ${m.name}", color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    if (m.supported) "Sí" else "No",
                    color = if (m.supported) Palette.Green else Palette.TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    Section("PIDs de modo 01 (${r.supportedPids.size})") {
        if (r.supportedPids.isEmpty()) {
            Text("La ECU no reportó PIDs.", color = Palette.TextDim, fontSize = 14.sp)
        }
        r.supportedPids.forEach { pid ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pid, color = Palette.Cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text(PidCatalog.name(pid), color = Palette.Text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.CardBottom)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ============================================================================ PERFILES

@Composable
fun ProfilesScreen(
    state: UiState,
    onSelect: (String) -> Unit,
    onSave: (VehicleProfile) -> Unit,
    onDelete: (String) -> Unit,
    onExport: () -> String,
    onImport: (String) -> Int?,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<VehicleProfile?>(null) }
    var deleting by remember { mutableStateOf<VehicleProfile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Cada vehículo guarda su cilindrada, adaptador, VIN y precio de combustible. " +
                "La app conecta sola con el adaptador del perfil activo.",
            color = Palette.TextDim,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

        state.profiles.forEach { p ->
            val active = p.id == state.profile.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Palette.Surface2 else Palette.CardBottom)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (active) Text("ACTIVO", color = Palette.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                val detail = listOfNotNull(
                    p.brand.takeIf { it.isNotBlank() },
                    p.model.takeIf { it.isNotBlank() },
                    p.year?.toString(),
                    String.format(Locale.US, "%.1f L", p.displacementL),
                ).joinToString(" · ")
                Text(detail, color = Palette.TextDim, fontSize = 13.sp)
                Text(
                    "Adaptador: " + (p.adapterName ?: "sin asignar") + "   VIN: " + (p.vin ?: "--"),
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!active) TextButton(onClick = { onSelect(p.id) }) { Text("Usar") }
                    TextButton(onClick = { editing = p }) { Text("Editar") }
                    if (state.profiles.size > 1) TextButton(onClick = { deleting = p }) { Text("Borrar") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = VehicleProfile(name = "Nuevo auto") }) { Text("Nuevo perfil") }
            OutlinedButton(onClick = { shareText(context, "Perfiles OBD Dash", onExport()) }) { Text("Exportar") }
            OutlinedButton(onClick = { importing = true }) { Text("Importar") }
        }
        status?.let { Text(it, color = Palette.Cyan, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
    }

    editing?.let { p ->
        ProfileEditDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar \"${p.name}\"?") },
            text = { Text("Se quita el perfil de este teléfono. Si lo exportaste antes, puedes volver a importarlo.") },
            confirmButton = { TextButton(onClick = { onDelete(p.id); deleting = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Importar perfiles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pega aquí el texto que exportaste antes.", fontSize = 14.sp)
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = onImport(text)
                    status = if (n == null) "El texto no es un respaldo de perfiles válido." else "Se importaron $n perfil(es)."
                    importing = false
                }) { Text("Importar") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ProfileEditDialog(initial: VehicleProfile, onDismiss: () -> Unit, onSave: (VehicleProfile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var brand by remember { mutableStateOf(initial.brand) }
    var model by remember { mutableStateOf(initial.model) }
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var disp by remember { mutableStateOf(String.format(Locale.US, "%.1f", initial.displacementL)) }
    var tank by remember { mutableStateOf(initial.tankL?.toString().orEmpty()) }
    var price by remember { mutableStateOf(initial.pricePerL?.toString().orEmpty()) }
    var redline by remember { mutableStateOf(initial.redlineRpm.toString()) }

    fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perfil de vehículo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Nombre (se ve en el panel)", name) { name = it }
                Field("Marca", brand) { brand = it }
                Field("Modelo", model) { model = it }
                Field("Año", year, number = true) { year = it }
                Field("Cilindrada (L)", disp, number = true) { disp = it }
                Field("Tanque (L)", tank, number = true) { tank = it }
                Field("Precio por litro", price, number = true) { price = it }
                Field("Línea roja (RPM)", redline, number = true) { redline = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            brand = brand.trim(),
                            model = model.trim(),
                            year = year.trim().toIntOrNull(),
                            displacementL = num(disp)?.takeIf { it in 0.3..10.0 } ?: initial.displacementL,
                            tankL = num(tank)?.takeIf { it > 0 },
                            pricePerL = num(price)?.takeIf { it > 0 },
                            redlineRpm = redline.trim().toIntOrNull()?.takeIf { it in 2000..12000 } ?: initial.redlineRpm,
                        ),
                    )
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
