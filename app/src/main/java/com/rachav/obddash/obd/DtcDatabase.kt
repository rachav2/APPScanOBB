package com.rachav.obddash.obd

data class DtcInfo(
    val code: String,
    val description: String,
    val system: String,
    val generic: Boolean,
    val known: Boolean,
)

/** Base local de códigos de falla (DTC) con su significado en español. */
object DtcDatabase {

    fun lookup(rawCode: String): DtcInfo {
        val code = rawCode.uppercase()
        val known = CODES[code]
        return DtcInfo(
            code = code,
            description = known ?: fallback(code),
            system = system(code),
            generic = isGeneric(code),
            known = known != null,
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

    private val CODES: Map<String, String> = mapOf(
        // --- Aire, combustible y sensores (P01xx / P02xx) ---
        "P0016" to "Correlación entre posición del cigüeñal y del árbol de levas (banco 1, sensor A)",
        "P0100" to "Falla en el circuito del sensor MAF (flujo de masa de aire)",
        "P0101" to "Sensor MAF fuera de rango o con rendimiento deficiente",
        "P0102" to "Sensor MAF: señal baja",
        "P0103" to "Sensor MAF: señal alta",
        "P0105" to "Falla en el circuito del sensor MAP / presión barométrica",
        "P0106" to "Sensor MAP fuera de rango o con rendimiento deficiente",
        "P0107" to "Sensor MAP: señal baja",
        "P0108" to "Sensor MAP: señal alta",
        "P0110" to "Falla en el circuito del sensor de temperatura del aire de admisión (IAT)",
        "P0112" to "Sensor IAT: señal baja",
        "P0113" to "Sensor IAT: señal alta",
        "P0115" to "Falla en el circuito del sensor de temperatura del refrigerante (ECT)",
        "P0116" to "Sensor ECT fuera de rango o con rendimiento deficiente",
        "P0117" to "Sensor ECT: señal baja",
        "P0118" to "Sensor ECT: señal alta",
        "P0120" to "Falla en el circuito del sensor de posición del acelerador / pedal (TPS)",
        "P0121" to "Sensor TPS fuera de rango o con rendimiento deficiente",
        "P0122" to "Sensor TPS: señal baja",
        "P0123" to "Sensor TPS: señal alta",
        "P0125" to "Temperatura del refrigerante insuficiente para el control en lazo cerrado",
        "P0128" to "Termostato: el refrigerante no alcanza la temperatura regulada",
        "P0130" to "Falla en el circuito del sensor de oxígeno (banco 1, sensor 1)",
        "P0131" to "Sensor de oxígeno B1S1: voltaje bajo",
        "P0132" to "Sensor de oxígeno B1S1: voltaje alto",
        "P0133" to "Sensor de oxígeno B1S1: respuesta lenta",
        "P0134" to "Sensor de oxígeno B1S1: sin actividad",
        "P0135" to "Calentador del sensor de oxígeno B1S1: falla en el circuito",
        "P0136" to "Falla en el circuito del sensor de oxígeno (banco 1, sensor 2)",
        "P0137" to "Sensor de oxígeno B1S2: voltaje bajo",
        "P0138" to "Sensor de oxígeno B1S2: voltaje alto",
        "P0139" to "Sensor de oxígeno B1S2: respuesta lenta",
        "P0140" to "Sensor de oxígeno B1S2: sin actividad",
        "P0141" to "Calentador del sensor de oxígeno B1S2: falla en el circuito",
        "P0150" to "Falla en el circuito del sensor de oxígeno (banco 2, sensor 1)",
        "P0153" to "Sensor de oxígeno B2S1: respuesta lenta",
        "P0155" to "Calentador del sensor de oxígeno B2S1: falla en el circuito",
        "P0156" to "Falla en el circuito del sensor de oxígeno (banco 2, sensor 2)",
        "P0161" to "Calentador del sensor de oxígeno B2S2: falla en el circuito",
        "P0171" to "Mezcla demasiado pobre (banco 1): poco combustible o entrada de aire falso",
        "P0172" to "Mezcla demasiado rica (banco 1): exceso de combustible",
        "P0174" to "Mezcla demasiado pobre (banco 2)",
        "P0175" to "Mezcla demasiado rica (banco 2)",
        "P0200" to "Falla en el circuito de los inyectores",
        "P0201" to "Falla en el circuito del inyector del cilindro 1",
        "P0202" to "Falla en el circuito del inyector del cilindro 2",
        "P0203" to "Falla en el circuito del inyector del cilindro 3",
        "P0204" to "Falla en el circuito del inyector del cilindro 4",
        "P0217" to "Sobretemperatura del motor",
        "P0219" to "Sobrerevolución del motor",
        "P0230" to "Falla en el circuito primario de la bomba de combustible",

        // --- Encendido / fallo de encendido (P03xx) ---
        "P0300" to "Fallo de encendido aleatorio o en múltiples cilindros",
        "P0301" to "Fallo de encendido en el cilindro 1",
        "P0302" to "Fallo de encendido en el cilindro 2",
        "P0303" to "Fallo de encendido en el cilindro 3",
        "P0304" to "Fallo de encendido en el cilindro 4",
        "P0305" to "Fallo de encendido en el cilindro 5",
        "P0306" to "Fallo de encendido en el cilindro 6",
        "P0307" to "Fallo de encendido en el cilindro 7",
        "P0308" to "Fallo de encendido en el cilindro 8",
        "P0316" to "Fallo de encendido detectado en los primeros 1000 giros del motor",
        "P0325" to "Falla en el circuito del sensor de detonación (knock) 1",
        "P0327" to "Sensor de detonación 1: señal baja",
        "P0328" to "Sensor de detonación 1: señal alta",
        "P0335" to "Falla en el circuito del sensor de posición del cigüeñal (CKP)",
        "P0336" to "Sensor CKP fuera de rango o con rendimiento deficiente",
        "P0337" to "Sensor CKP: señal baja",
        "P0338" to "Sensor CKP: señal alta",
        "P0340" to "Falla en el circuito del sensor de posición del árbol de levas (CMP)",
        "P0341" to "Sensor CMP fuera de rango o con rendimiento deficiente",
        "P0351" to "Falla en el circuito de la bobina de encendido A",
        "P0352" to "Falla en el circuito de la bobina de encendido B",
        "P0353" to "Falla en el circuito de la bobina de encendido C",
        "P0354" to "Falla en el circuito de la bobina de encendido D",
        "P0365" to "Falla en el circuito del sensor CMP B (banco 1)",

        // --- Emisiones (P04xx) ---
        "P0400" to "Falla en el sistema de recirculación de gases de escape (EGR)",
        "P0401" to "Flujo EGR insuficiente",
        "P0402" to "Flujo EGR excesivo",
        "P0403" to "Falla en el circuito de la válvula EGR",
        "P0404" to "Válvula EGR fuera de rango o con rendimiento deficiente",
        "P0405" to "Sensor de posición EGR A: señal baja",
        "P0420" to "Eficiencia del catalizador por debajo del umbral (banco 1)",
        "P0430" to "Eficiencia del catalizador por debajo del umbral (banco 2)",
        "P0440" to "Falla en el sistema de control de emisiones evaporativas (EVAP)",
        "P0441" to "EVAP: flujo de purga incorrecto",
        "P0442" to "EVAP: fuga pequeña detectada",
        "P0443" to "Falla en el circuito de la válvula de purga EVAP",
        "P0446" to "Falla en el circuito de control de ventilación EVAP",
        "P0449" to "Falla en la válvula de ventilación EVAP",
        "P0455" to "EVAP: fuga grande detectada (revisa el tapón del tanque)",
        "P0456" to "EVAP: fuga muy pequeña detectada",
        "P0457" to "EVAP: fuga detectada, tapón del combustible suelto o ausente",

        // --- Velocidad, ralentí y auxiliares (P05xx) ---
        "P0500" to "Falla en el sensor de velocidad del vehículo (VSS)",
        "P0501" to "Sensor VSS fuera de rango o con rendimiento deficiente",
        "P0505" to "Falla en el sistema de control de ralentí",
        "P0506" to "Régimen de ralentí más bajo de lo esperado",
        "P0507" to "Régimen de ralentí más alto de lo esperado",
        "P0510" to "Falla en el interruptor de posición de acelerador cerrado",
        "P0520" to "Falla en el circuito del sensor de presión de aceite",
        "P0521" to "Sensor de presión de aceite fuera de rango",
        "P0522" to "Presión de aceite: señal baja",
        "P0523" to "Presión de aceite: señal alta",
        "P0560" to "Falla en el voltaje del sistema eléctrico",
        "P0562" to "Voltaje del sistema bajo (batería o alternador)",
        "P0563" to "Voltaje del sistema alto",
        "P0571" to "Falla en el interruptor del freno (control de crucero)",

        // --- Computadora y salidas (P06xx) ---
        "P0600" to "Falla en el enlace de comunicación serial de la computadora",
        "P0601" to "Error de memoria interna de la ECM (checksum)",
        "P0603" to "Error en la memoria KAM de la ECM",
        "P0606" to "Falla en el procesador de la ECM",
        "P0620" to "Falla en el circuito de control del alternador",

        // --- Transmisión (P07xx-P09xx) ---
        "P0700" to "Falla en el sistema de control de transmisión",
        "P0705" to "Falla en el sensor de rango de transmisión (PRNDL)",
        "P0710" to "Falla en el sensor de temperatura del aceite de transmisión",
        "P0715" to "Falla en el sensor de velocidad de entrada de la transmisión",
        "P0720" to "Falla en el sensor de velocidad de salida de la transmisión",
        "P0730" to "Relación de engranes incorrecta",
        "P0740" to "Falla en el circuito del embrague del convertidor de par (TCC)",
        "P0741" to "Embrague del convertidor de par: rendimiento deficiente o atascado",
        "P0750" to "Falla en el solenoide de cambios A",
        "P0755" to "Falla en el solenoide de cambios B",
        "P0780" to "Falla de cambio de marcha",
        "P0850" to "Falla en el interruptor de posición estacionamiento / neutral",

        // --- Honda (específicos del fabricante) ---
        "P1259" to "Honda: falla en el sistema VTEC / solenoide de válvula VTEC",
        "P1298" to "Honda: voltaje alto en el detector de carga eléctrica (ELD)",
        "P1361" to "Honda: señal intermitente del sensor TDC",
        "P1362" to "Honda: sin señal del sensor TDC",
        "P1456" to "Honda: fuga en el sistema EVAP del tanque de combustible",
        "P1457" to "Honda: fuga en el sistema EVAP del canister",

        // --- Red de comunicación (U) ---
        "U0001" to "Bus CAN de alta velocidad: falla de comunicación",
        "U0073" to "Bus de comunicación CAN apagado (bus off)",
        "U0100" to "Pérdida de comunicación con la ECM/PCM (computadora del motor)",
        "U0101" to "Pérdida de comunicación con el módulo de transmisión (TCM)",
        "U0121" to "Pérdida de comunicación con el módulo ABS",
        "U0140" to "Pérdida de comunicación con el módulo de carrocería (BCM)",
        "U0155" to "Pérdida de comunicación con el tablero de instrumentos",
    )
}
