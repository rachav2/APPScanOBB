package com.rachav.obddash.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodersTest {

    // ------------------------------------------------------------------ DTC

    @Test fun decodesPowertrainCode() = assertEquals("P0171", decodeDtcHex("0171"))
    @Test fun decodesChassisCode() = assertEquals("C0123", decodeDtcHex("4123"))
    @Test fun decodesBodyCode() = assertEquals("B0123", decodeDtcHex("8123"))
    @Test fun decodesNetworkCode() = assertEquals("U0123", decodeDtcHex("C123"))
    @Test fun rejectsGarbage() = assertNull(decodeDtcHex("ZZZZ"))

    // ------------------------------------------------------------------ fórmulas de PIDs

    @Test fun fuelTrimIsZeroAt128() = assertEquals(0.0, Pids.decode("0106", intArrayOf(128))!!, 0.001)
    @Test fun fuelTrimPositive() = assertEquals(19.53, Pids.decode("0107", intArrayOf(153))!!, 0.01)
    @Test fun fuelTrimNegative() = assertEquals(-25.0, Pids.decode("0106", intArrayOf(96))!!, 0.001)
    @Test fun rpm() = assertEquals(1726.0, Pids.decode("010C", intArrayOf(0x1A, 0xF8))!!, 0.001)
    @Test fun coolant() = assertEquals(50.0, Pids.decode("0105", intArrayOf(90))!!, 0.001)
    @Test fun ecuVoltage() = assertEquals(14.154, Pids.decode("0142", intArrayOf(0x37, 0x4A))!!, 0.001)
    @Test fun narrowO2() = assertEquals(0.45, Pids.decode("0114", intArrayOf(90))!!, 0.001)
    @Test fun wideO2Lambda() = assertEquals(1.0, Pids.decode("0124", intArrayOf(0x80, 0x00))!!, 0.001)
    @Test fun catalystTemp() = assertEquals(360.0, Pids.decode("013C", intArrayOf(0x0F, 0xA0))!!, 0.001)
    @Test fun missingSecondByteGivesNull() = assertNull(Pids.decode("010C", intArrayOf(10)))

    // ------------------------------------------------------------------ monitores I/M

    private val sample = intArrayOf(0x00, 0x07, 0xE5, 0x20)

    @Test fun monitorsListByName() {
        val rows = Monitors.decode(sample, null)
        assertEquals(11, rows.size)
        assertEquals(MonState.Ready, rows.first { it.name == "Catalizador" }.sinceClear)
        assertEquals(MonState.NotReady, rows.first { it.name == "Sensor de oxígeno" }.sinceClear)
        assertEquals(MonState.NotSupported, rows.first { it.name == "Catalizador calentado" }.sinceClear)
    }

    @Test fun inspectionToleranceIsConfigurable() {
        val rows = Monitors.decode(sample, null)
        assertFalse(Monitors.inspectionReady(rows, 0))
        assertTrue(Monitors.inspectionReady(rows, 1))
    }

    // ------------------------------------------------------------------ batería

    @Test fun healthyBatteryPasses() {
        val v = BatteryEval.evaluate(BatteryReadings(12.6, 10.2, 14.1, 14.1, 14.2, 0.2))
        assertEquals(Verdict.Pass, v.verdict)
    }

    @Test fun deadBatteryFails() =
        assertEquals(Verdict.Fail, BatteryEval.evaluate(BatteryReadings(rest = 11.8)).verdict)

    @Test fun weakCrankWarns() =
        assertEquals(Verdict.Warn, BatteryEval.evaluate(BatteryReadings(crankMin = 9.3)).verdict)

    @Test fun lowIdleChargeFailsButSmartChargingOnlyWarns() {
        assertEquals(Verdict.Fail, BatteryEval.evaluate(BatteryReadings(idle = 12.9)).verdict)
        assertEquals(Verdict.Warn, BatteryEval.evaluate(BatteryReadings(idle = 12.9, idleEcu = 14.0)).verdict)
    }

    @Test fun oscillatingChargeWarns() =
        assertEquals(Verdict.Warn, BatteryEval.evaluate(BatteryReadings(high = 14.2, highRange = 0.8)).verdict)

    // ------------------------------------------------------------------ viaje y costo

    @Test fun tripCost() = assertEquals(12.0, TripMath.cost(100.0, 8.0, 1.5), 0.0001)

    @Test fun fuelFactor() {
        assertEquals(30.0 / 28.0, TripMath.fuelFactor(30.0, 28.0)!!, 0.0001)
        assertNull(TripMath.fuelFactor(5.0, 2.0))
        assertEquals(2.0, TripMath.fuelFactor(60.0, 10.0)!!, 0.0001)
    }

    @Test fun smoothFactorUsesLastThree() {
        assertEquals(1.0, TripMath.smoothFactor(emptyList()), 0.0001)
        assertEquals(1.2, TripMath.smoothFactor(listOf(5.0, 1.1, 1.2, 1.3)), 0.0001)
    }

    // ------------------------------------------------------------------ pistas automáticas

    @Test fun vacuumLeakHint() {
        val pairs = List(6) { 800.0 to 15.0 } + List(6) { 2500.0 to 3.0 }
        assertTrue(TrimHint.analyze(pairs)!!.contains("fuga de vacío"))
    }

    @Test fun fuelOrMafHint() {
        val pairs = List(6) { 800.0 to 15.0 } + List(6) { 2500.0 to 14.0 }
        assertTrue(TrimHint.analyze(pairs)!!.contains("MAF"))
    }

    @Test fun noHintWithoutData() = assertNull(TrimHint.analyze(listOf(800.0 to 15.0)))

    @Test fun healthyCatalyst() {
        val front = List(20) { if (it % 2 == 0) 0.1 else 0.9 }
        val rear = List(20) { 0.55 + (it % 3) * 0.02 }
        assertEquals(CatalystStatus.Ok, CatalystCheck.evaluate(front, rear).status)
    }

    @Test fun weakCatalyst() {
        val front = List(20) { if (it % 2 == 0) 0.1 else 0.9 }
        assertEquals(CatalystStatus.Weak, CatalystCheck.evaluate(front, front).status)
    }

    @Test fun catalystNeedsOscillatingFront() {
        val flat = List(20) { 0.5 }
        assertEquals(CatalystStatus.Inconclusive, CatalystCheck.evaluate(flat, flat).status)
        assertNotNull(CatalystCheck.evaluate(emptyList(), emptyList()).text)
    }
}
