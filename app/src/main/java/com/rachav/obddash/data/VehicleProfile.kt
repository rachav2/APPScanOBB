package com.rachav.obddash.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Un vehículo con sus datos. Todo lo que se guarda por auto (viajes, fallas, pruebas) cuelga de [id]. */
data class VehicleProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mi auto",
    val brand: String = "",
    val model: String = "",
    val year: Int? = null,
    /** Cilindrada en litros; la usa el cálculo de consumo. */
    val displacementL: Double = 1.8,
    val fuel: String = "Gasolina",
    val tankL: Double? = null,
    /** Precio por litro, en la moneda que el usuario use. */
    val pricePerL: Double? = null,
    val adapterMac: String? = null,
    val adapterName: String? = null,
    val vin: String? = null,
    val redlineRpm: Int = 6800,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("brand", brand)
        put("model", model)
        year?.let { put("year", it) }
        put("displacementL", displacementL)
        put("fuel", fuel)
        tankL?.let { put("tankL", it) }
        pricePerL?.let { put("pricePerL", it) }
        adapterMac?.let { put("adapterMac", it) }
        adapterName?.let { put("adapterName", it) }
        vin?.let { put("vin", it) }
        put("redlineRpm", redlineRpm)
    }

    companion object {
        fun fromJson(o: JSONObject): VehicleProfile {
            val d = VehicleProfile()
            return VehicleProfile(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", d.name).ifBlank { d.name },
                brand = o.optString("brand", ""),
                model = o.optString("model", ""),
                year = if (o.has("year")) o.optInt("year") else null,
                displacementL = o.optDouble("displacementL", d.displacementL).takeIf { it > 0.0 } ?: d.displacementL,
                fuel = o.optString("fuel", d.fuel),
                tankL = if (o.has("tankL")) o.optDouble("tankL") else null,
                pricePerL = if (o.has("pricePerL")) o.optDouble("pricePerL") else null,
                adapterMac = o.optString("adapterMac").ifBlank { null },
                adapterName = o.optString("adapterName").ifBlank { null },
                vin = o.optString("vin").ifBlank { null },
                redlineRpm = o.optInt("redlineRpm", d.redlineRpm).takeIf { it > 0 } ?: d.redlineRpm,
            )
        }

        /** Perfil inicial: el Civic 1.8 L que ya usaba la app. */
        fun civicDefault() = VehicleProfile(
            name = "CIVIC",
            brand = "Honda",
            model = "Civic",
            year = 2012,
            displacementL = 1.8,
        )
    }
}

/** Guarda los perfiles en SharedPreferences como JSON (sin base de datos ni dependencias extra). */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun all(): List<VehicleProfile> {
        val raw = prefs.getString(KEY_LIST, null)
        val list = if (raw == null) emptyList() else parse(raw)
        if (list.isNotEmpty()) return list
        val first = VehicleProfile.civicDefault()
        writeAll(listOf(first))
        prefs.edit().putString(KEY_ACTIVE, first.id).apply()
        return listOf(first)
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun save(profile: VehicleProfile) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == profile.id }
        if (i >= 0) list[i] = profile else list.add(profile)
        writeAll(list)
    }

    /** No permite borrar el último perfil. */
    fun delete(id: String) {
        val list = all().filter { it.id != id }
        if (list.isEmpty()) return
        writeAll(list)
        if (activeId() == id) setActive(list.first().id)
    }

    fun exportJson(): String = JSONArray().apply { all().forEach { put(it.toJson()) } }.toString(2)

    /** Importa perfiles de un JSON exportado antes. Devuelve cuántos agregó o actualizó, o null si el texto no sirve. */
    fun importJson(text: String): Int? {
        val incoming = parse(text)
        if (incoming.isEmpty()) return null
        incoming.forEach { save(it) }
        return incoming.size
    }

    private fun parse(text: String): List<VehicleProfile> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(VehicleProfile::fromJson) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeAll(list: List<VehicleProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_ACTIVE = "active"
    }
}
