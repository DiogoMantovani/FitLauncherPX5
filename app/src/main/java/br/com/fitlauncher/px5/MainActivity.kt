package br.com.fitlauncher.px5

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Independent Android 9 prototype. Does not replace the system HOME application. */
class MainActivity : Activity(), LocationListener {
    private lateinit var speed: TextView
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var locationManager: LocationManager
    private lateinit var obdStatus: TextView
    private lateinit var obdValues: TextView
    private val elm = Elm327Client { reading -> runOnUiThread {
        if (!isFinishing && !isDestroyed) {
            obdStatus.text = reading.state
            obdValues.text = "RPM: ${reading.rpm ?: "--"}   Temp.: ${reading.coolantC?.let { "$it °C" } ?: "--"}   " +
                "ECU: ${reading.vehicleKmh?.let { "$it km/h" } ?: "--"}   " +
                "Carga: ${reading.engineLoad?.let { "$it%" } ?: "--"}   " +
                "ECU V: ${reading.ecuVoltage?.let { "%.1f V".format(Locale.US, it) } ?: "--"}   " +
                "ELM V: ${reading.adapterVoltage?.let { "%.1f V".format(Locale.US, it) } ?: "--"}"
        }
    } }
    private val handler = Handler(Looper.getMainLooper())
    private val clockUpdate = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 12, 22, 14)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 15, 26), Color.rgb(20, 37, 55)))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("FIT  /  LAUNCHER", 23f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, -2, 1f))
        clock = label("--:--", 25f, Color.WHITE)
        header.addView(clock)
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val vehicle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        vehicle.addView(label("HONDA FIT 2010", 26f, Color.WHITE))
        vehicle.addView(label("PRETO  •  PX5", 15f, Color.rgb(156, 185, 207)))
        vehicle.addView(ImageView(this).apply {
            setImageResource(R.drawable.fit_silhouette)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Silhueta original estilizada de um hatch preto"
        }, LinearLayout.LayoutParams(-1, 190))
        vehicle.addView(label("Arte provisória • animação em desenvolvimento", 12f, Color.LTGRAY))
        body.addView(vehicle, LinearLayout.LayoutParams(0, -1, 2f))

        val dashboard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        speed = label("--", 76f, Color.WHITE).apply { gravity = Gravity.CENTER }
        dashboard.addView(speed)
        dashboard.addView(label("km/h  •  GPS", 17f, Color.rgb(106, 203, 231)).apply { gravity = Gravity.CENTER })
        status = label("Aguardando localização", 13f, Color.LTGRAY).apply { gravity = Gravity.CENTER; setPadding(0, 12, 0, 0) }
        dashboard.addView(status)
        body.addView(dashboard, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        obdStatus = label("OBD2 desconectado • feche o Car Scanner antes de conectar", 14f, Color.rgb(106, 203, 231))
        obdValues = label("RPM: --   Temp.: --   ECU: --   Carga: --   ECU V: --   ELM V: --", 14f, Color.WHITE)
        root.addView(obdStatus)
        root.addView(obdValues)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addButton(actions, "WAZE") { openApp("com.waze") }
        addButton(actions, "MAPAS") { openApp("com.google.android.apps.maps") }
        addButton(actions, "SPOTIFY") { openApp("com.spotify.music") }
        addButton(actions, "APPS") { showApps() }
        addButton(actions, "OBD2") { selectObd() }
        addButton(actions, "AJUSTES") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        addButton(actions, "SAIR") { finish() }
        root.addView(actions)
        setContentView(root)
        handler.post(clockUpdate)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        } else startGps()
    }

    override fun onPause() {
        locationManager.removeUpdates(this)
        super.onPause()
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }

    private fun addButton(row: LinearLayout, value: String, action: () -> Unit) {
        row.addView(Button(this).apply {
            text = value; textSize = 12f; isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, 58, 1f).apply { marginEnd = 5 })
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent) else Toast.makeText(this, "Aplicativo não encontrado", Toast.LENGTH_SHORT).show()
    }

    @Suppress("MissingPermission")
    private fun selectObd() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { obdStatus.text = "Bluetooth indisponível"; return }
            if (!adapter.isEnabled) { obdStatus.text = "Ative o Bluetooth nas configurações"; return }
            val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                obdStatus.text = "Pareie o ELM327 nas configurações Bluetooth"
                return
            }
            android.app.AlertDialog.Builder(this).setTitle("Selecionar OBD2 pareado")
                .setItems(devices.map { "${it.name ?: "Dispositivo"} (${it.address})" }.toTypedArray()) { _, index ->
                    elm.connect(devices[index])
                }.setNeutralButton("Desconectar") { _, _ ->
                    elm.disconnect(); obdStatus.text = "OBD2 desconectado"
                    obdValues.text = "RPM: --   Temp.: --   ECU: --   Carga: --   ECU V: --   ELM V: --"
                }.setNegativeButton("Cancelar", null).show()
        } catch (e: SecurityException) { obdStatus.text = "Permissão Bluetooth negada" }
    }

    private fun showApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        val names = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Aplicativos instalados")
            .setItems(names) { _, index ->
                val info = apps[index].activityInfo
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.packageName, info.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(launch) } catch (_: Exception) {
                    Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Fechar", null).show()
    }

    @Suppress("MissingPermission")
    private fun startGps() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                status.text = "Procurando sinal GPS"
            } else status.text = "GPS desativado"
        } catch (_: Exception) { status.text = "GPS indisponível" }
    }

    override fun onLocationChanged(location: Location) {
        speed.text = if (location.hasSpeed()) (location.speed * 3.6f).toInt().coerceAtLeast(0).toString() else "--"
        status.text = if (location.hasSpeed()) "Velocidade GPS" else "GPS sem velocidade válida"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
        else status.text = "Permita localização para usar GPS"
    }

    override fun onDestroy() {
        elm.disconnect()
        handler.removeCallbacks(clockUpdate)
        locationManager.removeUpdates(this)
        super.onDestroy()
    }
}
