package com.kanealex.obdauto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A Mode 01 PID, or the adapter's battery voltage when pid is null. f() gets the data bytes after "41 xx". */
class Metric(
    val label: String,
    val pid: String?,
    val unit: String,
    val n: Int = 1,
    val decimals: Int = 0,
    val f: (IntArray) -> Double = { 0.0 },
) {
    var fails = 0
    var skip = false
}

// Ordered by priority: the car screen may only show the first few.
fun defaultMetrics() = listOf(
    Metric("Speed", "0D", "km/h") { it[0].toDouble() },
    Metric("RPM", "0C", "rpm", n = 2) { (it[0] * 256 + it[1]) / 4.0 },
    Metric("Coolant", "05", "°C") { it[0] - 40.0 },
    Metric("Manifold pressure", "0B", "kPa") { it[0].toDouble() },
    Metric("Battery", null, "V", decimals = 1),
    Metric("Engine load", "04", "%") { it[0] * 100 / 255.0 },
    Metric("Intake air", "0F", "°C") { it[0] - 40.0 },
    Metric("Throttle", "11", "%") { it[0] * 100 / 255.0 },
    Metric("Fuel rate", "5E", "L/h", n = 2, decimals = 1) { (it[0] * 256 + it[1]) / 20.0 },
)

enum class Conn { DISCONNECTED, CONNECTING, CONNECTED, RETRYING }

data class ObdState(
    val conn: Conn = Conn.DISCONNECTED,
    val status: String = "Not connected",
    /** Label to display value ("—" before the first read, "n/a" if the car doesn't report it). */
    val values: Map<String, String> = defaultMetrics().associate { it.label to "—" },
    val log: List<String> = emptyList(),
)

/** Owns the adapter connection and polling. Shared by the phone screen and the car screen. */
@SuppressLint("MissingPermission")
object ObdManager {
    private const val PREFS = "obd"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var transport: Transport? = null

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state

    data class Saved(val address: String, val name: String, val ble: Boolean)

    fun saved(ctx: Context): Saved? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = p.getString("address", null) ?: return null
        return Saved(address, p.getString("name", null) ?: address, p.getBoolean("ble", false))
    }

    fun save(ctx: Context, s: Saved) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("address", s.address).putString("name", s.name).putBoolean("ble", s.ble).apply()
    }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val isRunning get() = job?.isActive == true

    /** Connects to the saved adapter and keeps retrying until disconnect() is called. */
    fun connect(ctx: Context) {
        if (isRunning) return
        val saved = saved(ctx) ?: run { setStatus(Conn.DISCONNECTED, "Choose your adapter on the phone first"); return }
        if (!hasPermission(ctx)) { setStatus(Conn.DISCONNECTED, "Open OBD Auto on the phone to allow Bluetooth"); return }
        val app = ctx.applicationContext
        job = scope.launch {
            while (isActive) {
                try {
                    session(app, saved)
                } catch (e: TimeoutCancellationException) {
                    log("Timed out waiting for the adapter")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Error: ${e.message ?: e.javaClass.simpleName}")
                }
                setStatus(Conn.RETRYING, "Lost ${saved.name}, retrying…")
                delay(5_000)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        transport?.close()
        transport = null
        setStatus(Conn.DISCONNECTED, "Not connected")
    }

    private suspend fun session(ctx: Context, saved: Saved) {
        setStatus(Conn.CONNECTING, "Connecting to ${saved.name}…")
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(saved.address)
        val elm = Elm327()
        val t = if (saved.ble) BleTransport(ctx, device, elm::onData) else ClassicTransport(device, elm::onData)
        transport = t
        elm.transport = t
        try {
            poll(t, elm, saved)
        } finally {
            t.close()
        }
    }

    private suspend fun poll(t: Transport, elm: Elm327, saved: Saved) {
        t.open()
        log("Link open (${if (saved.ble) "BLE" else "Classic"}), initialising")

        for ((cmd, timeout) in listOf(
            "ATZ" to 5_000L, "ATE0" to 2_000L, "ATL0" to 2_000L, "ATS0" to 2_000L,
            "ATH0" to 2_000L, "ATAT1" to 2_000L, "ATSP0" to 2_000L,
        )) {
            val r = elm.send(cmd, timeout)
            log("$cmd → ${Elm327.lines(r).joinToString(" ")}")
        }
        log("0100 → ${Elm327.lines(elm.send("0100", 10_000)).joinToString(" ")}")
        log("Protocol ${Elm327.lines(elm.send("ATDPN")).joinToString(" ")}")
        setStatus(Conn.CONNECTED, "Connected to ${saved.name}")

        val metrics = defaultMetrics()
        var cycle = 0
        while (coroutineContext.isActive) {
            if (!t.isOpen) throw IOException("Connection to adapter closed")
            for (m in metrics) {
                if (m.skip) continue
                if (m.pid == null) {
                    // Voltage changes slowly, so read it every fifth cycle.
                    if (cycle % 5 != 0) continue
                    try {
                        val v = Elm327.lines(elm.send("ATRV")).firstOrNull()?.trimEnd('V', 'v')?.toDoubleOrNull()
                        if (v != null) setValue(m.label, format(v, m))
                    } catch (ignored: TimeoutCancellationException) {}
                    continue
                }
                val bytes = try {
                    Elm327.parsePid(elm.send("01" + m.pid, 1_500), m.pid, m.n)
                } catch (ignored: TimeoutCancellationException) { null }
                if (bytes != null) {
                    m.fails = 0
                    setValue(m.label, format(m.f(bytes), m))
                } else if (++m.fails >= 3) {
                    m.skip = true
                    setValue(m.label, "n/a")
                    log("PID ${m.pid} (${m.label}) unsupported, skipping")
                }
            }
            cycle++
        }
    }

    private fun format(v: Double, m: Metric) = "%.${m.decimals}f %s".format(Locale.US, v, m.unit)

    private fun setValue(label: String, value: String) =
        _state.update { it.copy(values = it.values + (label to value)) }

    private fun setStatus(conn: Conn, status: String) {
        _state.update {
            val values = if (conn == Conn.CONNECTED) it.values else it.values.mapValues { "—" }
            it.copy(conn = conn, status = status, values = values)
        }
        log(status)
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        _state.update { it.copy(log = (it.log + line).takeLast(60)) }
    }
}
