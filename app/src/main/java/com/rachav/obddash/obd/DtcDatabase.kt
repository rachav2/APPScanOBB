package com.rachav.obddash.obd

import android.content.Context
import org.json.JSONArray

data class DtcInfo(
    val code: String,
    val description: String,
    val system: String,
    val generic: Boolean,
    val known: Boolean,
    /** 1 = informativo, 2 = atención, 3 = grave. */
    val severity: Int = 2,
    val drivable: String = "",
    val symptoms: List<String> = emptyList(),
    val causes: List<String> = emptyList(),
    val fixes: List<String> = emptyList(),
    /** Pantalla relacionada: "trims", "o2", "map" o "battery". */
    val related: String? = null,
    /** true si la ficha trae causas y soluciones redactadas para ese código. */
    val detailed: Boolean = false,
)

/** Base local de códigos de falla (DTC): vive en assets/dtc.json y se carga una sola vez. */
object DtcDatabase {

    private var entries: Map<String, DtcInfo> = emptyMap()

    /** Carga assets/dtc.json. Si falla, la app sigue con descripciones genéricas. */
    fun init(context: Context) {
        if (entries.isNotEmpty()) return
        entries = try {
            val text = context.assets.open("dtc.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            val map = HashMap<String, DtcInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.getString("code")
                map[code] = DtcInfo(
                    code = code,
                    description = o.getString("title"),
                    system = o.optString("system", system(code)),
                    generic = isGeneric(code),
                    known = true,
                    severity = o.optInt("severity", 2),
                    drivable = o.optString("drivable", ""),
                    symptoms = strings(o.optJSONArray("symptoms")),
                    causes = strings(o.optJSONArray("causes")),
                    fixes = strings(o.optJSONArray("fixes")),
                    related = if (o.isNull("related")) null else o.optString("related").ifBlank { null },
                    detailed = o.optBoolean("detailed", false),
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    fun lookup(rawCode: String): DtcInfo {
        val code = rawCode.uppercase()
        entries[code]?.let { return it }
        return DtcInfo(
            code = code,
            description = fallback(code),
            system = system(code),
            generic = isGeneric(code),
            known = false,
        )
    }

    private fun isGeneric(c: String) = c.length >= 2 && (c[1] == '0' || c[1] == '2')

    private fun system(c: String): String = when (c.firstOrNull()) {
        'P' -> when (c.getOrNull(2)) {
            '0', '1', '2' -> "Medición de aire y combustible"
            '3' -> "Encendido / fallo de encendido"
            '4' -> "Control auxiliar de emisiones"
            '5' -> "Velocidad, ralentí y entradas auxiliares"
            '6' -> "Computadora y salidas auxiliares"
            '7', '8', '9' -> "Transmisión"
            else -> "Motor y tren motriz"
        }
        'B' -> "Carrocería"
        'C' -> "Chasis (ABS / tracción / dirección)"
        'U' -> "Red de comunicación (CAN)"
        else -> "Desconocido"
    }

    private fun fallback(c: String): String =
        if (isGeneric(c)) {
            "Código estándar sin descripción en la base local. Busca \"$c\" junto con la marca y modelo del vehículo."
        } else {
            "Código específico del fabricante (no estandarizado). Su significado depende de la marca y el modelo; consulta el manual de taller."
        }
}
