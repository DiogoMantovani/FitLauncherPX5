package br.com.fitlauncher.px5

fun main() {
    fun eq(want: Any?, got: Any?) { check(want == got) { "Expected $want, got $got" } }
    eq(1726, Elm327Protocol.rpm("010C\r41 0C 1A F8\r>"))
    eq(1726, Elm327Protocol.rpm("410C1AF8>"))
    eq(1726, Elm327Protocol.rpm("7E8 04 41 0C 1A F8\r>"))
    eq(null, Elm327Protocol.rpm("NO DATA\r>"))
    eq(null, Elm327Protocol.rpm("SEARCHING...\rUNABLE TO CONNECT>"))
    eq(null, Elm327Protocol.rpm("41 0C 1A"))
    eq(90, Elm327Protocol.coolantC("41 05 82>"))
    eq(88, Elm327Protocol.vehicleKmh("41 0D 58>"))
    eq(50, Elm327Protocol.engineLoadPercent("41 04 80>"))
    eq(13.8, Elm327Protocol.ecuVoltage("41 42 35 E8>"))
    eq(13.8, Elm327Protocol.adapterVoltage("13.8V\r>"))
    eq(12.4, Elm327Protocol.adapterVoltage("ATRV\r12.4V>"))
    eq(null, Elm327Protocol.adapterVoltage("?\r>"))
    eq(setOf(1, 3, 4, 6, 9, 0x10, 0x20), Elm327Protocol.supportedPids("41 00 B4 81 00 01>" ,0x00))
    // Unknown / malformed data never appears as a numeric reading.
    eq(null, Elm327Protocol.ecuVoltage("41 42 00>"))
    println("OK — 15 parsing and validation assertions passed")
}
