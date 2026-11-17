package com.rachav.obddash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.rachav.obddash.data.FillUp
import com.rachav.obddash.obd.TripMath
import com.rachav.obddash.report.PdfReport
import java.util.Locale

private fun d2(v: Double?) = if (v == null) "--" else String.format(Locale.US, "%.2f", v)

/** Consumo real medido con los tanqueos (L/100 km), o null si no hay datos suficientes. */
private fun fillAverage(f: List<FillUp>): Double? {
    val km = f.sumOf { it.km }
    val l = f.sumOf { it.liters }
    return if (km > 20.0 && l > 0.0) l / km * 100.0 else null
}

// ============================================================================ COSTO DEL TRAYECTO

@Composable
fun CostScreen(state: UiState) {
    val u = unitsOf(state.profile)
    val trip = state.trip
    val price = state.profile.pricePerL
    var planText by remember { mutableStateOf("") }

    // Consumo promedio del vehículo: el del viaje si ya hay recorrido; si no, el de los tanqueos.
    val avgCons = trip.avgConsumption ?: fillAverage(state.fillups)

    AdaptivePane(
        first = {
            Section("Viaje en curso") {
                KeyValue("Distancia", d2(u.dist(trip.distanceKm)) + " " + u.distLabel)
                KeyValue("Combustible usado (estimado)", if (trip.fuelKnown) d2(u.vol(trip.fuelL)) + " " + u.volLabel else "--")
                KeyValue("Consumo promedio", trip.avgConsumption?.let { u.consumption(it) } ?: "--")
                KeyValue("Precio por ${u.volLabel}", price?.let { u.currency + d2(u.price(it)) } ?: "sin precio")
                if (price != null && trip.fuelKnown) {
                    KeyValue("Costo del viaje", u.currency + d2(trip.fuelL * price))
                }
                if (price == null) Note("Pon el precio del combustible en el perfil o registra un tanqueo.", Palette.Amber)
            }
            Note("El consumo sale de una estimación por flujo de aire, así que puede desviarse. Para afinarlo, registra tus tanqueos (factor actual: ${String.format(Locale.US, "%.3f", state.profile.fuelCorrection)}).")
        },
        second = {
            Section("Planificador de ruta") {
                Note("Escribe la distancia de la ruta en ${u.distLabel}. Se multiplica por el consumo promedio del vehículo.")
                Field("Distancia (${u.distLabel})", planText, number = true) { planText = it }
                val dist = num(planText)
                val km = dist?.let { u.distToKm(it) }
                when {
                    avgCons == null -> Note("Aún no hay consumo promedio: maneja un poco o registra tanqueos.", Palette.Amber)
                    price == null -> Note("Falta el precio del combustible.", Palette.Amber)
                    km != null && km > 0 -> {
                        val cost = TripMath.cost(km, avgCons, price)
                        val liters = km * avgCons / 100.0
                        KeyValue("Consumo usado", u.consumption(avgCons))
                        KeyValue("Combustible necesario", d2(u.vol(liters)) + " " + u.volLabel)
                        KeyValue("Costo estimado", u.currency + d2(cost))
                    }
                }
            }
        },
    )
}

// ============================================================================ TANQUEOS

@Composable
fun FillUpScreen(state: UiState, vm: ObdViewModel) {
    val u = unitsOf(state.profile)
    var liters by remember { mutableStateOf("") }
    var price by remember(state.profile.id) {
        mutableStateOf(state.profile.pricePerL?.let { String.format(Locale.US, "%.2f", u.price(it)) }.orEmpty())
    }
    val (accKm, accEst) = state.fuelAcc

    AdaptivePane(
        first = {
            Section("Desde el último llenado completo") {
                KeyValue("Recorrido medido", d2(u.dist(accKm)) + " " + u.distLabel)
                KeyValue("Combustible estimado", d2(u.vol(accEst)) + " " + u.volLabel)
                KeyValue("Factor de corrección actual", String.format(Locale.US, "%.3f", state.profile.fuelCorrection))
            }
            Section("Registrar llenado completo") {
                Note("Llena el tanque hasta el tope y anota los ${u.volLabel} que entraron. La app compara con lo que estimó y ajusta el consumo de ahí en adelante.")
                Field("Cantidad (${u.volLabel})", liters, number = true) { liters = it }
                Field("Precio por ${u.volLabel} (${u.currency})", price, number = true) { price = it }
                Button(
                    onClick = {
                        val l = num(liters)
                        val p = num(price)
                        if (l != null && l > 0 && p != null && p > 0) {
                            vm.addFillUp(u.volToL(l), u.priceToPerL(p))
                            liters = ""
                        }
                    },
                    enabled = (num(liters) ?: 0.0) > 0 && (num(price) ?: 0.0) > 0,
                ) { Text("Guardar tanqueo") }
            }
        },
        second = {
            if (state.fillups.isEmpty()) Note("Aún no hay tanqueos de ${state.profile.name}.")
            state.fillups.forEach { fu ->
                Section(fmtDay(fu.date)) {
                    KeyValue("Cantidad", d2(u.vol(fu.liters)) + " " + u.volLabel + " a " + u.currency + d2(u.price(fu.pricePerL)))
                    KeyValue("Recorrido medido", d2(u.dist(fu.km)) + " " + u.distLabel)
                    KeyValue("Estimado por la app", d2(u.vol(fu.estimatedL)) + " " + u.volLabel)
                    KeyValue("Factor", fu.factor?.let { String.format(Locale.US, "%.3f", it) } ?: "pocos datos")
                    TextButton(onClick = { vm.deleteFillUp(fu.id) }) { Text("Borrar") }
                }
            }
        },
    )
}

// ============================================================================ REPORTES

@Composable
fun ReportsScreen(state: UiState) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    fun make(kind: String) {
        status = try {
            val file = if (kind == "health") PdfReport.health(context, state) else PdfReport.trip(context, state)
            PdfReport.share(context, file)
            "Reporte generado."
        } catch (e: Exception) {
            "No se pudo generar el reporte: ${e.message}"
        }
    }

    ScrollPage {
        Note("Los reportes se generan en este dispositivo y se comparten con el menú de compartir (WhatsApp, correo, guardar en archivos…).")
        Section("Reporte de salud") {
            Note("Datos del vehículo y VIN, códigos con descripción y causas, cuadro congelado, monitores I/M, prueba de batería, fuel trims y sensores de oxígeno. Antes lee los códigos y los monitores para que salgan completos.")
            Button(onClick = { make("health") }) { Text("Generar PDF de salud") }
        }
        Section("Reporte de viaje") {
            Note("Distancia, tiempo, velocidades, consumo, costo y calibración con tanqueos.")
            Button(onClick = { make("trip") }) { Text("Generar PDF de viaje") }
        }
        status?.let { Note(it, Palette.Cyan) }
    }
}
