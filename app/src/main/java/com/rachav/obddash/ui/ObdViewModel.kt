package com.rachav.obddash.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rachav.obddash.alerts.AlertNotifier
import com.rachav.obddash.alerts.MonitorService
import com.rachav.obddash.data.AlertOp
import com.rachav.obddash.data.AlertRule
import com.rachav.obddash.data.AlertRuleStore
import com.rachav.obddash.data.AppPrefs
import com.rachav.obddash.data.BatteryRecord
import com.rachav.obddash.data.BatteryStore
import com.rachav.obddash.data.DtcHistoryStore
import com.rachav.obddash.data.DtcRecord
import com.rachav.obddash.data.FillUp
import com.rachav.obddash.data.FillUpStore
import com.rachav.obddash.data.ProfileStore
import com.rachav.obddash.data.VehicleProfile
import com.rachav.obddash.obd.BatteryReadings
import com.rachav.obddash.obd.BatteryEval
import com.rachav.obddash.obd.CompatReport
import com.rachav.obddash.obd.DtcDatabase
import com.rachav.obddash.obd.DtcInfo
import com.rachav.obddash.obd.Elm327
import com.rachav.obddash.obd.FreezeFrame
import com.rachav.obddash.obd.FuelModel
import com.rachav.obddash.obd.ModeSupport
import com.rachav.obddash.obd.ModuleResult
import com.rachav.obddash.obd.MonitorRow
import com.rachav.obddash.obd.MonState
import com.rachav.obddash.obd.ObdData
import com.rachav.obddash.obd.Pids
import com.rachav.obddash.obd.TripMath
import com.rachav.obddash.obd.withPid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ConnState { Disconnected, Connecting, Connected, Demo }
enum class DtcKind { Stored, Pending }

data class DtcEntry(val info: DtcInfo, val kind: DtcKind)

/** Datos acumulados del viaje actual. */
data class Trip(
    val distanceKm: Double = 0.0,
    val seconds: Double = 0.0,
    val maxSpeed: Int = 0,
    val fuelL: Double = 0.0,
    val fuelKnown: Boolean = false,
) {
    val avgSpeed: Int get() = if (seconds > 5.0) (distanceKm / (seconds / 3600.0)).roundToInt() else 0

    /** Consumo promedio en L/100 km (null hasta tener algo de recorrido y datos de aire). */
    val avgConsumption: Double? get() = if (fuelKnown && distanceKm > 0.3) fuelL / distanceKm * 100.0 else null

    val timeText: String
        get() {
            val total = seconds.toLong()
            return "%02d:%02d".format(total / 3600, (total % 3600) / 60)
        }

    fun advance(dt: Double, speed: Int?, rpm: Int?, fuelLh: Double?): Trip {
        if ((rpm ?: 0) <= 0) return this // motor apagado: el viaje no avanza
        val v = speed ?: 0
        return copy(
            distanceKm = distanceKm + v * dt / 3600.0,
            seconds = seconds + dt,
            maxSpeed = maxOf(maxSpeed, v),
            fuelL = fuelL + (fuelLh ?: 0.0) * dt / 3600.0,
            fuelKnown = fuelKnown || fuelLh != null,
        )
    }
}

data class UiState(
    val conn: ConnState = ConnState.Disconnected,
    val deviceName: String? = null,
    val adapter: String? = null,
    val ecuId: String? = null,
    val data: ObdData = ObdData(),
    val trip: Trip = Trip(),
    val dtcs: List<DtcEntry> = emptyList(),
    val dtcsRead: Boolean = false,
    val dtcBusy: Boolean = false,
    val mil: Boolean? = null,
    val monitorsReady: Int? = null,
    val monitorsTotal: Int? = null,
    val message: String? = null,
    val profiles: List<VehicleProfile> = emptyList(),
    val profile: VehicleProfile = VehicleProfile.civicDefault(),
    val compat: CompatReport? = null,
    val compatBusy: Boolean = false,
    /** PIDs de modo 01 que la ECU dice soportar (vacío = aún no se sabe). */
    val supported: Set<String> = emptySet(),
    /** Última lectura de cada variable extra (fuel trims, O2, MAP...). */
    val live: Map<String, Double> = emptyMap(),
    /** Historial reciente (hasta 5 min) de las variables vigiladas. */
    val series: Map<String, List<Sample>> = emptyMap(),
    val recording: Boolean = false,
    val dtcRecords: List<DtcRecord> = emptyList(),
    val freeze: FreezeFrame? = null,
    val permanent: List<String> = emptyList(),
    val modules: List<ModuleResult> = emptyList(),
    val modulesBusy: Boolean = false,
    val monitors: List<MonitorRow> = emptyList(),
    val monitorsBusy: Boolean = false,
    val batteryHistory: List<BatteryRecord> = emptyList(),
    val fillups: List<FillUp> = emptyList(),
    /** Km y litros estimados acumulados desde el último llenado completo. */
    val fuelAcc: Pair<Double, Double> = 0.0 to 0.0,
    val alertRules: List<AlertRule> = emptyList(),
    val alertBanner: String? = null,
    val backgroundAlerts: Boolean = false,
    val autostart: Boolean = false,
    val imTolerance: Int = 1,
    /** VIN de un auto que no coincide con el perfil activo (para ofrecer crear otro perfil). */
    val pendingVin: String? = null,
)

data class Sample(val t: Long, val v: Double)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)
    private val prefs = AppPrefs(app)
    private val dtcStore = DtcHistoryStore(app)
    private val fillStore = FillUpStore(app)
    private val batteryStore = BatteryStore(app)
    private val alertStore = AlertRuleStore(app)
    private val notifier = AlertNotifier(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var elm: Elm327? = null
    private var job: Job? = null
    private var supportedPids: Set<String> = emptySet()
    private var autoTried = false

    /** Mientras es true, el ciclo de lectura se detiene (para que las pruebas largas tengan el bus libre). */
    @Volatile
    private var paused = false

    // Variables extra que piden las pantallas de herramientas (se leen además del panel, o en lugar de él).
    @Volatile
    private var watched: Set<String> = emptySet()
    @Volatile
    private var exclusive = false
    @Volatile
    private var alertKeys: Set<String> = emptySet()
    private val liveMap = HashMap<String, Double>()
    private val buffers = HashMap<String, ArrayDeque<Sample>>()
    private val csv = StringBuilder()
    private val since = HashMap<String, Long>()
    private val lastFired = HashMap<String, Long>()
    private var accKm = 0.0
    private var accEst = 0.0
    private var accSaved = 0L

    init {
        DtcDatabase.init(app)
        val list = store.all()
        val active = list.firstOrNull { it.id == store.activeId() } ?: list.first()
        _ui.value = UiState(profiles = list, profile = active)
        reloadProfileData()
    }

    /** Carga lo que se guarda por vehículo: historial de fallas, pruebas, tanqueos y reglas de alerta. */
    private fun reloadProfileData() {
        val p = _ui.value.profile
        val acc = fillStore.accumulated(p.id)
        accKm = acc.first
        accEst = acc.second
        val rules = alertStore.all(p.id, p.redlineRpm)
        refreshAlertKeys(rules)
        since.clear()
        lastFired.clear()
        _ui.update {
            it.copy(
                dtcRecords = dtcStore.all(p.id),
                batteryHistory = batteryStore.all(p.id),
                fillups = fillStore.all(p.id),
                fuelAcc = acc,
                alertRules = rules,
                backgroundAlerts = prefs.backgroundAlerts,
                autostart = prefs.autostart,
                imTolerance = prefs.imTolerance,
            )
        }
    }

    /** Estado limpio que conserva los perfiles y los datos por vehículo. */
    private fun fresh(base: UiState = UiState()): UiState {
        val c = _ui.value
        return base.copy(
            profiles = c.profiles, profile = c.profile,
            dtcRecords = c.dtcRecords, batteryHistory = c.batteryHistory, fillups = c.fillups,
            fuelAcc = c.fuelAcc, alertRules = c.alertRules, backgroundAlerts = c.backgroundAlerts,
            autostart = c.autostart, imTolerance = c.imTolerance,
        )
    }

    // ------------------------------------------------------------------ conexión real

    fun connect(device: BluetoothDevice, name: String) = startSession(device, name, auto = false)

    /**
     * Si el perfil activo tiene un adaptador guardado y está emparejado, conecta solo.
     * Se intenta una vez por perfil: si el usuario desconecta a mano, no vuelve a conectar.
     */
    @SuppressLint("MissingPermission")
    fun autoConnect(bonded: () -> List<BluetoothDevice>) {
        if (autoTried || _ui.value.conn != ConnState.Disconnected) return
        val mac = _ui.value.profile.adapterMac ?: return
        autoTried = true
        val dev = bonded().firstOrNull { it.address.equals(mac, ignoreCase = true) } ?: return
        val label = _ui.value.profile.adapterName ?: dev.address
        startSession(dev, label, auto = true)
    }

    /**
     * Abre la sesión con el adaptador. Si el enlace se cae (o falla la conexión automática) reintenta
     * 4 veces con espera creciente (1, 2, 4 y 8 s) sin tocar el viaje en curso.
     */
    @SuppressLint("MissingPermission")
    private fun startSession(device: BluetoothDevice, name: String, auto: Boolean) {
        stop()
        _ui.value = fresh(UiState(conn = ConnState.Connecting, deviceName = name))
        job = viewModelScope.launch {
            var failures = 0
            var established = false
            while (currentCoroutineContext().isActive) {
                val e = Elm327()
                try {
                    e.connect(device)
                    e.initAdapter()
                    val profile = _ui.value.profile
                    val cached = prefs.pidCache(profile.id)
                    // El VIN solo se pide si el perfil ya lo tiene (respuesta rápida) o si es la primera conexión.
                    val vin = if (profile.vin != null || cached == null) runCatching { e.readVin() }.getOrNull() else null
                    var pending: String? = null
                    var supported = cached
                    if (vin != null) {
                        if (profile.vin == null) {
                            saveVin(vin)
                        } else if (!profile.vin.equals(vin, ignoreCase = true)) {
                            pending = vin
                            supported = null // otro auto: la caché no sirve
                        }
                    }
                    if (supported == null) {
                        supported = e.supportedPids()
                        if (supported.isNotEmpty()) prefs.setPidCache(_ui.value.profile.id, supported)
                    }
                    supportedPids = supported
                    elm = e
                    established = true
                    failures = 0
                    val adapter = e.version.takeIf { it.isNotBlank() }?.replace("ELM327V", "ELM327 v")
                    rememberAdapter(device, name)
                    _ui.update {
                        it.copy(
                            conn = ConnState.Connected, adapter = adapter, ecuId = e.ecuId, message = null,
                            supported = supported, pendingVin = pending ?: it.pendingVin,
                        )
                    }
                    if (prefs.backgroundAlerts) MonitorService.start(getApplication<Application>())
                    poll(e, supported)
                } catch (ex: CancellationException) {
                    e.close()
                    throw ex
                } catch (ex: Exception) {
                    e.close()
                    elm = null
                    failures++
                    val maxRetries = if (established || auto) 4 else 0
                    if (failures > maxRetries) {
                        _ui.update {
                            it.copy(
                                conn = ConnState.Disconnected,
                                message = "No se pudo mantener la conexión: ${ex.message ?: "error desconocido"}",
                            )
                        }
                        return@launch
                    }
                    val wait = 1000L shl (failures - 1)
                    _ui.update {
                        it.copy(conn = ConnState.Connecting, message = "Reconectando ($failures/4)…")
                    }
                    delay(wait)
                }
            }
        }
    }

    /** Guarda el adaptador en el perfil activo para conectar solo la próxima vez. */
    @SuppressLint("MissingPermission")
    private fun rememberAdapter(device: BluetoothDevice, name: String) {
        val p = _ui.value.profile
        if (p.adapterMac.equals(device.address, ignoreCase = true) && p.adapterName == name) return
        val updated = p.copy(adapterMac = device.address, adapterName = name)
        store.save(updated)
        _ui.update { s -> s.copy(profile = updated, profiles = s.profiles.map { if (it.id == updated.id) updated else it }) }
    }

    private suspend fun poll(e: Elm327, supported: Set<String>) {
        // Flujo de aire para el consumo: MAF si existe, si no MAP (se consulta en cada ciclo).
        val fastExtra = when {
            "0110" in supported -> "0110"
            "010B" in supported -> "010B"
            else -> null
        }
        // PIDs "lentos": se consulta uno por ciclo para mantener RPM y velocidad fluidos.
        val slow = listOf("0105", "015C", "0104", "0111", "010F", "012F", "0146", "013C", "VOLT")
            .filter { it == "VOLT" || supported.isEmpty() || it in supported }
        var i = 0
        var wi = 0
        var misses = 0
        var warned = false
        var last = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            if (paused) {
                delay(100)
                last = System.nanoTime()
                continue
            }
            val w = watched
            if (exclusive && w.isNotEmpty()) {
                // Modo gráfica/prueba: solo se consultan las variables elegidas, para subir la frecuencia de muestreo.
                var sent = false
                for (key in w.toList()) {
                    if (paused || !currentCoroutineContext().isActive) break
                    if (readExtra(e, key, supported)) sent = true
                }
                tickAlerts()
                if (!sent) delay(100)
                last = System.nanoTime()
                continue
            }
            val rpm = e.readPid("010C")
            val spd = e.readPid("010D")
            var d = _ui.value.data
            rpm?.let { d = d.withPid("010C", it) }
            spd?.let { d = d.withPid("010D", it) }
            fastExtra?.let { key -> e.readPid(key)?.let { b -> d = d.withPid(key, b) } }

            val key = slow[i % slow.size]
            i++
            if (key == "VOLT") {
                e.readVoltage()?.let { d = d.copy(voltage = it) }
            } else {
                e.readPid(key)?.let { d = d.withPid(key, it) }
            }

            val extra = (watched + alertKeys).toList()
            if (extra.isNotEmpty()) readExtra(e, extra[wi++ % extra.size], supported)

            val now = System.nanoTime()
            val dt = ((now - last) / 1e9).coerceAtMost(2.0)
            last = now
            val raw = FuelModel.fuelRateLph(d, _ui.value.profile.displacementL)
            val lh = raw?.let { it * _ui.value.profile.fuelCorrection }
            val trip = _ui.value.trip.advance(dt, d.speed, d.rpm, lh)
            accumulate(dt, d.speed, d.rpm, raw)
            _ui.update { it.copy(data = d, trip = trip) }
            tickAlerts()

            if (rpm == null && spd == null) misses++ else {
                misses = 0
                warned = false
            }
            if (misses >= 8 && !warned) {
                warned = true
                _ui.update {
                    it.copy(message = "El adaptador responde pero la ECU no. Enciende el contacto o el motor.")
                }
            }
        }
    }

    /** Lee una variable extra, la guarda en el buffer y la publica. Devuelve false si no se pidió nada al adaptador. */
    private suspend fun readExtra(e: Elm327, key: String, supported: Set<String>): Boolean {
        val v: Double? = if (key == "VOLT") {
            e.readVoltage()?.toDouble()
        } else {
            if (supported.isNotEmpty() && key !in supported) return false
            e.readPid(key)?.let { Pids.decode(key, it) }
        }
        if (v != null) recordSamples(mapOf(key to v))
        return true
    }

    /** Guarda muestras nuevas: última lectura, buffer circular de 5 minutos y, si se graba, el CSV. */
    private fun recordSamples(values: Map<String, Double>) {
        val now = System.currentTimeMillis()
        for ((key, v) in values) {
            liveMap[key] = v
            val buf = buffers.getOrPut(key) { ArrayDeque() }
            buf.addLast(Sample(now, v))
            while (buf.size > 3000 || (buf.isNotEmpty() && buf.first().t < now - 300_000L)) buf.removeFirst()
            if (_ui.value.recording) csv.append(now).append(',').append(key).append(',').append(v).append('\n')
        }
        val w = watched
        val snapshot = buffers.filterKeys { it in w }.mapValues { it.value.toList() }
        _ui.update { it.copy(live = liveMap.toMap(), series = snapshot) }
    }

    /** Suma lo recorrido y lo estimado desde el último llenado; se guarda cada ~20 s. */
    private fun accumulate(dt: Double, speed: Int?, rpm: Int?, rawLh: Double?) {
        if ((rpm ?: 0) <= 0) return
        accKm += (speed ?: 0) * dt / 3600.0
        accEst += (rawLh ?: 0.0) * dt / 3600.0
        val now = System.currentTimeMillis()
        if (now - accSaved > 20_000L) {
            accSaved = now
            fillStore.setAccumulated(_ui.value.profile.id, accKm, accEst)
            _ui.update { it.copy(fuelAcc = accKm to accEst) }
        }
    }

    // ------------------------------------------------------------------ alertas

    private fun refreshAlertKeys(rules: List<AlertRule>) {
        val keys = mutableSetOf<String>()
        for (r in rules.filter { it.enabled }) {
            when (r.key) {
                "TRIM" -> { keys.add("0106"); keys.add("0107") }
                "0105", "VOLT", "FUEL", "010C" -> Unit // el panel ya las lee
                else -> keys.add(r.key)
            }
        }
        alertKeys = keys
    }

    private fun alertValue(r: AlertRule, s: UiState): Double? {
        if (r.needsEngineOn && (s.data.rpm ?: 0) < 400) return null
        return when (r.key) {
            "0105" -> s.data.coolant?.toDouble()
            "VOLT" -> s.data.voltage?.toDouble()
            "FUEL" -> s.data.fuel?.toDouble()
            "010C" -> s.data.rpm?.toDouble()
            "TRIM" -> {
                val a = s.live["0106"]
                val b = s.live["0107"]
                if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
            }
            else -> s.live[r.key]
        }
    }

    private fun tickAlerts() {
        val s = _ui.value
        if (s.alertRules.isEmpty()) return
        val now = System.currentTimeMillis()
        for (r in s.alertRules) {
            if (!r.enabled) continue
            val v = alertValue(r, s)
            val hit = v != null && when (r.op) {
                AlertOp.Above -> v > r.threshold
                AlertOp.Below -> v < r.threshold
                AlertOp.AbsAbove -> abs(v) > r.threshold
            }
            if (!hit || v == null) {
                since.remove(r.id)
                continue
            }
            val t0 = since.getOrPut(r.id) { now }
            if (now - t0 < r.minSeconds * 1000L) continue
            if (now - (lastFired[r.id] ?: 0L) < r.pauseSeconds * 1000L) continue
            lastFired[r.id] = now
            val text = String.format(java.util.Locale.US, "%.1f", v)
            notifier.fire(r, text)
            _ui.update { it.copy(alertBanner = "${r.name}: $text") }
        }
    }

    // ------------------------------------------------------------------ modo demo

    fun startDemo() {
        stop()
        // El viaje de demo arranca con valores de ejemplo para ver las pantallas completas.
        _ui.value = fresh(
            UiState(
                conn = ConnState.Demo,
                deviceName = "Modo demo",
                adapter = "ELM327 v1.5",
                ecuId = "\$7E8",
                trip = Trip(distanceKm = 36.5, seconds = 4320.0, maxSpeed = 110, fuelL = 2.5, fuelKnown = true),
                supported = Pids.GRAPHABLE.filter { it != "VOLT" }.toSet() + setOf("0101", "0103", "0113", "0141"),
            ),
        )
        job = viewModelScope.launch {
            var t = 0.0
            while (isActive) {
                val thr = (0.5 + 0.5 * sin(t * 0.5)).pow(2)
                val rpm = (850 + thr * 5200 + 150 * sin(t * 7)).roundToInt()
                val speed = ((rpm - 800) / 52.0 * (0.6 + 0.4 * sin(t * 0.2))).roundToInt().coerceAtLeast(0)
                val warm = (t / 20.0).coerceAtMost(1.0)
                val d = ObdData(
                    rpm = rpm,
                    speed = speed,
                    coolant = (35 + 55 * warm).roundToInt(),
                    oil = (30 + 68 * warm).roundToInt(),
                    load = (20 + thr * 70).roundToInt(),
                    throttle = (thr * 80).roundToInt(),
                    intake = 32,
                    fuel = 62,
                    voltage = 13.9f + 0.2f * sin(t).toFloat(),
                    maf = (3 + thr * 25).toFloat(),
                    ambient = 29,
                    catalyst = (300 + 280 * warm).roundToInt(),
                )
                _ui.update { s ->
                    s.copy(data = d, trip = s.trip.advance(0.05, speed, rpm, FuelModel.fuelRateLph(d, s.profile.displacementL)))
                }
                val w = watched
                if (w.isNotEmpty()) {
                    val vals = HashMap<String, Double>()
                    for (key in w) demoValue(key, t, rpm, speed, thr, warm, d)?.let { vals[key] = it }
                    if (vals.isNotEmpty()) recordSamples(vals)
                }
                tickAlerts()
                t += 0.05
                delay(50)
            }
        }
    }

    /** Valores de ejemplo para las pantallas de herramientas en modo demo. */
    private fun demoValue(key: String, t: Double, rpm: Int, speed: Int, thr: Double, warm: Double, d: ObdData): Double? = when (key) {
        "010C" -> rpm.toDouble()
        "010D" -> speed.toDouble()
        "0105" -> d.coolant?.toDouble()
        "010F" -> 32.0
        "0104" -> d.load?.toDouble()
        "0111" -> d.throttle?.toDouble()
        "0106" -> 2.0 + 1.5 * sin(t * 1.3)
        "0107" -> 3.5 + 0.4 * sin(t * 0.2)
        "0108" -> -1.0 + 1.0 * sin(t * 1.1)
        "0109" -> 2.0
        "010B" -> 32.0 + thr * 68.0
        "0110" -> d.maf?.toDouble()
        "0133" -> 101.0
        "0142" -> 14.1 + 0.1 * sin(t)
        "VOLT" -> d.voltage?.toDouble()
        "0114" -> 0.5 + 0.4 * sin(t * 6.0)
        "0115" -> 0.6 + 0.03 * sin(t * 2.0)
        "0124" -> 1.0 + 0.03 * sin(t * 6.0)
        "013C" -> 300.0 + 280.0 * warm
        "013E" -> 330.0 + 270.0 * warm
        "0103" -> 2.0
        else -> null
    }

    // ------------------------------------------------------------------ códigos de falla

    /** Variables del cuadro congelado que se muestran en la tarjeta. */
    private val freezeKeys = listOf("010C", "010D", "0105", "0104", "0106", "0107", "010B")

    fun readDtcs() {
        val vid = _ui.value.profile.id
        if (_ui.value.conn == ConnState.Demo) {
            val list = listOf(
                DtcEntry(DtcDatabase.lookup("P0171"), DtcKind.Stored),
                DtcEntry(DtcDatabase.lookup("P0420"), DtcKind.Pending),
            )
            val ff = FreezeFrame(
                "P0171",
                mapOf("010C" to 2150.0, "010D" to 62.0, "0105" to 88.0, "0104" to 41.0, "0106" to 9.4, "0107" to 14.8, "010B" to 52.0),
            )
            val rec = dtcStore.observe(vid, list.map { it.info.code }, ff, System.currentTimeMillis())
            _ui.update {
                it.copy(
                    dtcs = list, dtcsRead = true, mil = true, monitorsReady = 7, monitorsTotal = 8,
                    freeze = ff, permanent = emptyList(), dtcRecords = rec,
                )
            }
            return
        }
        val e = elm
        if (e == null) {
            _ui.update { it.copy(message = "Conéctate primero al adaptador.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true) }
            try {
                val stored = e.readDtcs("03", "43")
                val pending = e.readDtcs("07", "47")
                val permanent = runCatching { e.readPermanentDtcs() }.getOrDefault(emptyList())
                val status = e.readStatus()
                val freeze = if (stored.isNotEmpty() || pending.isNotEmpty()) {
                    runCatching { e.readFreezeFrame(freezeKeys) }.getOrNull()
                } else {
                    null
                }
                val list = stored.map { DtcEntry(DtcDatabase.lookup(it), DtcKind.Stored) } +
                    pending.filter { it !in stored }.map { DtcEntry(DtcDatabase.lookup(it), DtcKind.Pending) }
                val rec = dtcStore.observe(vid, list.map { it.info.code } + permanent.filter { it !in stored && it !in pending }, freeze, System.currentTimeMillis())
                _ui.update {
                    it.copy(
                        dtcs = list,
                        dtcsRead = true,
                        dtcBusy = false,
                        mil = status?.mil,
                        monitorsReady = status?.monitorsReady,
                        monitorsTotal = status?.monitorsTotal,
                        freeze = freeze,
                        permanent = permanent,
                        dtcRecords = rec,
                    )
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(dtcBusy = false, message = "No se pudieron leer los códigos: ${ex.message}") }
            }
        }
    }

    /** Antes de borrar, el cuadro congelado se guarda solo: la ECU lo pierde al limpiar los códigos. */
    fun clearDtcs() {
        val vid = _ui.value.profile.id
        val active = _ui.value.dtcs.map { it.info.code }
        if (_ui.value.conn == ConnState.Demo) {
            val rec = dtcStore.markCleared(vid, active)
            _ui.update { it.copy(dtcs = emptyList(), dtcsRead = true, mil = false, freeze = null, dtcRecords = rec) }
            return
        }
        val e = elm ?: return
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true) }
            try {
                val ff = _ui.value.freeze ?: runCatching { e.readFreezeFrame(freezeKeys) }.getOrNull()
                if (ff != null || active.isNotEmpty()) dtcStore.observe(vid, active, ff, System.currentTimeMillis())
                val ok = e.clearDtcs()
                if (ok) {
                    val rec = dtcStore.markCleared(vid, active)
                    _ui.update { it.copy(dtcs = emptyList(), dtcsRead = true, dtcBusy = false, mil = false, freeze = null, dtcRecords = rec) }
                } else {
                    _ui.update { it.copy(dtcBusy = false, message = "La ECU no confirmó el borrado (¿motor encendido?).") }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(dtcBusy = false, message = "Error al borrar: ${ex.message}") }
            }
        }
    }

    fun setDtcNote(code: String, note: String) {
        val rec = dtcStore.update(_ui.value.profile.id, code) { it.copy(note = note) }
        _ui.update { it.copy(dtcRecords = rec) }
    }

    fun setDtcRepaired(code: String, repaired: Boolean) {
        val rec = dtcStore.update(_ui.value.profile.id, code) { it.copy(repaired = repaired) }
        _ui.update { it.copy(dtcRecords = rec) }
    }

    /** EXPERIMENTAL: pregunta por códigos a otros módulos. Depende de la marca y del auto. */
    fun scanModules() {
        if (_ui.value.modulesBusy) return
        if (_ui.value.conn == ConnState.Demo) {
            _ui.update {
                it.copy(
                    modules = listOf(
                        ModuleResult("7E0", "Motor (ECM)", listOf("P0171"), true),
                        ModuleResult("7E1", "Transmisión (TCM)", emptyList(), true),
                        ModuleResult("7B0", "ABS / VSA (a probar)", emptyList(), false),
                    ),
                )
            }
            return
        }
        val e = elm
        if (e == null) {
            _ui.update { it.copy(message = "Conéctate primero al adaptador.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(modulesBusy = true) }
            paused = true
            try {
                val res = e.scanModules(MODULE_CANDIDATES)
                _ui.update { it.copy(modules = res, modulesBusy = false) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(modulesBusy = false, message = "La exploración se interrumpió: ${ex.message}") }
            } finally {
                paused = false
            }
        }
    }

    // ------------------------------------------------------------------ monitores I/M

    fun loadMonitors() {
        if (_ui.value.monitorsBusy) return
        if (_ui.value.conn == ConnState.Demo) {
            val rows = com.rachav.obddash.obd.Monitors.decode(
                intArrayOf(0x00, 0x07, 0xE5, 0x20), intArrayOf(0x00, 0x07, 0xE5, 0x20),
            )
            _ui.update { it.copy(monitors = rows) }
            return
        }
        val e = elm
        if (e == null) {
            _ui.update { it.copy(message = "Conéctate primero al adaptador.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(monitorsBusy = true) }
            try {
                val rows = e.readMonitors()
                _ui.update { it.copy(monitors = rows, monitorsBusy = false) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(monitorsBusy = false, message = "No se pudieron leer los monitores: ${ex.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ perfiles

    fun selectProfile(id: String) {
        val p = _ui.value.profiles.firstOrNull { it.id == id } ?: return
        if (p.id == _ui.value.profile.id) return
        store.setActive(id)
        autoTried = false
        _ui.update { it.copy(profile = p, pendingVin = null) }
        reloadProfileData()
    }

    fun saveProfile(p: VehicleProfile) {
        store.save(p)
        val list = store.all()
        _ui.update { s ->
            s.copy(profiles = list, profile = list.firstOrNull { it.id == s.profile.id } ?: list.first())
        }
    }

    fun deleteProfile(id: String) {
        store.delete(id)
        val list = store.all()
        val active = list.firstOrNull { it.id == store.activeId() } ?: list.first()
        _ui.update { it.copy(profiles = list, profile = active) }
        reloadProfileData()
    }

    fun exportProfiles(): String = store.exportJson()

    /** Devuelve cuántos perfiles se importaron, o null si el texto no era un respaldo válido. */
    fun importProfiles(text: String): Int? {
        val n = store.importJson(text)
        if (n != null) {
            val list = store.all()
            _ui.update { s -> s.copy(profiles = list, profile = list.firstOrNull { it.id == s.profile.id } ?: list.first()) }
        }
        return n
    }

    // ------------------------------------------------------------------ compatibilidad

    fun runCompat() {
        if (_ui.value.compatBusy) return
        if (_ui.value.conn == ConnState.Demo) {
            _ui.update { it.copy(compat = demoCompat()) }
            return
        }
        val e = elm
        if (e == null) {
            _ui.update { it.copy(message = "Conéctate primero al adaptador.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(compatBusy = true) }
            paused = true
            try {
                val report = e.probe(supportedPids)
                // Si el auto entregó el VIN y el perfil aún no lo tiene, se guarda.
                val p = _ui.value.profile
                if (report.vin != null && p.vin != report.vin) {
                    val updated = p.copy(vin = report.vin)
                    store.save(updated)
                    _ui.update { s ->
                        s.copy(profile = updated, profiles = s.profiles.map { if (it.id == updated.id) updated else it })
                    }
                }
                _ui.update { it.copy(compat = report, compatBusy = false) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(compatBusy = false, message = "La prueba se interrumpió: ${ex.message}") }
            } finally {
                paused = false
            }
        }
    }

    private fun demoCompat() = CompatReport(
        adapter = "ELM327 v1.5",
        protocol = "ISO 15765-4 CAN 11 bits / 500 kbps",
        ecuId = "\$7E8",
        vin = null,
        voltageAdapter = 13.9f,
        voltagePid = 14.1f,
        supportedPids = listOf(
            "0101", "0104", "0105", "0106", "0107", "010B", "010C", "010D", "010F", "0111",
            "0113", "0114", "0115", "011C", "011F", "012F", "0133", "0142", "0146",
        ),
        modes = listOf(
            ModeSupport("01", "Datos en vivo", true),
            ModeSupport("02", "Cuadro congelado", true),
            ModeSupport("03", "Códigos almacenados", true),
            ModeSupport("06", "Pruebas de monitores", false),
            ModeSupport("07", "Códigos pendientes", true),
            ModeSupport("09", "Información del vehículo", false),
            ModeSupport("0A", "Códigos permanentes", false),
        ),
        demo = true,
    )

    // ------------------------------------------------------------------ VIN y alta rápida

    private fun saveVin(vin: String) {
        val p = _ui.value.profile
        val updated = p.copy(vin = vin)
        store.save(updated)
        _ui.update { s -> s.copy(profile = updated, profiles = s.profiles.map { if (it.id == updated.id) updated else it }) }
    }

    /** Crea un perfil nuevo con el VIN del auto conectado y lo activa. */
    fun createProfileFromVin() {
        val vin = _ui.value.pendingVin ?: return
        val base = _ui.value.profile
        val np = VehicleProfile(
            name = "Auto nuevo", displacementL = base.displacementL, fuel = base.fuel, vin = vin,
            adapterMac = base.adapterMac, adapterName = base.adapterName,
            distanceUnit = base.distanceUnit, volumeUnit = base.volumeUnit, currency = base.currency,
        )
        store.save(np)
        store.setActive(np.id)
        _ui.update { it.copy(profiles = store.all(), profile = np, pendingVin = null) }
        reloadProfileData()
    }

    fun dismissPendingVin() = _ui.update { it.copy(pendingVin = null) }

    // ------------------------------------------------------------------ variables en vivo

    /**
     * Las pantallas de herramientas piden qué variables quieren. Con [onlyThese] el ciclo del panel se detiene
     * y solo se consultan esas (más muestras por segundo); si no, se leen en turnos junto al panel.
     */
    fun watch(keys: Set<String>, onlyThese: Boolean = false) {
        watched = keys
        exclusive = onlyThese
    }

    fun unwatch() {
        watched = emptySet()
        exclusive = false
    }

    fun clearSeries() {
        buffers.clear()
        liveMap.clear()
        _ui.update { it.copy(live = emptyMap(), series = emptyMap()) }
    }

    /** Graba las muestras a un CSV en memoria para compartirlo. */
    fun setRecording(on: Boolean) {
        if (on) {
            csv.setLength(0)
            csv.append("tiempo_ms,variable,valor\n")
        }
        _ui.update { it.copy(recording = on) }
    }

    fun recordingCsv(): String = csv.toString()

    // ------------------------------------------------------------------ batería

    fun saveBattery(r: BatteryReadings) {
        val verdict = BatteryEval.evaluate(r).verdict
        batteryStore.add(_ui.value.profile.id, BatteryRecord(System.currentTimeMillis(), r, verdict))
        _ui.update { it.copy(batteryHistory = batteryStore.all(it.profile.id)) }
    }

    // ------------------------------------------------------------------ combustible y tanqueos

    /** Registra un llenado completo y recalcula el factor de corrección del consumo. */
    fun addFillUp(liters: Double, pricePerL: Double) {
        val p = _ui.value.profile
        val factor = TripMath.fuelFactor(liters, accEst)
        fillStore.add(p.id, FillUp(date = System.currentTimeMillis(), liters = liters, pricePerL = pricePerL, km = accKm, estimatedL = accEst, factor = factor))
        accKm = 0.0
        accEst = 0.0
        fillStore.setAccumulated(p.id, 0.0, 0.0)
        val all = fillStore.all(p.id)
        val factors = all.sortedBy { it.date }.mapNotNull { it.factor }
        val updated = p.copy(fuelCorrection = TripMath.smoothFactor(factors), pricePerL = pricePerL)
        store.save(updated)
        _ui.update { s ->
            s.copy(
                fillups = all, fuelAcc = 0.0 to 0.0, profile = updated,
                profiles = s.profiles.map { if (it.id == updated.id) updated else it },
                message = if (factor == null) "Tanqueo guardado. Faltan más kilómetros medidos para calibrar el consumo." else null,
            )
        }
    }

    fun deleteFillUp(id: String) {
        val p = _ui.value.profile
        fillStore.delete(p.id, id)
        val all = fillStore.all(p.id)
        val updated = p.copy(fuelCorrection = TripMath.smoothFactor(all.sortedBy { it.date }.mapNotNull { it.factor }))
        store.save(updated)
        _ui.update { s -> s.copy(fillups = all, profile = updated, profiles = s.profiles.map { if (it.id == updated.id) updated else it }) }
    }

    // ------------------------------------------------------------------ alertas

    fun saveAlertRules(rules: List<AlertRule>) {
        alertStore.save(_ui.value.profile.id, rules)
        refreshAlertKeys(rules)
        _ui.update { it.copy(alertRules = rules) }
    }

    fun resetAlertRules() {
        val p = _ui.value.profile
        saveAlertRules(AlertRule.defaults(p.redlineRpm))
    }

    fun setBackgroundAlerts(on: Boolean) {
        prefs.backgroundAlerts = on
        _ui.update { it.copy(backgroundAlerts = on) }
        val connected = _ui.value.conn == ConnState.Connected
        if (on && connected) MonitorService.start(getApplication<Application>()) else if (!on) MonitorService.stop(getApplication<Application>())
    }

    fun setAutostart(on: Boolean) {
        prefs.autostart = on
        _ui.update { it.copy(autostart = on) }
    }

    fun setImTolerance(n: Int) {
        prefs.imTolerance = n.coerceIn(0, 3)
        _ui.update { it.copy(imTolerance = prefs.imTolerance) }
    }

    fun clearAlertBanner() = _ui.update { it.copy(alertBanner = null) }

    // ------------------------------------------------------------------ utilidades

    fun resetTrip() = _ui.update { it.copy(trip = Trip()) }

    fun disconnect() {
        stop()
        _ui.value = fresh()
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun stop() {
        job?.cancel()
        job = null
        elm?.close()
        elm = null
        MonitorService.stop(getApplication<Application>())
    }

    override fun onCleared() {
        fillStore.setAccumulated(_ui.value.profile.id, accKm, accEst)
        stop()
        notifier.shutdown()
    }

    private companion object {
        /** Direcciones CAN de 11 bits a probar. Las de ABS, airbag y carrocería son suposiciones comunes, no un estándar. */
        val MODULE_CANDIDATES = listOf(
            "7E0" to "Motor (ECM)",
            "7E1" to "Transmisión (TCM)",
            "7B0" to "ABS / VSA (a probar)",
            "7C0" to "Airbag / SRS (a probar)",
            "7A0" to "Carrocería / BCM (a probar)",
            "7D0" to "Tablero (a probar)",
        )
    }
}
