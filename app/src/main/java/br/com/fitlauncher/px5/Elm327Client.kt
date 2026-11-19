package br.com.fitlauncher.px5

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic Serial Port Profile (SPP) OBD2 reader for Android 9.
 * All ECU queries are standardized OBD-II Mode 01 (read-only).
 * Does not attempt simultaneous ownership of the ELM327 with another app.
 */
class Elm327Client(private val callback: (Reading) -> Unit) {
    data class Reading(
        val state: String,
        val rpm: Int? = null,
        val coolantC: Int? = null,
        val vehicleKmh: Int? = null,
        val ecuVoltage: Double? = null,
        val adapterVoltage: Double? = null,
        val engineLoad: Int? = null
    )

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var generation: Long = 0
    @Volatile private var running: Boolean = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var worker: Thread? = null

    @Synchronized fun connect(device: BluetoothDevice) {
        disconnect()
        running = true
        val session = generation
        worker = Thread {
            var localSocket: BluetoothSocket? = null
            try {
                callback(Reading("Conectando ao ELM327..."))
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                localSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket = localSocket
                localSocket.connect()
                if (!isCurrent(session)) return@Thread
                val input = localSocket.inputStream
                val output = localSocket.outputStream
                fun cmd(value: String, timeoutMs: Long = 3000): String? =
                    command(value, input, output, timeoutMs, session)

                // ELM initialization; some inexpensive adapters return '?' for optional AT commands.
                cmd("ATZ", 6500)
                cmd("ATE0")
                cmd("ATL0")
                cmd("ATS0")
                cmd("ATH0")
                cmd("ATSP0", 5000) // Auto-detect ECU protocol
                cmd("ATAT1")       // Adaptive timing (not supported by every clone)

                val supported0 = Elm327Protocol.supportedPids(cmd("0100", 5500), 0x00)
                val supported20 = if (supported0?.contains(0x20) == true)
                    Elm327Protocol.supportedPids(cmd("0120"), 0x20) else null
                val supported40 = if (supported20?.contains(0x40) == true)
                    Elm327Protocol.supportedPids(cmd("0140"), 0x40) else null

                fun supported(pid: Int): Boolean = when (pid) {
                    in 1..0x20 -> supported0?.contains(pid) ?: true
                    in 0x21..0x40 -> supported20?.contains(pid) ?: (supported0 == null)
                    in 0x41..0x60 -> supported40?.contains(pid) ?: (supported0 == null)
                    else -> false
                }

                var idleCycles = 0
                var loop = 0
                var lastAdapterVoltage: Double? = null
                while (isCurrent(session)) {
                    val rpm = if (supported(0x0C)) Elm327Protocol.rpm(cmd("010C")) else null
                    val coolant = if (supported(0x05)) Elm327Protocol.coolantC(cmd("0105")) else null
                    val speed = if (supported(0x0D)) Elm327Protocol.vehicleKmh(cmd("010D")) else null
                    val load = if (supported(0x04)) Elm327Protocol.engineLoadPercent(cmd("0104")) else null
                    val volts = if (supported(0x42)) Elm327Protocol.ecuVoltage(cmd("0142")) else null
                    if (loop % 4 == 0) {
                        lastAdapterVoltage = Elm327Protocol.adapterVoltage(cmd("ATRV", 1500))
                    }
                    if (!isCurrent(session)) break
                    val validEcuData = listOf(rpm, coolant, speed, load, volts).any { it != null }
                    idleCycles = if (validEcuData) 0 else idleCycles + 1
                    val status = if (validEcuData) "OBD2 conectado" else "ELM conectado • aguardando ECU"
                    callback(Reading(status, rpm, coolant, speed, volts, lastAdapterVoltage, load))
                    if (idleCycles >= 5) {
                        callback(Reading("Sem dados da ECU • verifique a ignição"))
                        cmd("ATSP0", 4000)
                        idleCycles = 0
                    }
                    loop++
                    try { Thread.sleep(450) } catch (_: InterruptedException) { break }
                }
            } catch (ex: Exception) {
                if (isCurrent(session)) callback(Reading("Falha OBD2: ${ex.javaClass.simpleName}"))
            } finally {
                try { localSocket?.close() } catch (_: Exception) {}
                if (generation == session) {
                    socket = null
                    running = false
                }
            }
        }.apply { name = "FitLauncher-ELM327"; isDaemon = true; start() }
    }

    private fun isCurrent(session: Long) = running && generation == session

    /** Collects bytes up to ELM '>' prompt; a timeout is never treated as valid data. */
    private fun command(
        text: String, input: InputStream, output: OutputStream,
        timeoutMs: Long, session: Long
    ): String? {
        if (!isCurrent(session)) return null
        output.write((text + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val result = StringBuilder()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (isCurrent(session) && System.nanoTime() < deadline) {
            if (input.available() > 0) {
                val value = input.read()
                if (value < 0) return null
                if (value == '>'.code) return result.toString()
                result.append(value.toChar())
                if (result.length > 2048) return null
            } else {
                try { Thread.sleep(20) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    @Synchronized fun disconnect() {
        running = false
        generation++
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
        socket = null
    }
}
