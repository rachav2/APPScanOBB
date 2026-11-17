package com.rachav.obddash.obd

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un servicio OBD (modo) y si la ECU respondió a él. */
data class ModeSupport(val mode: String, val name: String, val supported: Boolean)

/** Resultado de la prueba de compatibilidad: qué responde realmente el auto con este adaptador. */
data class CompatReport(
    val adapter: String?,
    val protocol: String?,
    val ecuId: String?,
    val vin: String?,
    val voltageAdapter: Float?,
    val voltagePid: Float?,
    val supportedPids: List<String>,
    val modes: List<ModeSupport>,
    val demo: Boolean = false,
)

/** Nombres legibles de los PIDs de modo 01 que la app conoce o piensa usar. */
object PidCatalog {
    private val NAMES: Map<String, String> = mapOf(
        "0101" to "Estado de monitores y luz MIL",
        "0103" to "Estado del sistema de combustible",
        "0104" to "Carga del motor",
        "0105" to "Temperatura del refrigerante",
        "0106" to "Ajuste corto de combustible, banco 1",
        "0107" to "Ajuste largo de combustible, banco 1",
        "0108" to "Ajuste corto de combustible, banco 2",
        "0109" to "Ajuste largo de combustible, banco 2",
        "010B" to "Presión del múltiple (MAP)",
        "010C" to "RPM",
        "010D" to "Velocidad",
        "010E" to "Avance del encendido",
        "010F" to "Temperatura del aire de admisión",
        "0110" to "Flujo de aire (MAF)",
        "0111" to "Posición del acelerador",
        "0113" to "Sensores de oxígeno presentes",
        "0114" to "Sensor O2 B1S1 (banda angosta)",
        "0115" to "Sensor O2 B1S2 (banda angosta)",
        "011C" to "Norma OBD que cumple el auto",
        "011F" to "Tiempo desde el arranque",
        "0121" to "Distancia con la luz MIL encendida",
        "012F" to "Nivel de combustible",
        "0130" to "Calentamientos desde el borrado",
        "0131" to "Distancia desde el borrado",
        "0133" to "Presión barométrica",
        "0134" to "Sensor O2 B1S1 (banda ancha, corriente)",
        "013C" to "Temperatura del catalizador B1S1",
        "013D" to "Temperatura del catalizador B2S1",
        "013E" to "Temperatura del catalizador B1S2",
        "013F" to "Temperatura del catalizador B2S2",
        "0141" to "Monitores de este ciclo de manejo",
        "0142" to "Voltaje del módulo de control (ECU)",
        "0143" to "Carga absoluta del motor",
        "0144" to "Relación de equivalencia (lambda) pedida",
        "0145" to "Posición relativa del acelerador",
        "0146" to "Temperatura ambiente",
        "0149" to "Posición del pedal del acelerador D",
        "014A" to "Posición del pedal del acelerador E",
        "015C" to "Temperatura del aceite",
        "015E" to "Consumo de combustible del motor",
    )

    fun name(key: String): String = NAMES[key] ?: "PID no catalogado"
}

/** Convierte el reporte en texto plano para compartirlo (WhatsApp, correo, notas...). */
fun CompatReport.toText(vehicleName: String): String = buildString {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    appendLine("OBD Dash - prueba de compatibilidad")
    appendLine("Fecha: $stamp")
    appendLine("Vehiculo: $vehicleName")
    if (demo) appendLine("(datos de ejemplo del modo demo)")
    appendLine()
    appendLine("Adaptador: ${adapter ?: "--"}")
    appendLine("Protocolo: ${protocol ?: "--"}")
    appendLine("ECU: ${ecuId ?: "--"}")
    appendLine("VIN: ${vin ?: "no entregado"}")
    appendLine("Voltaje (adaptador, ATRV): ${voltageAdapter?.let { "%.1f V".format(Locale.US, it) } ?: "--"}")
    appendLine("Voltaje (ECU, PID 0142): ${voltagePid?.let { "%.1f V".format(Locale.US, it) } ?: "no soportado"}")
    appendLine()
    appendLine("Servicios OBD:")
    modes.forEach { appendLine("  modo ${it.mode} ${it.name}: ${if (it.supported) "si" else "no"}") }
    appendLine()
    appendLine("PIDs de modo 01 soportados (${supportedPids.size}):")
    supportedPids.forEach { appendLine("  $it  ${PidCatalog.name(it)}") }
}
