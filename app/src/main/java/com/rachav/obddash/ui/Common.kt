package com.rachav.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dos paneles que se adaptan al espacio: si es más ancho que alto (celular horizontal, pantalla de auto, tableta)
 * van lado a lado, cada uno con su desplazamiento; si no, se apilan. Se decide por el espacio, no por el modelo.
 */
@Composable
fun AdaptivePane(
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight * 1.2f) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { first() }
                Column(
                    Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { second() }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                first()
                second()
            }
        }
    }
}

/** Una sola columna con desplazamiento y margen estándar. */
@Composable
fun ScrollPage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, accent: Color = Color(0xFF1E5BD8)) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else Palette.Surface2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color.White else Palette.TextDim, fontSize = 14.sp)
    }
}

@Composable
fun Note(text: String, color: Color = Palette.TextDim) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
}

fun fmtDateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

fun fmtDay(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** Unidades configurables por perfil: se guarda en km y litros, se muestra en lo que elija el usuario. */
class UnitFmt(val distance: String, val volume: String, val currency: String) {
    private val mi get() = distance == "mi"
    private val gal get() = volume == "gal"

    fun dist(km: Double): Double = if (mi) km / com.rachav.obddash.obd.TripMath.KM_PER_MILE else km
    fun distToKm(v: Double): Double = if (mi) v * com.rachav.obddash.obd.TripMath.KM_PER_MILE else v
    fun vol(l: Double): Double = if (gal) l / com.rachav.obddash.obd.TripMath.L_PER_GAL else l
    fun volToL(v: Double): Double = if (gal) v * com.rachav.obddash.obd.TripMath.L_PER_GAL else v

    /** Precio por litro -> precio por la unidad elegida. */
    fun price(perL: Double): Double = if (gal) perL * com.rachav.obddash.obd.TripMath.L_PER_GAL else perL
    fun priceToPerL(v: Double): Double = if (gal) v / com.rachav.obddash.obd.TripMath.L_PER_GAL else v

    /** Consumo desde L/100 km: en L/100 km o en millas por galón (EE. UU.). */
    fun consumption(lPer100: Double): String =
        if (mi || gal) String.format(Locale.US, "%.1f mpg", 235.215 / lPer100) else String.format(Locale.US, "%.1f L/100 km", lPer100)

    val distLabel get() = distance
    val volLabel get() = volume
}

fun unitsOf(p: com.rachav.obddash.data.VehicleProfile) = UnitFmt(p.distanceUnit, p.volumeUnit, p.currency)
