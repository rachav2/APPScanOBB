package com.rachav.obddash.obd

/**
 * Estimación de consumo de combustible.
 *
 * Si la ECU entrega el flujo de aire (MAF, PID 0110) se usa directamente. Muchos Honda no tienen MAF
 * y usan presión de admisión (MAP), así que en ese caso se estima el flujo con el método
 * "velocidad-densidad": MAP + temperatura de admisión + RPM + cilindrada.
 *
 * Es una estimación: ajusta [DISPLACEMENT_L] a la cilindrada real de tu motor y
 * [VOLUMETRIC_EFFICIENCY] si el consumo promedio no coincide con lo que ves en las gasolineras.
 */
object FuelModel {
    const val DISPLACEMENT_L = 1.8
    const val VOLUMETRIC_EFFICIENCY = 0.82
    private const val AFR = 14.7
    private const val GASOLINE_G_PER_L = 745.0

    /** Flujo de aire en g/s, o null si faltan datos. */
    fun airflowGs(d: ObdData): Double? {
        d.maf?.let { return it.toDouble() }
        val map = d.map ?: return null
        val rpm = d.rpm ?: return null
        val iatC = d.intake ?: return null
        if (rpm <= 0) return 0.0
        val iatK = iatC + 273.15
        val imap = rpm * map / iatK
        return imap / 120.0 * VOLUMETRIC_EFFICIENCY * DISPLACEMENT_L * 28.97 / 8.314
    }

    /** Consumo instantáneo en litros por hora. */
    fun fuelRateLph(d: ObdData): Double? = airflowGs(d)?.let { it / AFR / GASOLINE_G_PER_L * 3600.0 }
}
