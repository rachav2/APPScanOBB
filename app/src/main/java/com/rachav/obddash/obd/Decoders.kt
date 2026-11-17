package com.rachav.obddash.obd

/** Lógica pura (sin Android) para poder probarla con tests unitarios. */

/** Decodifica 2 bytes en hexadecimal (ej. "0133") al código de falla (ej. "P0133"). */
fun decodeDtcHex(hex: String): String? {
    val v = hex.toIntOrNull(16) ?: return null
    val letter = "PCBU"[(v shr 14) and 3]
    val digit = (v shr 12) and 3
    return "$letter$digit${"%03X".format(v and 0xFFF)}"
}

/** Variables de modo 01 que usan las pantallas de herramientas, con su nombre y unidad. */
object Pids {
    data class Info(val key: String, val label: String, val unit: String)

    val O2_NARROW = listOf("0114", "0115", "0116", "0117", "0118", "0119", "011A", "011B")
    val O2_WIDE = listOf("0124", "0125", "0126", "0127", "0128", "0129", "012A", "012B")
    val CAT_TEMP = listOf("013C", "013D", "013E", "013F")

    private val INFO: Map<String, Info> = listOf(
        Info("010C", "RPM", "rpm"),
        Info("010D", "Velocidad", "km/h"),
        Info("0105", "Temp. refrigerante", "°C"),
        Info("010F", "Temp. aire admisión", "°C"),
        Info("0104", "Carga del motor", "%"),
        Info("0111", "Acelerador", "%"),
        Info("0106", "Ajuste corto B1", "%"),
        Info("0107", "Ajuste largo B1", "%"),
        Info("0108", "Ajuste corto B2", "%"),
        Info("0109", "Ajuste largo B2", "%"),
        Info("010B", "MAP", "kPa"),
        Info("0110", "MAF", "g/s"),
        Info("0133", "Presión barométrica", "kPa"),
        Info("0142", "Voltaje ECU", "V"),
        Info("VOLT", "Voltaje adaptador", "V"),
        Info("0114", "O2 B1S1", "V"),
        Info("0115", "O2 B1S2", "V"),
        Info("0116", "O2 B1S3", "V"),
        Info("0117", "O2 B1S4", "V"),
        Info("0118", "O2 B2S1", "V"),
        Info("0119", "O2 B2S2", "V"),
        Info("0124", "O2 B1S1 (lambda)", "λ"),
        Info("0125", "O2 B1S2 (lambda)", "λ"),
        Info("0128", "O2 B2S1 (lambda)", "λ"),
        Info("013C", "Catalizador B1S1", "°C"),
        Info("013D", "Catalizador B2S1", "°C"),
        Info("013E", "Catalizador B1S2", "°C"),
        Info("013F", "Catalizador B2S2", "°C"),
    ).associateBy { it.key }

    fun info(key: String): Info = INFO[key] ?: Info(key, key, "")

    /** Variables que se pueden graficar (si la ECU las soporta). */
    val GRAPHABLE: List<String> = listOf(
        "010C", "010D", "0105", "010F", "0104", "0111", "0106", "0107", "0108", "0109",
        "010B", "0110", "0133", "0142", "VOLT", "0114", "0115", "0124", "013C", "013E",
    )

    /** Aplica las fórmulas SAE J1979. Devuelve null si faltan bytes. */
    fun decode(key: String, b: IntArray): Double? {
        val a = b.getOrNull(0) ?: return null
        val bb = b.getOrNull(1)
        return when (key) {
            "010C" -> bb?.let { (a * 256 + it) / 4.0 }
            "010D", "010B", "0133", "0103" -> a.toDouble()
            "0105", "010F", "0146", "015C" -> a - 40.0
            "0104", "0111", "012F" -> a * 100.0 / 255.0
            "0106", "0107", "0108", "0109" -> (a - 128) * 100.0 / 128.0
            "0110" -> bb?.let { (a * 256 + it) / 100.0 }
            "0142" -> bb?.let { (a * 256 + it) / 1000.0 }
            in O2_NARROW -> a / 200.0
            in O2_WIDE -> bb?.let { (a * 256 + it) * 2.0 / 65536.0 }
            in CAT_TEMP -> bb?.let { (a * 256 + it) / 10.0 - 40.0 }
            else -> null
        }
    }
}

// ---------------------------------------------------------------------------------- monitores I/M

enum class MonState { Ready, NotReady, NotSupported }

data class MonitorRow(val name: String, val sinceClear: MonState, val thisCycle: MonState, val hint: String)

object Monitors {
    private data class Def(val name: String, val hint: String)

    private val CONTINUOUS = listOf(
        Def("Fallos de encendido", "Se completa solo mientras manejas."),
        Def("Sistema de combustible", "Se completa solo mientras manejas."),
        Def("Componentes", "Se completa solo mientras manejas."),
    )
    private val SPARK = listOf(
        Def("Catalizador", "Maneja a velocidad constante (60-90 km/h) unos 10 minutos con el motor caliente."),
        Def("Catalizador calentado", "Igual que el catalizador; con el motor caliente y manejo estable."),
        Def("EVAP (vapores de combustible)", "Suele pedir el auto frío (reposo de 6 h o más) y el tanque entre un cuarto y tres cuartos."),
        Def("Aire secundario", "Se completa en el arranque en frío."),
        Def("Refrigerante del A/C", "Enciende el aire acondicionado un rato."),
        Def("Sensor de oxígeno", "Maneja con motor caliente a velocidad constante."),
        Def("Calentador del sensor O2", "Se completa tras un arranque en frío."),
        Def("EGR", "Alterna aceleraciones suaves y desaceleraciones."),
    )
    private val DIESEL = listOf(
        Def("Catalizador NMHC", "Manejo estable con el motor caliente."),
        Def("Postratamiento de NOx", "Manejo estable con el motor caliente."),
        Def("Reservado", ""),
        Def("Presión de sobrealimentación", "Aceleraciones progresivas."),
        Def("Reservado", ""),
        Def("Sensor de gases de escape", "Manejo estable con el motor caliente."),
        Def("Filtro de partículas", "Manejo en carretera sostenido."),
        Def("EGR / VVT", "Alterna aceleraciones suaves y desaceleraciones."),
    )

    /**
     * Arma la lista por nombre a partir de las respuestas de 0101 (desde el borrado) y 0141 (este ciclo).
     * Formato J1979: byte B bits 0-2 = monitores continuos soportados, bits 4-6 = no completados;
     * bytes C y D = monitores no continuos soportados / no completados (1 = no completado).
     */
    fun decode(b101: IntArray, b141: IntArray?): List<MonitorRow> {
        if (b101.size < 4) return emptyList()
        val diesel = (b101[1] and 0x08) != 0
        val rows = mutableListOf<MonitorRow>()

        fun state(b: IntArray?, supportedBit: Int, incompleteBit: Int, supportedByte: Int, incompleteByte: Int): MonState {
            if (b == null || b.size < 4) return MonState.NotSupported
            val sup = ((b[supportedByte] shr supportedBit) and 1) == 1
            if (!sup) return MonState.NotSupported
            return if (((b[incompleteByte] shr incompleteBit) and 1) == 1) MonState.NotReady else MonState.Ready
        }

        CONTINUOUS.forEachIndexed { i, def ->
            // Byte B: soportado en el bit i, no completado en el bit i+4.
            val since = state(b101, i, i + 4, 1, 1)
            val cycle = state(b141, i, i + 4, 1, 1)
            if (since != MonState.NotSupported || cycle != MonState.NotSupported) {
                rows.add(MonitorRow(def.name, since, cycle, def.hint))
            }
        }
        val defs = if (diesel) DIESEL else SPARK
        defs.forEachIndexed { i, def ->
            if (def.name == "Reservado") return@forEachIndexed
            val since = state(b101, i, i, 2, 3)
            val cycle = state(b141, i, i, 2, 3)
            rows.add(MonitorRow(def.name, since, cycle, def.hint))
        }
        return rows
    }

    /** "Listo para inspección": monitores no listos permitidos por la tolerancia del lugar (EE. UU.: 1 en autos 2001+). */
    fun inspectionReady(rows: List<MonitorRow>, tolerance: Int): Boolean =
        rows.count { it.sinceClear == MonState.NotReady } <= tolerance
}

// ---------------------------------------------------------------------------------- batería y alternador

enum class Verdict { Pass, Warn, Fail }

data class BatteryReadings(
    val rest: Double? = null,
    val crankMin: Double? = null,
    val idle: Double? = null,
    val idleEcu: Double? = null,
    val high: Double? = null,
    val highRange: Double? = null,
)

data class BatteryVerdict(val verdict: Verdict, val notes: List<String>)

object BatteryEval {
    fun evaluate(r: BatteryReadings): BatteryVerdict {
        var worst = Verdict.Pass
        val notes = mutableListOf<String>()
        fun raise(v: Verdict, text: String) {
            if (v.ordinal > worst.ordinal) worst = v
            notes.add(text)
        }
        r.rest?.let {
            when {
                it < 12.0 -> raise(Verdict.Fail, "Reposo %.1f V: batería descargada o dañada.".format(java.util.Locale.US, it))
                it < 12.4 -> raise(Verdict.Warn, "Reposo %.1f V: batería algo baja.".format(java.util.Locale.US, it))
            }
        }
        r.crankMin?.let {
            when {
                it < 9.0 -> raise(Verdict.Fail, "Arranque: bajó a %.1f V. Batería débil o bornes flojos.".format(java.util.Locale.US, it))
                it < 9.6 -> raise(Verdict.Warn, "Arranque: bajó a %.1f V. Revisa batería y bornes.".format(java.util.Locale.US, it))
            }
            notes.add("El adaptador muestrea poco, así que el mínimo real del arranque puede ser menor.")
        }
        r.idle?.let {
            when {
                it < 13.2 || it > 15.0 -> {
                    val smart = r.idleEcu?.let { e -> e in 13.2..15.0 } == true
                    if (smart) raise(Verdict.Warn, "Ralentí %.1f V en el adaptador, pero la ECU marca %.1f V: puede ser el voltaje del adaptador.".format(java.util.Locale.US, it, r.idleEcu))
                    else raise(Verdict.Fail, "Carga en ralentí %.1f V: fuera de 13.2-15.0 V.".format(java.util.Locale.US, it))
                }
                it < 13.8 -> raise(Verdict.Warn, "Ralentí %.1f V: algunos autos regulan el alternador; repite con luces y ventilador encendidos.".format(java.util.Locale.US, it))
            }
        }
        r.high?.let {
            when {
                it < 13.2 || it > 15.0 -> raise(Verdict.Fail, "Carga a 2500 RPM %.1f V: fuera de rango.".format(java.util.Locale.US, it))
                it < 13.8 || it > 14.7 -> raise(Verdict.Warn, "Carga a 2500 RPM %.1f V: en el borde del rango.".format(java.util.Locale.US, it))
            }
        }
        r.highRange?.let {
            if (it > 0.5) raise(Verdict.Warn, "El voltaje oscila %.1f V a 2500 RPM.".format(java.util.Locale.US, it))
        }
        if (notes.isEmpty()) notes.add("Todos los pasos dentro de lo esperado.")
        return BatteryVerdict(worst, notes)
    }
}

// ---------------------------------------------------------------------------------- viaje y costo

object TripMath {
    /** costo = distancia × consumo (L/100 km) / 100 × precio por litro */
    fun cost(distanceKm: Double, lPer100: Double, pricePerL: Double): Double = distanceKm * lPer100 / 100.0 * pricePerL

    const val KM_PER_MILE = 1.609344
    const val L_PER_GAL = 3.785411784

    /** Factor de corrección: litros reales entre litros estimados. null si todavía hay muy pocos datos. */
    fun fuelFactor(realL: Double, estimatedL: Double): Double? =
        if (estimatedL < 3.0 || realL <= 0.0) null else (realL / estimatedL).coerceIn(0.5, 2.0)

    /** Promedio de los últimos factores, para que un solo llenado raro no desajuste todo. */
    fun smoothFactor(factors: List<Double>): Double =
        if (factors.isEmpty()) 1.0 else factors.takeLast(3).average()
}

// ---------------------------------------------------------------------------------- pistas automáticas

object TrimHint {
    /** [pairs] = (RPM, ajuste total en %). Devuelve una pista, no un diagnóstico, o null si no hay suficientes datos. */
    fun analyze(pairs: List<Pair<Double, Double>>): String? {
        val idle = pairs.filter { it.first in 400.0..1200.0 }.map { it.second }
        val high = pairs.filter { it.first >= 1800.0 }.map { it.second }
        if (idle.size < 5 || high.size < 5) return null
        val ia = idle.average()
        val ha = high.average()
        return when {
            ia > 10 && ha < ia * 0.5 ->
                "El ajuste es alto en ralentí y baja al subir las RPM: apunta a una fuga de vacío. Es una pista, no un diagnóstico."
            ia > 10 && ha > 10 ->
                "El ajuste es alto en todo el rango: apunta a falta de combustible (presión, inyectores) o a un MAF sucio. Es una pista, no un diagnóstico."
            ia < -10 && ha < -10 ->
                "El ajuste es negativo en todo el rango: la mezcla es rica (inyectores con fuga, presión alta o sensor de temperatura). Es una pista, no un diagnóstico."
            else -> null
        }
    }
}

enum class CatalystStatus { Ok, Weak, Inconclusive }

data class CatalystResult(val status: CatalystStatus, val text: String)

object CatalystCheck {
    /**
     * Chequeo estimado con el motor caliente y ~2500 RPM estables: el sensor posterior debe verse casi plano
     * (entre 0.45 y 0.7 V) mientras el anterior oscila. Siempre es una estimación.
     */
    fun evaluate(front: List<Double>, rear: List<Double>): CatalystResult {
        if (front.size < 10 || rear.size < 10) {
            return CatalystResult(CatalystStatus.Inconclusive, "Faltan muestras. Repite la prueba.")
        }
        val fr = front.max() - front.min()
        val rr = rear.max() - rear.min()
        val rm = rear.average()
        if (fr < 0.4) {
            return CatalystResult(CatalystStatus.Inconclusive, "El sensor anterior casi no oscila (motor frío o lazo abierto). Calienta el motor y repite.")
        }
        val ratio = rr / fr
        return when {
            rm in 0.45..0.7 && ratio < 0.35 ->
                CatalystResult(CatalystStatus.Ok, "El sensor posterior está casi plano mientras el anterior oscila: el catalizador parece trabajar bien (estimación).")
            ratio > 0.7 ->
                CatalystResult(CatalystStatus.Weak, "El sensor posterior oscila casi igual que el anterior: el catalizador podría estar perdiendo eficiencia, lo que se relaciona con el código P0420 (estimación).")
            else ->
                CatalystResult(CatalystStatus.Inconclusive, "Resultado intermedio. Repite con el motor bien caliente y las RPM estables (estimación).")
        }
    }
}
