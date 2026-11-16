package com.rachav.obddash.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rachav.obddash.data.ProfileStore
import com.rachav.obddash.data.VehicleProfile
import com.rachav.obddash.obd.CompatReport
import com.rachav.obddash.obd.DtcDatabase
import com.rachav.obddash.obd.DtcInfo
import com.rachav.obddash.obd.Elm327
import com.rachav.obddash.obd.FuelModel
import com.rachav.obddash.obd.ModeSupport
import com.rachav.obddash.obd.ObdData
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
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var elm: Elm327? = null
    private var job: Job? = null
    private var supportedPids: Set<String> = emptySet()
    private var autoTried = false

    /** Mientras es true, el ciclo de lectura se detiene (para que las pruebas largas tengan el bus libre). */
    @Volatile
    private var paused = false

    init {
        val list = store.all()
        val active = list.firstOrNull { it.id == store.activeId() } ?: list.first()
        _ui.value = UiState(profiles = list, profile = active)
    }

    /** Estado limpio que conserva los perfiles cargados. */
    private fun fresh(base: UiState = UiState()) =
        base.copy(profiles = _ui.value.profiles, profile = _ui.value.profile)

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
                    val supported = e.supportedPids()
                    supportedPids = supported
                    elm = e
                    established = true
                    failures = 0
                    val adapter = e.version.takeIf { it.isNotBlank() }?.replace("ELM327V", "ELM327 v")
                    rememberAdapter(device, name)
                    _ui.update {
                        it.copy(conn = ConnState.Connected, adapter = adapter, ecuId = e.ecuId, message = null)
                    }
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
        var misses = 0
        var warned = false
        var last = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            if (paused) {
                delay(100)
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

            val now = System.nanoTime()
            val dt = ((now - last) / 1e9).coerceAtMost(2.0)
            last = now
            val trip = _ui.value.trip.advance(dt, d.speed, d.rpm, FuelModel.fuelRateLph(d, _ui.value.profile.displacementL))
            _ui.update { it.copy(data = d, trip = trip) }

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
                t += 0.05
                delay(50)
            }
        }
    }

    // ------------------------------------------------------------------ códigos de falla

    fun readDtcs() {
        if (_ui.value.conn == ConnState.Demo) {
            val list = listOf(
                DtcEntry(DtcDatabase.lookup("P0301"), DtcKind.Stored),
                DtcEntry(DtcDatabase.lookup("P0420"), DtcKind.Pending),
            )
            _ui.update {
                it.copy(dtcs = list, dtcsRead = true, mil = true, monitorsReady = 7, monitorsTotal = 8)
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
                val status = e.readStatus()
                val list = stored.map { DtcEntry(DtcDatabase.lookup(it), DtcKind.Stored) } +
                    pending.filter { it !in stored }.map { DtcEntry(DtcDatabase.lookup(it), DtcKind.Pending) }
                _ui.update {
                    it.copy(
                        dtcs = list,
                        dtcsRead = true,
                        dtcBusy = false,
                        mil = status?.mil,
                        monitorsReady = status?.monitorsReady,
                        monitorsTotal = status?.monitorsTotal,
                    )
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(dtcBusy = false, message = "No se pudieron leer los códigos: ${ex.message}") }
            }
        }
    }

    fun clearDtcs() {
        if (_ui.value.conn == ConnState.Demo) {
            _ui.update { it.copy(dtcs = emptyList(), dtcsRead = true, mil = false) }
            return
        }
        val e = elm ?: return
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true) }
            try {
                val ok = e.clearDtcs()
                _ui.update {
                    if (ok) it.copy(dtcs = emptyList(), dtcsRead = true, dtcBusy = false, mil = false)
                    else it.copy(dtcBusy = false, message = "La ECU no confirmó el borrado (¿motor encendido?).")
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(dtcBusy = false, message = "Error al borrar: ${ex.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ perfiles

    fun selectProfile(id: String) {
        val p = _ui.value.profiles.firstOrNull { it.id == id } ?: return
        if (p.id == _ui.value.profile.id) return
        store.setActive(id)
        autoTried = false
        _ui.update { it.copy(profile = p) }
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
    }

    override fun onCleared() {
        stop()
    }
}
