package com.rachav.obddash.data

import android.content.Context
import com.rachav.obddash.obd.BatteryReadings
import com.rachav.obddash.obd.FreezeFrame
import com.rachav.obddash.obd.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Guarda listas de objetos JSON en SharedPreferences, una clave por vehículo. */
class JsonListStore(context: Context, file: String) {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun read(key: String): List<JSONObject> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun write(key: String, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
}

// ============================================================================ HISTORIAL DE FALLAS

data class DtcRecord(
    val code: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val timesCleared: Int = 0,
    val note: String = "",
    val repaired: Boolean = false,
    val freeze: FreezeFrame? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("firstSeen", firstSeen)
        put("lastSeen", lastSeen)
        put("timesCleared", timesCleared)
        put("note", note)
        put("repaired", repaired)
        freeze?.let { f ->
            put("freezeCode", f.code)
            put("freezeValues", JSONObject().also { o -> f.values.forEach { (k, v) -> o.put(k, v) } })
        }
    }

    companion object {
        fun fromJson(o: JSONObject): DtcRecord {
            val freeze = if (o.has("freezeValues")) {
                val vals = o.getJSONObject("freezeValues")
                FreezeFrame(
                    o.optString("freezeCode", o.optString("code")),
                    vals.keys().asSequence().associateWith { vals.getDouble(it) },
                )
            } else {
                null
            }
            return DtcRecord(
                code = o.getString("code"),
                firstSeen = o.optLong("firstSeen"),
                lastSeen = o.optLong("lastSeen"),
                timesCleared = o.optInt("timesCleared"),
                note = o.optString("note", ""),
                repaired = o.optBoolean("repaired", false),
                freeze = freeze,
            )
        }
    }
}

class DtcHistoryStore(context: Context) {
    private val store = JsonListStore(context, "dtc_history")
    private fun key(vid: String) = "v_$vid"

    fun all(vid: String): List<DtcRecord> =
        store.read(key(vid)).mapNotNull { runCatching { DtcRecord.fromJson(it) }.getOrNull() }
            .sortedByDescending { it.lastSeen }

    private fun save(vid: String, list: List<DtcRecord>) = store.write(key(vid), list.map { it.toJson() })

    /** Registra los códigos leídos. La fecha es la de la lectura: la ECU no entrega la de la falla. */
    fun observe(vid: String, codes: List<String>, freeze: FreezeFrame?, now: Long): List<DtcRecord> {
        val list = all(vid).toMutableList()
        for (code in codes) {
            val i = list.indexOfFirst { it.code == code }
            val frozen = freeze?.takeIf { it.code == code }
            if (i >= 0) {
                val r = list[i]
                // Si el código ya estaba marcado como reparado y regresa, vuelve a quedar abierto.
                list[i] = r.copy(lastSeen = now, repaired = false, freeze = frozen ?: r.freeze)
            } else {
                list.add(DtcRecord(code, now, now, freeze = frozen))
            }
        }
        save(vid, list)
        return all(vid)
    }

    /** Suma un borrado a los códigos que estaban activos. */
    fun markCleared(vid: String, codes: List<String>): List<DtcRecord> {
        val list = all(vid).map { if (it.code in codes) it.copy(timesCleared = it.timesCleared + 1) else it }
        save(vid, list)
        return all(vid)
    }

    fun update(vid: String, code: String, change: (DtcRecord) -> DtcRecord): List<DtcRecord> {
        save(vid, all(vid).map { if (it.code == code) change(it) else it })
        return all(vid)
    }
}

// ============================================================================ TANQUEOS

data class FillUp(
    val id: String = UUID.randomUUID().toString(),
    val date: Long,
    val liters: Double,
    val pricePerL: Double,
    /** Km recorridos desde el llenado completo anterior (los que midió la app). */
    val km: Double,
    /** Litros que había estimado la app para esos km. */
    val estimatedL: Double,
    val factor: Double?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("date", date); put("liters", liters); put("pricePerL", pricePerL)
        put("km", km); put("estimatedL", estimatedL)
        factor?.let { put("factor", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = FillUp(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            date = o.optLong("date"),
            liters = o.optDouble("liters"),
            pricePerL = o.optDouble("pricePerL"),
            km = o.optDouble("km"),
            estimatedL = o.optDouble("estimatedL"),
            factor = if (o.has("factor")) o.optDouble("factor") else null,
        )
    }
}

class FillUpStore(context: Context) {
    private val store = JsonListStore(context, "fillups")
    private fun key(vid: String) = "v_$vid"
    private fun accKey(vid: String) = "acc_$vid"

    fun all(vid: String): List<FillUp> =
        store.read(key(vid)).mapNotNull { runCatching { FillUp.fromJson(it) }.getOrNull() }.sortedByDescending { it.date }

    fun add(vid: String, f: FillUp) = store.write(key(vid), (all(vid) + f).map { it.toJson() })

    fun delete(vid: String, id: String) = store.write(key(vid), all(vid).filter { it.id != id }.map { it.toJson() })

    /** Km y litros estimados acumulados desde el último llenado completo. */
    fun accumulated(vid: String): Pair<Double, Double> {
        val raw = store.getString(accKey(vid)) ?: return 0.0 to 0.0
        val p = raw.split(',')
        return (p.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (p.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
    }

    fun setAccumulated(vid: String, km: Double, estL: Double) = store.putString(accKey(vid), "$km,$estL")
}

// ============================================================================ PRUEBAS DE BATERÍA

data class BatteryRecord(val date: Long, val readings: BatteryReadings, val verdict: Verdict) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("verdict", verdict.name)
        readings.rest?.let { put("rest", it) }
        readings.crankMin?.let { put("crankMin", it) }
        readings.idle?.let { put("idle", it) }
        readings.idleEcu?.let { put("idleEcu", it) }
        readings.high?.let { put("high", it) }
        readings.highRange?.let { put("highRange", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): BatteryRecord {
            fun d(k: String): Double? = if (o.has(k)) o.optDouble(k) else null
            return BatteryRecord(
                date = o.optLong("date"),
                readings = BatteryReadings(d("rest"), d("crankMin"), d("idle"), d("idleEcu"), d("high"), d("highRange")),
                verdict = runCatching { Verdict.valueOf(o.optString("verdict")) }.getOrDefault(Verdict.Warn),
            )
        }
    }
}

class BatteryStore(context: Context) {
    private val store = JsonListStore(context, "battery")
    private fun key(vid: String) = "v_$vid"

    fun all(vid: String): List<BatteryRecord> =
        store.read(key(vid)).mapNotNull { runCatching { BatteryRecord.fromJson(it) }.getOrNull() }.sortedByDescending { it.date }

    fun add(vid: String, r: BatteryRecord) = store.write(key(vid), (all(vid) + r).take(60).map { it.toJson() })
}

// ============================================================================ ALERTAS

enum class AlertOp(val label: String) { Above("mayor que"), Below("menor que"), AbsAbove("fuera de ±") }
enum class AlertAction(val label: String) { Sound("Sonido"), Vibrate("Vibración"), Notify("Notificación"), Voice("Voz") }

data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Clave de variable: PID (ej. "0105"), "VOLT", "TRIM" (ajuste total) o "FUEL" (nivel de combustible). */
    val key: String,
    val op: AlertOp,
    val threshold: Double,
    val minSeconds: Int = 5,
    val pauseSeconds: Int = 60,
    val actions: Set<AlertAction> = setOf(AlertAction.Sound, AlertAction.Notify),
    val critical: Boolean = false,
    val enabled: Boolean = true,
    /** Para "voltaje con motor encendido": solo evalúa si las RPM pasan de este valor. */
    val needsEngineOn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("key", key); put("op", op.name); put("threshold", threshold)
        put("minSeconds", minSeconds); put("pauseSeconds", pauseSeconds)
        put("actions", JSONArray(actions.map { it.name }))
        put("critical", critical); put("enabled", enabled); put("needsEngineOn", needsEngineOn)
    }

    companion object {
        fun fromJson(o: JSONObject): AlertRule {
            val acts = o.optJSONArray("actions")
            val set = if (acts == null) emptySet() else (0 until acts.length()).mapNotNull {
                runCatching { AlertAction.valueOf(acts.getString(it)) }.getOrNull()
            }.toSet()
            return AlertRule(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", "Alerta"),
                key = o.getString("key"),
                op = runCatching { AlertOp.valueOf(o.optString("op")) }.getOrDefault(AlertOp.Above),
                threshold = o.optDouble("threshold"),
                minSeconds = o.optInt("minSeconds", 5),
                pauseSeconds = o.optInt("pauseSeconds", 60),
                actions = set,
                critical = o.optBoolean("critical", false),
                enabled = o.optBoolean("enabled", true),
                needsEngineOn = o.optBoolean("needsEngineOn", false),
            )
        }

        /** Reglas iniciales de cada perfil; todas se pueden editar o apagar. */
        fun defaults(redline: Int) = listOf(
            AlertRule(name = "Temperatura del motor alta", key = "0105", op = AlertOp.Above, threshold = 105.0, minSeconds = 5),
            AlertRule(
                name = "Temperatura del motor crítica", key = "0105", op = AlertOp.Above, threshold = 112.0, minSeconds = 3,
                actions = setOf(AlertAction.Sound, AlertAction.Vibrate, AlertAction.Notify, AlertAction.Voice), critical = true,
            ),
            AlertRule(name = "Voltaje bajo", key = "VOLT", op = AlertOp.Below, threshold = 12.8, minSeconds = 10, needsEngineOn = true),
            AlertRule(name = "Voltaje alto", key = "VOLT", op = AlertOp.Above, threshold = 15.0, minSeconds = 10, needsEngineOn = true),
            AlertRule(name = "Combustible bajo", key = "FUEL", op = AlertOp.Below, threshold = 12.0, minSeconds = 10, pauseSeconds = 600),
            AlertRule(name = "RPM sobre la línea roja", key = "010C", op = AlertOp.Above, threshold = redline.toDouble(), minSeconds = 2, pauseSeconds = 30),
            AlertRule(name = "Ajuste de combustible fuera de ±20 %", key = "TRIM", op = AlertOp.AbsAbove, threshold = 20.0, minSeconds = 15),
        )
    }
}

class AlertRuleStore(context: Context) {
    private val store = JsonListStore(context, "alerts")
    private fun key(vid: String) = "v_$vid"

    fun all(vid: String, redline: Int): List<AlertRule> {
        val saved = store.read(key(vid))
        if (saved.isEmpty() && store.getString(key(vid)) == null) {
            val d = AlertRule.defaults(redline)
            save(vid, d)
            return d
        }
        return saved.mapNotNull { runCatching { AlertRule.fromJson(it) }.getOrNull() }
    }

    fun save(vid: String, rules: List<AlertRule>) = store.write(key(vid), rules.map { it.toJson() })
}

/** Ajustes generales de la app. */
class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Alertas con la app cerrada: servicio en primer plano con notificación permanente. */
    var backgroundAlerts: Boolean
        get() = prefs.getBoolean("bg_alerts", false)
        set(v) = prefs.edit().putBoolean("bg_alerts", v).apply()

    /** Abrir la app al encender el sistema (pantalla del auto). */
    var autostart: Boolean
        get() = prefs.getBoolean("autostart", false)
        set(v) = prefs.edit().putBoolean("autostart", v).apply()

    /** Monitores sin completar que se aceptan para "Listo para inspección". */
    var imTolerance: Int
        get() = prefs.getInt("im_tol", 1)
        set(v) = prefs.edit().putInt("im_tol", v).apply()

    fun pidCache(profileId: String): Set<String>? =
        prefs.getString("pids_$profileId", null)?.split(',')?.filter { it.isNotBlank() }?.toSet()

    fun setPidCache(profileId: String, pids: Set<String>) =
        prefs.edit().putString("pids_$profileId", pids.sorted().joinToString(",")).apply()

    fun clearPidCache(profileId: String) = prefs.edit().remove("pids_$profileId").apply()
}
