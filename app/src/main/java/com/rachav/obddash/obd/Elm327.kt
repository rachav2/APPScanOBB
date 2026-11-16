package com.rachav.obddash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Datos en vivo que mostramos en el panel. null = aún sin dato / no soportado. */
data class ObdData(
    val rpm: Int? = null,
    val speed: Int? = null,
    val coolant: Int? = null,
    val oil: Int? = null,
    val load: Int? = null,
    val throttle: Int? = null,
    val intake: Int? = null,
    val fuel: Int? = null,
    val voltage: Float? = null,
    val map: Int? = null,
    val maf: Float? = null,
    val ambient: Int? = null,
    val catalyst: Int? = null,
)

/** Cuadro congelado: código que lo disparó y valores guardados (clave de PID -> valor). */
data class FreezeFrame(val code: String, val values: Map<String, Double>)

/** Resultado de preguntar a un módulo (exploración experimental). */
data class ModuleResult(val header: String, val name: String, val codes: List<String>, val responded: Boolean)

/** Estado de la ECU (PID 0101): luz MIL y monitores de emisiones listos. */
data class EcuStatus(val mil: Boolean, val monitorsReady: Int, val monitorsTotal: Int)

/** Aplica la respuesta de un PID (modo 01) al estado, usando las fórmulas SAE J1979. */
fun ObdData.withPid(key: String, b: IntArray): ObdData {
    val a = b.getOrNull(0) ?: return this
    val bb = b.getOrNull(1)
    return when (key) {
        "010C" -> if (bb == null) this else copy(rpm = (a * 256 + bb) / 4)
        "010D" -> copy(speed = a)
        "0105" -> copy(coolant = a - 40)
        "015C" -> copy(oil = a - 40)
        "0104" -> copy(load = a * 100 / 255)
        "0111" -> copy(throttle = a * 100 / 255)
        "010F" -> copy(intake = a - 40)
        "012F" -> copy(fuel = a * 100 / 255)
        "010B" -> copy(map = a)
        "0110" -> if (bb == null) this else copy(maf = (a * 256 + bb) / 100f)
        "0146" -> copy(ambient = a - 40)
        "013C" -> if (bb == null) this else copy(catalyst = (a * 256 + bb) / 10 - 40)
        else -> this
    }
}

/**
 * Cliente mínimo del adaptador ELM327 sobre Bluetooth clásico (SPP).
 * Todas las llamadas a [send] se serializan con un Mutex, así que el panel en vivo
 * y la lectura de códigos pueden convivir sin mezclar respuestas.
 */
class Elm327 {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val mutex = Mutex()

    var version: String = ""
        private set

    /** Cabecera CAN de la ECU que responde, ej. "$7E8" (null si no se pudo leer). */
    var ecuId: String? = null
        private set

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        close()
        val s: BluetoothSocket = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (first: Exception) {
            // Muchos clones ELM327 solo conectan por el canal 1 vía reflexión.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (second: Exception) {
                throw IOException("No se pudo abrir el socket Bluetooth (${first.message})")
            }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    /** Configura el adaptador y despierta el bus. */
    suspend fun initAdapter() {
        version = lines(send("ATZ", 4000L)).firstOrNull { it.contains("ELM") }.orEmpty()
        send("ATE0")      // sin eco
        send("ATL0")      // sin saltos de línea extra
        send("ATS0")      // sin espacios
        send("ATH0")      // sin cabeceras
        send("ATSP0")     // protocolo automático
        send("0100", 9000L) // fuerza la detección del protocolo

        // Identifica la ECU: con cabeceras activas la respuesta empieza por el ID CAN (ej. 7E8).
        send("ATH1")
        val hdrLine = lines(send("0100", 4000L)).firstOrNull { it.contains("4100") }
        send("ATH0")
        ecuId = hdrLine?.let { l ->
            if (l.indexOf("4100") == 5) "\$" + l.substring(0, 3) else null
        }
    }

    /** Devuelve el conjunto de PIDs de modo 01 que la ECU dice soportar (ej. "010C"). */
    suspend fun supportedPids(): Set<String> {
        val result = mutableSetOf<String>()
        var base = 0
        while (base <= 0x40) {
            val key = "01" + "%02X".format(base)
            val b = readPid(key) ?: break
            if (b.size < 4) break
            val mask = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
            for (bit in 0 until 32) {
                if (((mask ushr (31 - bit)) and 1) == 1) {
                    result.add("01" + "%02X".format(base + bit + 1))
                }
            }
            if ((mask and 1) == 0) break // no hay rango siguiente
            base += 0x20
        }
        return result
    }

    /** Lee un PID de modo 01 (ej. "010C") y devuelve los bytes de datos, o null si no hay respuesta. */
    suspend fun readPid(key: String): IntArray? {
        val prefix = "41" + key.substring(2)
        for (line in lines(send(key))) {
            if (line.startsWith(prefix)) {
                val hex = line.substring(prefix.length)
                val bytes = hex.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
                return if (bytes.isEmpty()) null else bytes.toIntArray()
            }
        }
        return null
    }

    /** Voltaje de la batería medido por el propio adaptador (ATRV). */
    suspend fun readVoltage(): Float? {
        val line = lines(send("ATRV")).firstOrNull() ?: return null
        return Regex("(\\d+\\.?\\d*)").find(line)?.value?.toFloatOrNull()
    }

    /** Luz MIL (check engine) y cuántos monitores de emisiones están listos (ej. 7 de 8). */
    suspend fun readStatus(): EcuStatus? {
        val b = readPid("0101") ?: return null
        val mil = (b[0] and 0x80) != 0
        if (b.size < 4) return EcuStatus(mil, 0, 0)
        var total = 0
        var ready = 0
        // Byte B: bits 0-2 = monitores soportados (fallos de encendido, combustible, componentes),
        // bits 4-6 = "aún no completados".
        for (i in 0..2) {
            if (((b[1] shr i) and 1) == 1) {
                total++
                if (((b[1] shr (i + 4)) and 1) == 0) ready++
            }
        }
        // Bytes C y D: 8 monitores (catalizador, EVAP, sensor O2, EGR...): soportados / no completados.
        for (i in 0..7) {
            if (((b[2] shr i) and 1) == 1) {
                total++
                if (((b[3] shr i) and 1) == 0) ready++
            }
        }
        return EcuStatus(mil, ready, total)
    }

    /** Lee códigos: modo "03" (almacenados, prefijo "43") o "07" (pendientes, prefijo "47"). */
    suspend fun readDtcs(mode: String, prefix: String): List<String> {
        val raw = lines(send(mode, 7000L))
        val indexed = Regex("^[0-9A-F]:")
        val responses: List<String> =
            if (raw.any { indexed.containsMatchIn(it) }) {
                // Respuesta multitrama CAN: "0:43...", "1:...", se concatenan.
                listOf(raw.filter { indexed.containsMatchIn(it) }.joinToString("") { it.substring(2) })
            } else {
                raw
            }
        val out = LinkedHashSet<String>()
        for (resp in responses) {
            if (!resp.startsWith(prefix)) continue
            var body = resp.removePrefix(prefix)
            // Algunas ECU CAN anteponen un byte con el número de códigos.
            if (body.length % 4 == 2) body = body.substring(2)
            for (chunk in body.chunked(4)) {
                if (chunk.length == 4 && chunk != "0000") decodeDtc(chunk)?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    /** Protocolo que el adaptador negoció con el auto (ATDPN), ya con nombre legible. */
    suspend fun readProtocol(): String? {
        val raw = lines(send("ATDPN")).firstOrNull() ?: return null
        val code = raw.removePrefix("A")
        return when (code) {
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230-4 KWP (inicio lento)"
            "5" -> "ISO 14230-4 KWP (inicio rápido)"
            "6" -> "ISO 15765-4 CAN 11 bits / 500 kbps"
            "7" -> "ISO 15765-4 CAN 29 bits / 500 kbps"
            "8" -> "ISO 15765-4 CAN 11 bits / 250 kbps"
            "9" -> "ISO 15765-4 CAN 29 bits / 250 kbps"
            else -> raw
        }
    }

    /** VIN (modo 09, PID 02). Null si el auto no lo entrega. */
    suspend fun readVin(): String? {
        val raw = lines(send("0902", 6000L))
        val indexed = Regex("^[0-9A-F]:")
        val hex = if (raw.any { indexed.containsMatchIn(it) }) {
            // CAN multitrama: "0:490201...", "1:...". Tras "4902" va el contador de bloques.
            raw.filter { indexed.containsMatchIn(it) }.joinToString("") { it.substring(2) }
                .removePrefix("4902").drop(2)
        } else {
            // Otros protocolos: una línea por bloque, "4902" + número de bloque + 4 bytes.
            raw.filter { it.startsWith("4902") }.joinToString("") { it.drop(6) }
        }
        val text = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .map { it.toChar() }
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .joinToString("")
        return if (text.length >= 17) text.take(17) else null
    }

    /** Pregunta si la ECU responde a un servicio. [request] es el comando y [prefix] el inicio de una respuesta válida. */
    suspend fun respondsTo(request: String, prefix: String): Boolean {
        val raw = lines(send(request, 4000L))
        val indexed = Regex("^[0-9A-F]:")
        return raw.any { it.startsWith(prefix) } ||
            raw.any { indexed.containsMatchIn(it) && it.substring(2).startsWith(prefix) }
    }

    /** Recorre los servicios y datos clave y arma el reporte de compatibilidad. */
    suspend fun probe(supported: Set<String>): CompatReport {
        val modes = listOf(
            ModeSupport("01", "Datos en vivo", supported.isNotEmpty()),
            ModeSupport("02", "Cuadro congelado", respondsTo("020000", "4200")),
            ModeSupport("03", "Códigos almacenados", respondsTo("03", "43")),
            ModeSupport("06", "Pruebas de monitores", respondsTo("0600", "4600")),
            ModeSupport("07", "Códigos pendientes", respondsTo("07", "47")),
            ModeSupport("09", "Información del vehículo", respondsTo("0900", "4900")),
            ModeSupport("0A", "Códigos permanentes", respondsTo("0A", "4A")),
        )
        val ecuVolt = if ("0142" in supported) {
            readPid("0142")?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 1000f }
        } else {
            null
        }
        return CompatReport(
            adapter = version.takeIf { it.isNotBlank() },
            protocol = readProtocol(),
            ecuId = ecuId,
            vin = readVin(),
            voltageAdapter = readVoltage(),
            voltagePid = ecuVolt,
            supportedPids = supported.sorted(),
            modes = modes,
        )
    }

    // ------------------------------------------------------------------ fase 1: errores y cuadro congelado

    /** Concatena una respuesta multitrama CAN ("0:..", "1:..") o devuelve las líneas tal cual. */
    private fun joinFrames(raw: List<String>): List<String> {
        val indexed = Regex("^[0-9A-F]:")
        return if (raw.any { indexed.containsMatchIn(it) }) {
            listOf(raw.filter { indexed.containsMatchIn(it) }.joinToString("") { it.substring(2) })
        } else {
            raw
        }
    }

    /** Códigos permanentes (modo 0A): no se borran con un escáner. */
    suspend fun readPermanentDtcs(): List<String> = readDtcs("0A", "4A")

    /** Lee un dato del cuadro congelado (modo 02, cuadro 00). [pid] son 2 dígitos, ej. "0C". */
    private suspend fun readFrozen(pid: String): IntArray? {
        val prefix = "42" + pid + "00"
        for (line in joinFrames(lines(send("02" + pid + "00", 3000L)))) {
            if (line.startsWith(prefix)) {
                val bytes = line.substring(prefix.length).chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
                return if (bytes.isEmpty()) null else bytes.toIntArray()
            }
        }
        return null
    }

    /**
     * Cuadro congelado: el código que lo disparó (02 02) y las variables guardadas.
     * Devuelve null si la ECU no tiene ninguno guardado.
     */
    suspend fun readFreezeFrame(keys: List<String>): FreezeFrame? {
        val c = readFrozen("02") ?: return null
        if (c.size < 2) return null
        val code = decodeDtcHex("%02X%02X".format(c[0], c[1])) ?: return null
        if (code == "P0000") return null
        val values = LinkedHashMap<String, Double>()
        for (key in keys) {
            val bytes = readFrozen(key.substring(2)) ?: continue
            Pids.decode(key, bytes)?.let { values[key] = it }
        }
        return FreezeFrame(code, values)
    }

    /** Monitores I/M por nombre: 0101 (desde el borrado) y 0141 (este ciclo de manejo, si existe). */
    suspend fun readMonitors(): List<MonitorRow> {
        val b101 = readPid("0101") ?: return emptyList()
        val b141 = readPid("0141")
        return Monitors.decode(b101, b141)
    }

    /**
     * EXPERIMENTAL. Pregunta por códigos a otros módulos con UDS 0x19 sobre CAN de 11 bits.
     * Las direcciones cambian de marca en marca; si un módulo no contesta, no es un error.
     */
    suspend fun scanModules(candidates: List<Pair<String, String>>): List<ModuleResult> {
        val results = mutableListOf<ModuleResult>()
        try {
            send("ATH0")
            for ((header, label) in candidates) {
                send("ATSH$header")
                val raw = joinFrames(lines(send("1902AC", 3000L)))
                val resp = raw.firstOrNull { it.startsWith("5902") }
                if (resp == null) {
                    results.add(ModuleResult(header, label, emptyList(), false))
                    continue
                }
                // 5902 + máscara de disponibilidad (1 byte) + registros de 4 bytes (código de 2 bytes, falla, estado).
                val body = resp.removePrefix("5902").drop(2)
                val codes = body.chunked(8).filter { it.length == 8 }
                    .mapNotNull { decodeDtcHex(it.substring(0, 4)) }
                    .filter { it != "P0000" }
                results.add(ModuleResult(header, label, codes, true))
            }
        } finally {
            runCatching { send("ATSH7DF") }
        }
        return results
    }

    /** Borra códigos y apaga la luz MIL (modo 04). */
    suspend fun clearDtcs(): Boolean = lines(send("04", 5000L)).any { it.startsWith("44") }

    // ---------------------------------------------------------------------------------------

    private fun decodeDtc(hex: String): String? = decodeDtcHex(hex)

    private fun lines(raw: String): List<String> =
        raw.split('\r', '\n')
            .map { it.replace(" ", "").trim().uppercase() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }

    private suspend fun send(cmd: String, timeoutMs: Long = 2500L): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val out = output ?: throw IOException("No conectado")
            val inp = input ?: throw IOException("No conectado")
            while (inp.available() > 0) inp.read() // descarta basura previa
            out.write((cmd + "\r").toByteArray())
            out.flush()
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val c = inp.read()
                    if (c < 0) throw IOException("Conexión cerrada")
                    if (c.toChar() == '>') break // el ELM327 termina cada respuesta con '>'
                    sb.append(c.toChar())
                } else {
                    delay(15)
                }
            }
            sb.toString()
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
