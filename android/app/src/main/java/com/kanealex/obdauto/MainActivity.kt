package com.kanealex.obdauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Phone screen: pick the adapter, connect, and see the same readings the car shows. */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var status: TextView
    private lateinit var adapterLabel: TextView
    private lateinit var toggle: Button
    private lateinit var values: LinearLayout
    private lateinit var logView: TextView

    private val fg = Color.parseColor("#F8FAFC")
    private val muted = Color.parseColor("#94A3B8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (40 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        fun text(size: Float, color: Int = fg) = TextView(this).apply { textSize = size; setTextColor(color) }

        root.addView(text(24f).apply { text = "OBD Auto"; typeface = Typeface.DEFAULT_BOLD })
        root.addView(text(13f, muted).apply { text = "Shows on Android Auto. Your web dashboard is separate and unchanged." })
        status = text(16f).apply { setPadding(0, (12 * dp).toInt(), 0, 0) }
        root.addView(status)
        adapterLabel = text(14f, muted)
        root.addView(adapterLabel)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Choose adapter"; setOnClickListener { chooseAdapter() } })
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        buttons.addView(toggle)
        root.addView(buttons)

        values = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(values)

        root.addView(text(13f, muted).apply { text = "Log"; setPadding(0, (16 * dp).toInt(), 0, 0) })
        logView = text(11f, muted).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#0F172A")) })

        requestPermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            1,
        )

        scope.launch { ObdManager.state.collect { render(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(s: ObdState) {
        status.text = s.status
        adapterLabel.text = ObdManager.saved(this)?.let { "Adapter: ${it.name} (${if (it.ble) "BLE" else "Classic"})" }
            ?: "No adapter chosen yet"
        toggle.text = if (ObdManager.isRunning) "Disconnect" else "Connect"
        values.removeAllViews()
        for ((label, value) in s.values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 16f; setTextColor(muted)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = value; textSize = 22f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
            })
            values.addView(row)
        }
        logView.text = s.log.takeLast(25).joinToString("\n")
    }

    private fun onToggle() {
        if (ObdManager.isRunning) {
            ObdManager.disconnect()
        } else if (!ObdManager.hasPermission(this)) {
            ObdManager.log("Bluetooth permission is needed; allow it and try again")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
        } else {
            ObdService.start(this)
        }
    }

    private fun bluetooth() = getSystemService(BluetoothManager::class.java)?.adapter

    /** Paired devices use Bluetooth Classic. "Scan for BLE" finds adapters that only speak BLE. */
    private fun chooseAdapter() {
        val bt = bluetooth()
        if (bt == null || !ObdManager.hasPermission(this)) {
            ObdManager.log("Allow Bluetooth access first")
            return
        }
        val paired = bt.bondedDevices.orEmpty().sortedBy { it.name ?: "" }
        val labels = paired.map { "${it.name ?: "Unknown"}  ·  ${it.address}" } + "Scan for BLE adapters…"
        AlertDialog.Builder(this)
            .setTitle("Paired devices")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < paired.size) {
                    val d = paired[i]
                    pick(d, ble = d.type == BluetoothDevice.DEVICE_TYPE_LE)
                } else {
                    scanBle()
                }
            }
            .show()
    }

    private fun scanBle() {
        val scanner = bluetooth()?.bluetoothLeScanner ?: return
        val found = LinkedHashMap<String, BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name != null) found[result.device.address] = result.device
            }
        }
        ObdManager.log("Scanning for BLE adapters…")
        scanner.startScan(callback)
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            val devices = found.values.toList()
            if (devices.isEmpty()) {
                ObdManager.log("No BLE devices found. Is the ignition on?")
                return@postDelayed
            }
            AlertDialog.Builder(this)
                .setTitle("BLE devices")
                .setItems(devices.map { "${it.name}  ·  ${it.address}" }.toTypedArray()) { _, i -> pick(devices[i], ble = true) }
                .show()
        }, 6_000)
    }

    private fun pick(d: BluetoothDevice, ble: Boolean) {
        ObdManager.disconnect()
        ObdManager.save(this, ObdManager.Saved(d.address, d.name ?: d.address, ble))
        ObdService.start(this)
    }
}
