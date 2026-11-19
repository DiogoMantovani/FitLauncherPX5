package br.com.fitlauncher.px5

import org.junit.Assert.*
import org.junit.Test

class Elm327ProtocolUnitTest {
    @Test fun readsRpmWithAndWithoutHeaders() {
        assertEquals(1726, Elm327Protocol.rpm("010C\r41 0C 1A F8\r>"))
        assertEquals(1726, Elm327Protocol.rpm("410C1AF8>"))
        assertEquals(1726, Elm327Protocol.rpm("7E8 04 41 0C 1A F8\r>"))
    }

    @Test fun rejectsIncompleteOrFailedReplies() {
        assertNull(Elm327Protocol.rpm("NO DATA\r>"))
        assertNull(Elm327Protocol.rpm("UNABLE TO CONNECT>"))
        assertNull(Elm327Protocol.rpm("41 0C 1A"))
    }

    @Test fun decodesCommonSensors() {
        assertEquals(90, Elm327Protocol.coolantC("41 05 82>"))
        assertEquals(88, Elm327Protocol.vehicleKmh("41 0D 58>"))
        assertEquals(50, Elm327Protocol.engineLoadPercent("41 04 80>"))
    }

    @Test fun separatesEcuAndAdapterVoltage() {
        assertEquals(13.8, Elm327Protocol.ecuVoltage("41 42 35 E8>" )!!, 0.001)
        assertEquals(12.4, Elm327Protocol.adapterVoltage("ATRV\r12.4V>" )!!, 0.001)
        assertNull(Elm327Protocol.ecuVoltage("41 42 00"))
    }

    @Test fun usesSupportedPidBitmap() {
        assertEquals(setOf(1,3,4,6,9,16,32),
            Elm327Protocol.supportedPids("41 00 B4 81 00 01>" ,0x00))
    }
}
