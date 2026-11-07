package com.rachav.obddash.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String, val icon: Ico) {
    Panel("Panel", Ico.Dial),
    Temp("Temp.", Ico.Thermo),
    Velocidad("Velocidad", Ico.Gauge),
    Errores("Errores", Ico.Warning),
    Mas("Más", Ico.Grid),
}

@Composable
fun ObdApp(
    vm: ObdViewModel,
    btGranted: Boolean,
    onRequestBt: () -> Unit,
    bondedDevices: () -> List<BluetoothDevice>,
) {
    val state by vm.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Mas) }

    LaunchedEffect(state.conn) {
        if (state.conn == ConnState.Connected || state.conn == ConnState.Demo) tab = Tab.Panel
    }

    val title = when (tab) {
        Tab.Panel -> "OBD Monitor"
        Tab.Temp -> "Temperaturas"
        Tab.Velocidad -> "Velocidad y Viaje"
        Tab.Errores -> "Códigos de Error"
        Tab.Mas -> "Conexión"
    }
    val subtitle = if (tab == Tab.Panel) {
        when (state.conn) {
            ConnState.Connected -> state.adapter ?: "Conectado"
            ConnState.Demo -> "Modo demo"
            ConnState.Connecting -> "Conectando…"
            ConnState.Disconnected -> "Sin conexión"
        }
    } else {
        null
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        TopBar(title = title, subtitle = subtitle, state = state, onMenu = { tab = Tab.Mas })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Panel -> PanelScreen(state)
                Tab.Temp -> TempScreen(state)
                Tab.Velocidad -> SpeedScreen(state, onReset = vm::resetTrip)
                Tab.Errores -> DtcScreen(state, onRead = vm::readDtcs, onClear = vm::clearDtcs)
                Tab.Mas -> ConnectionScreen(
                    state = state,
                    btGranted = btGranted,
                    onRequestBt = onRequestBt,
                    bondedDevices = bondedDevices,
                    onConnect = vm::connect,
                    onDemo = vm::startDemo,
                    onDisconnect = vm::disconnect,
                )
            }
            state.message?.let {
                MessageBanner(it, vm::clearMessage, Modifier.align(Alignment.BottomCenter))
            }
        }
        BottomNav(selected = tab, onSelect = { tab = it }, hasDtc = state.dtcs.isNotEmpty())
    }
}

// ============================================================================ BARRAS

@Composable
private fun TopBar(title: String, subtitle: String?, state: UiState, onMenu: () -> Unit) {
    val clock = rememberClock()
    val live = state.conn == ConnState.Connected || state.conn == ConnState.Demo
    val dot = when (state.conn) {
        ConnState.Connected -> Palette.Green
        ConnState.Demo -> Palette.Amber
        ConnState.Connecting -> Palette.Cyan
        ConnState.Disconnected -> Palette.TextDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Surface, Palette.Bg)))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ObdIcon(Ico.Menu, Modifier.size(28.dp).clickable { onMenu() }, Palette.Text)
        Spacer(Modifier.width(14.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            }
            subtitle?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        }
        Spacer(Modifier.weight(1f))
        ObdIcon(Ico.Bluetooth, Modifier.size(22.dp), if (live) Palette.Text else Palette.TextDim)
        Spacer(Modifier.width(16.dp))
        ObdIcon(Ico.Car, Modifier.size(26.dp), if (live) Palette.Text else Palette.TextDim)
        Spacer(Modifier.width(16.dp))
        Text(clock, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        Text("⋮", color = Palette.Text, fontSize = 22.sp)
    }
}

@Composable
private fun rememberClock(): String {
    var text by remember { mutableStateOf(nowText()) }
    LaunchedEffect(Unit) {
        while (true) {
            text = nowText()
            delay(10_000)
        }
    }
    return text
}

private fun nowText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit, hasDtc: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tab.values().forEach { t ->
            val sel = t == selected
            val fg = when {
                t == Tab.Errores && hasDtc -> Palette.Red
                sel -> Color.White
                else -> Palette.TextDim
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Color(0xFF1E5BD8) else Palette.Surface2.copy(alpha = 0.6f))
                    .clickable { onSelect(t) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ObdIcon(t.icon, Modifier.size(22.dp), fg)
                Text(t.label, color = if (sel) Color.White else Palette.TextDim, fontSize = 12.sp)
            }
        }
    }
}

// ============================================================================ PANEL

@Composable
fun PanelScreen(state: UiState) {
    val d = state.data
    val t = state.trip
    val badge = when (state.conn) {
        ConnState.Connected -> "EN VIVO"
        ConnState.Demo -> "DEMO"
        ConnState.Connecting -> "…"
        ConnState.Disconnected -> "SIN SEÑAL"
    }
    val avgFuel = t.avgConsumption

    val coolant = CardSpec(
        Ico.Thermo, "Temp. Motor", fmt(d.coolant), "°C", prog(d.coolant, 40f, 130f),
        if ((d.coolant ?: 0) > 105) Palette.Red else Palette.Cyan,
    )
    val fuel = CardSpec(
        Ico.Fuel, "Nivel Combustible", fmt(d.fuel), "%", prog(d.fuel, 0f, 100f),
        if ((d.fuel ?: 100) < 12) Palette.Amber else Palette.Cyan,
    )
    val consumption = CardSpec(Ico.Drop, "Consumo Prom.", fmtD(avgFuel, 1), "L/100km")
    val speed = CardSpec(Ico.Gauge, "Velocidad", fmt(d.speed), "km/h", prog(d.speed, 0f, 220f))
    val v = d.voltage
    val battery = CardSpec(
        Ico.Battery, "Voltaje Batería", fmtF(v, 1), "V", prog(v, 9f, 16f),
        if (v != null && (v < 11.8f || v > 15.2f)) Palette.Amber else Palette.Cyan,
    )
    val intake = CardSpec(Ico.Wind, "Temp. Aire Admisión", fmt(d.intake), "°C", prog(d.intake, -10f, 80f))

    val left = listOf(coolant, fuel, consumption)
    val right = listOf(speed, battery, intake)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CardColumn(left, Modifier.weight(0.8f).fillMaxHeight())
                    Box(Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        RpmDial(rpm = d.rpm, badge = badge)
                    }
                    CardColumn(right, Modifier.weight(0.8f).fillMaxHeight())
                }
                TripStrip(state, Modifier.fillMaxWidth())
            }
        } else {
            val all = listOf(coolant, speed, fuel, battery, consumption, intake) + listOf(
                CardSpec(Ico.Road, "Distancia Trip", fmtD(t.distanceKm, 1), "km"),
                CardSpec(Ico.Clock, "Tiempo Trip", t.timeText, "h"),
                CardSpec(Ico.Chip, "Código ECU", state.ecuId ?: "--", ""),
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
                RpmDial(rpm = d.rpm, badge = badge, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                all.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { InfoCard(it, Modifier.weight(1f).height(84.dp)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardColumn(cards: List<CardSpec>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { InfoCard(it, Modifier.weight(1f).fillMaxWidth()) }
    }
}

@Composable
private fun TripStrip(state: UiState, modifier: Modifier = Modifier) {
    val t = state.trip
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(Palette.CardBottom)
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Palette.Blue.copy(alpha = 0.6f), Palette.Blue.copy(alpha = 0.1f))),
                shape,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripItem(Ico.Road, "Distancia Trip", fmtD(t.distanceKm, 1) + " km", Modifier.weight(1f))
        Text(
            text = AppConfig.CAR_NAME,
            style = TextStyle(
                brush = Brush.verticalGradient(listOf(Color.White, Color(0xFF7D8DB0))),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                letterSpacing = 4.sp,
            ),
            modifier = Modifier.weight(1.2f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StripItem(Ico.Clock, "Tiempo Trip", t.timeText + " h", Modifier.weight(0.8f))
        StripItem(Ico.Chip, "Código ECU", state.ecuId ?: "--", Modifier.weight(0.8f))
    }
}

@Composable
private fun StripItem(icon: Ico, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ObdIcon(icon, Modifier.size(30.dp), Palette.Cyan)
        Column {
            Text(label, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1)
            Text(value, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ============================================================================ TEMPERATURAS

private data class TempRow(
    val icon: Ico,
    val label: String,
    val value: Int?,
    val min: Float,
    val max: Float,
    val warnAbove: Int?,
)

@Composable
fun TempScreen(state: UiState) {
    val d = state.data
    val rows = listOf(
        TempRow(Ico.Thermo, "Temp. Motor", d.coolant, 40f, 130f, 105),
        TempRow(Ico.Oil, "Temp. Aceite Motor", d.oil, 40f, 150f, 125),
        TempRow(Ico.Wind, "Temp. Aire Admisión", d.intake, -10f, 80f, 60),
        TempRow(Ico.Drop, "Temp. Ambiente", d.ambient, -10f, 50f, null),
        TempRow(Ico.Exhaust, "Temp. Catalizador", d.catalyst, 200f, 900f, 850),
    )
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { r ->
            val warn = r.value != null && r.warnAbove != null && r.value > r.warnAbove
            val accent = if (warn) Palette.Red else Palette.Cyan
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(Palette.CardTop, Palette.CardBottom)))
                    .border(1.dp, Palette.Blue.copy(alpha = 0.35f), shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ObdIcon(r.icon, Modifier.size(36.dp), accent)
                Text(r.label, color = Palette.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                ProgressBar(prog(r.value, r.min, r.max) ?: 0f, accent, Modifier.weight(1.2f))
                Text(
                    text = if (r.value == null) "--" else "${r.value} °C",
                    color = if (warn) Palette.Red else Palette.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(88.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

// ============================================================================ VELOCIDAD Y VIAJE

@Composable
fun SpeedScreen(state: UiState, onReset: () -> Unit) {
    val t = state.trip
    val cards = listOf(
        CardSpec(Ico.Gauge, "Velocidad Máx.", t.maxSpeed.toString(), "km/h"),
        CardSpec(Ico.Gauge, "Promedio", t.avgSpeed.toString(), "km/h"),
        CardSpec(Ico.Pin, "Distancia Trip", fmtD(t.distanceKm, 1), "km"),
        CardSpec(Ico.Drop, "Consumo Prom.", fmtD(t.avgConsumption, 1), "L/100km"),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    SpeedArc(state.data.speed)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.chunked(2).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { InfoCard(it, Modifier.weight(1f).fillMaxHeight()) }
                        }
                    }
                    TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                        Text("Reiniciar viaje")
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                SpeedArc(state.data.speed, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                cards.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { InfoCard(it, Modifier.weight(1f).height(84.dp)) }
                    }
                }
                TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) { Text("Reiniciar viaje") }
            }
        }
    }
}

// ============================================================================ FALLAS

@Composable
fun DtcScreen(state: UiState, onRead: () -> Unit, onClear: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val connected = state.conn == ConnState.Connected || state.conn == ConnState.Demo
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ObdIcon(
                if (state.dtcsRead && count == 0) Ico.Check else Ico.Warning,
                Modifier.size(72.dp),
                iconColor,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(headline, color = iconColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRead, enabled = connected && !state.dtcBusy) { Text("Leer códigos") }
                    OutlinedButton(
                        onClick = { confirm = true },
                        enabled = connected && !state.dtcBusy && count > 0,
                    ) { Text("Borrar DTC") }
                }
            }
        }
        if (state.dtcBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !connected -> Hint("Conéctate al adaptador (o usa el modo demo) para leer los códigos de falla.")
                !state.dtcsRead -> Hint("Pulsa \"Leer códigos\" para consultar la ECU.")
                count == 0 -> Hint("La ECU no reporta fallas almacenadas ni pendientes.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.dtcs) { DtcCard(it) }
                }
            }
        }

        val total = state.monitorsTotal
        if (state.dtcsRead && total != null) {
            Text("Estado del Sistema", color = Palette.TextDim, fontSize = 14.sp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.CardBottom)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val allReady = state.monitorsReady == total
                ObdIcon(Ico.Check, Modifier.size(26.dp), if (allReady) Palette.Green else Palette.Amber)
                Text(
                    "Monitores: ${state.monitorsReady ?: 0} / $total",
                    color = Palette.Text,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                state.mil?.let { on ->
                    Text(
                        if (on) "Check engine: ENCENDIDA" else "Check engine: apagada",
                        color = if (on) Palette.Amber else Palette.Green,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar códigos de falla?") },
            text = {
                Text(
                    "Se apagará la luz de check engine y se perderán los datos de congelamiento. " +
                        "Si la falla persiste, el código volverá a aparecer.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; onClear() }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 16.sp, modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun DtcCard(entry: DtcEntry) {
    val info = entry.info
    val accent = if (entry.kind == DtcKind.Stored) Palette.Red else Palette.Amber
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Palette.CardTop, Palette.CardBottom)))
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        }
    }
}

// ============================================================================ CONEXIÓN

@Composable
private fun StatusPill(state: UiState) {
    val (text, color) = when (state.conn) {
        ConnState.Connected -> "● ${state.deviceName ?: "Conectado"}" to Palette.Green
        ConnState.Demo -> "● DEMO" to Palette.Amber
        ConnState.Connecting -> "● Conectando…" to Palette.Cyan
        ConnState.Disconnected -> "● Sin conexión" to Palette.TextDim
    }
    Surface(color = Palette.Surface2, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionScreen(
    state: UiState,
    btGranted: Boolean,
    onRequestBt: () -> Unit,
    bondedDevices: () -> List<BluetoothDevice>,
    onConnect: (BluetoothDevice, String) -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    val devices = remember(btGranted, refresh) {
        bondedDevices().sortedBy { if (looksLikeObd(it)) 0 else 1 }
    }
    val busy = state.conn == ConnState.Connecting

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        StatusPill(state)
        Spacer(Modifier.height(16.dp))

        Text(
            "1. Pon el adaptador ELM327 en el puerto OBD2 y enciende el contacto.\n" +
                "2. Empareja el adaptador en Ajustes → Bluetooth (PIN 1234 o 0000).\n" +
                "3. Vuelve aquí y elígelo en la lista.",
            color = Palette.TextDim,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))

        if (!btGranted) {
            Button(onClick = onRequestBt) { Text("Dar permiso de Bluetooth") }
            Spacer(Modifier.height(16.dp))
        }

        Text("Dispositivos emparejados", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) {
            Text("No hay dispositivos emparejados todavía.", color = Palette.TextDim, fontSize = 14.sp)
        }
        devices.forEach { dev ->
            val name = deviceLabel(dev)
            Card(
                onClick = { if (!busy) onConnect(dev, name) },
                colors = CardDefaults.cardColors(containerColor = Palette.Surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(dev.address, color = Palette.TextDim, fontSize = 12.sp)
                    }
                    if (looksLikeObd(dev)) Text("OBD", color = Palette.Cyan, fontSize = 12.sp)
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh++ }) { Text("Actualizar lista") }
            OutlinedButton(onClick = onDemo) { Text("Modo demo") }
            if (state.conn == ConnState.Connected || state.conn == ConnState.Demo) {
                OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun deviceLabel(d: BluetoothDevice): String =
    runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address

private fun looksLikeObd(d: BluetoothDevice): Boolean {
    val n = deviceLabel(d).uppercase()
    return listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "CAR SCAN", "OBDII").any { n.contains(it) }
}

// ============================================================================ COMÚN

@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Palette.Surface2,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.padding(12.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
