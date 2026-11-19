package br.com.fitlauncher.px5

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Fit Launcher PX5 v0.7: original immersive cockpit UI for Android 9 / 1024x600.
 * The Rota APK was consulted to understand the concept; no artwork or source code
 * from that third-party app is included. All live data displays only real readings.
 * Remains an ordinary Activity until the user has tested the PX5/MCU integrations.
 */
class MainActivity : Activity(), LocationListener {
    private enum class Screen { HOME, GARAGE, COCKPIT }
    private var screen = Screen.HOME
    private var accentTheme = 0
    private val accents = intArrayOf(Color.rgb(36, 201, 245), Color.rgb(255, 66, 75), Color.rgb(255, 171, 52))
    private val muted = Color.rgb(146, 165, 186)
    private val soft = Color.rgb(203, 218, 230)
    private var speedGps: Int? = null
    private var gpsState = "GPS aguardando sinal"
    private var lastLocationAt = 0L
    private lateinit var locationManager: LocationManager
    private lateinit var display: CockpitView
    private var connectedDeviceName: String? = null
    private var reading = Elm327Client.Reading("OBD2 desconectado")
    private var isRobot = false
    private var robotProgress = 0f
    private var carImage: Bitmap? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timer = object : Runnable {
        override fun run() {
            if (::display.isInitialized && !isFinishing && !isDestroyed) {
                val next = if (isRobot) 1f else 0f
                robotProgress += (next - robotProgress) * 0.18f
                display.invalidate()
                uiHandler.postDelayed(this, 75)
            }
        }
    }
    private val elm = Elm327Client { newReading ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                reading = newReading
                if (::display.isInitialized) display.invalidate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        accentTheme = getPreferences(MODE_PRIVATE).getInt("accent", 0).coerceIn(0,2)
        carImage = BitmapFactory.decodeResource(resources, R.drawable.honda_fit_black)
        display = CockpitView()
        setContentView(display)
        uiHandler.post(timer)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGps()
        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
    }
    override fun onPause() {
        locationManager.removeUpdates(this)
        super.onPause()
    }
    override fun onDestroy() {
        uiHandler.removeCallbacks(timer)
        elm.disconnect()
        locationManager.removeUpdates(this)
        carImage?.recycle()
        carImage = null
        super.onDestroy()
    }
    override fun onLocationChanged(location: Location) {
        lastLocationAt = System.currentTimeMillis()
        speedGps = if (location.hasSpeed()) max(0, (location.speed*3.6f).roundToInt()) else null
        gpsState = if (location.hasSpeed()) "GPS conectado" else "GPS sem velocidade"
        display.invalidate()
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 101) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
            else gpsState = "Permissão GPS não concedida"
        }
    }
    @Suppress("MissingPermission")
    private fun startGps() {
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsState = "Ative o GPS da central"
            } else {
                gpsState = "Aguardando satélites GPS"
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            }
        } catch (_: Exception) { gpsState = "GPS indisponível" }
    }

    private fun openApp(vararg packageIds: String) {
        for (id in packageIds) {
            val launch = packageManager.getLaunchIntentForPackage(id)
            if (launch != null) {
                try { startActivity(launch); return } catch (_: Exception) { }
            }
        }
        toast("Aplicativo não encontrado. Use APPS para escolher.")
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun showApps(filter: String? = null) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(i, 0)
            .filter { filter == null || it.loadLabel(packageManager).toString().contains(filter, true) }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        if (apps.isEmpty()) { toast("Nenhum aplicativo encontrado"); return }
        AlertDialog.Builder(this).setTitle(if (filter == null) "Aplicativos da PX5" else "Aplicativos: $filter")
            .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                val a = apps[index].activityInfo
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(a.packageName, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { toast("Não foi possível abrir o aplicativo") }
            }.setNegativeButton("Voltar", null).show()
    }
    @Suppress("MissingPermission")
    private fun connectObd() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { toast("Esta central não tem Bluetooth disponível"); return }
            if (!adapter.isEnabled) {
                toast("Ative o Bluetooth da central para conectar o ELM327")
                return
            }
            val list = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
            if (list.isEmpty()) { toast("Pareie seu ELM327 no Android antes de conectar"); return }
            AlertDialog.Builder(this).setTitle("Conectar ELM327 • Bluetooth")
                .setMessage("Feche o Car Scanner antes de conectar: só um app pode utilizar o ELM327 de cada vez.")
                .setItems(list.map { "${it.name ?: "Dispositivo"}  (${it.address})" }.toTypedArray()) { _, index ->
                    connectedDeviceName = list[index].name ?: "ELM327"
                    elm.connect(list[index])
                }.setNeutralButton("Desconectar") { _, _ ->
                    elm.disconnect(); connectedDeviceName = null
                    reading = Elm327Client.Reading("OBD2 desconectado")
                    display.invalidate()
                }.setNegativeButton("Cancelar", null).show()
        } catch (_: SecurityException) { toast("Permissão Bluetooth negada") }
    }
    private fun action(key: String) {
        when (key) {
            "home" -> screen = Screen.HOME
            "garage" -> screen = Screen.GARAGE
            "cockpit" -> screen = Screen.COCKPIT
            "transform" -> { screen = Screen.HOME; isRobot = !isRobot }
            "nav" -> openApp("com.waze", "com.google.android.apps.maps")
            "maps" -> openApp("com.google.android.apps.maps", "com.waze")
            "music" -> openApp("com.spotify.music", "com.google.android.music", "com.maxmpz.audioplayer")
            "radio" -> showApps("rádio")
            "bt" -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "video" -> openApp("com.google.android.youtube", "org.videolan.vlc")
            "scanner" -> openApp("com.ovz.carscanner")
            "apps" -> showApps()
            "obd" -> connectObd()
            "settings" -> startActivity(Intent(Settings.ACTION_SETTINGS))
            "exit" -> finish()
            "accent0", "accent1", "accent2" -> {
                accentTheme = key.last().toString().toInt()
                getPreferences(MODE_PRIVATE).edit().putInt("accent", accentTheme).apply()
            }
        }
        display.invalidate()
    }

    private inner class CockpitView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val time = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        private val date = SimpleDateFormat("EEE, dd MMM yyyy", Locale("pt", "BR"))
        private data class Hotspot(val x: Float, val y: Float, val w: Float, val h: Float, val key: String)
        private val touches = mutableListOf<Hotspot>()
        private var pressX = -1f
        private var pressY = -1f
        private val cyan get() = accents[accentTheme]
        private fun tint(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0,255), Color.red(color), Color.green(color), Color.blue(color))
        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false,
                         align: Paint.Align = Paint.Align.LEFT, italic: Boolean = false) {
            p.reset(); p.isAntiAlias = true; p.color = color; p.textSize = size; p.textAlign = align
            p.typeface = when {
                italic -> Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
                bold -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
            c.drawText(s,x,y,p)
        }
        private fun rect(c: Canvas, x: Float,y: Float,w: Float,h: Float,
                         fill: Int, border: Int? = null, radius: Float = 15f, borderWidth: Float = 1.2f) {
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = fill
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p)
            if (border != null) {
                p.style = Paint.Style.STROKE; p.color = border; p.strokeWidth = borderWidth
                c.drawRoundRect(x+.6f,y+.6f,x+w-.6f,y+h-.6f,radius,radius,p)
                p.style = Paint.Style.FILL
            }
        }
        private fun line(c: Canvas, x: Float,y: Float,x2: Float,y2: Float,color: Int,width: Float=1f) {
            p.reset();p.isAntiAlias=true;p.color=color;p.strokeWidth=width
            c.drawLine(x,y,x2,y2,p)
        }
        private fun hotspot(x: Float,y: Float,w: Float,h: Float,key:String) { touches.add(Hotspot(x,y,w,h,key)) }
        private fun gradient(c: Canvas, x: Float,y: Float,w: Float,h: Float, from: Int, to: Int, radius: Float=16f) {
            p.reset();p.isAntiAlias=true;p.shader=LinearGradient(x,y,x+w,y+h,from,to,Shader.TileMode.CLAMP)
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p); p.shader=null
        }
        private fun section(c: Canvas,x: Float,y: Float,w: Float,h: Float) {
            gradient(c,x,y,w,h,Color.rgb(14,22,35),Color.rgb(7,12,21))
            rect(c,x,y,w,h,Color.TRANSPARENT,tint(cyan,90),16f,1.2f)
        }
        override fun onDraw(realCanvas: Canvas) {
            super.onDraw(realCanvas)
            if (width<=0 || height<=0) return
            val c = realCanvas
            c.save();c.scale(width/1024f,height/600f)
            touches.clear()
            background(c)
            header(c)
            navigation(c)
            when(screen) {
                Screen.HOME -> vehicle(c, false)
                Screen.GARAGE -> vehicle(c, true)
                Screen.COCKPIT -> cockpit(c)
            }
            telemetry(c)
            dock(c)
            c.restore()
        }
        private fun background(c: Canvas) {
            p.reset();p.shader=LinearGradient(0f,0f,1024f,600f,
                intArrayOf(Color.rgb(8,15,27),Color.rgb(3,8,17),Color.rgb(16,17,25)),null,Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,1024f,600f,p);p.shader=null
            p.color=tint(cyan,17);c.drawCircle(570f,320f,310f,p)
            p.color=tint(cyan,13);c.drawCircle(840f,120f,220f,p)
            for(i in 0..17) {
                val x=180f+i*57
                line(c,x,600f,510f+(x-510f)*.35f,372f,tint(cyan,14))
            }
            for(j in 0..10) { val y=390f+j*j*2.7f;line(c,170f,y,1024f,y,tint(cyan,12)) }
        }
        private fun header(c: Canvas) {
            gradient(c,0f,0f,1024f,67f,Color.rgb(6,12,23),Color.rgb(14,19,30),0f)
            line(c,0f,66f,1024f,66f,tint(cyan,130),1.8f)
            rect(c,23f,16f,38f,36f,tint(cyan,38),tint(cyan,180),9f)
            text(c,"F",42f,43f,25f,Color.WHITE,true,Paint.Align.CENTER,true)
            text(c,"FIT",75f,34f,22f,Color.WHITE,true)
            text(c,"LAUNCHER PX5",119f,34f,22f,cyan,true)
            text(c,"BLACK EDITION  •  HONDA FIT 2010",77f,51f,10f,muted,true)
            val now=Date()
            text(c,date.format(now).uppercase(Locale("pt","BR")),749f,27f,12f,muted,false,Paint.Align.RIGHT)
            text(c,time.format(now),994f,46f,33f,Color.WHITE,true,Paint.Align.RIGHT)
            text(c, if(connectedDeviceName == null) "●  OBD2 OFFLINE" else "●  ELM327", 755f,50f,11f,
                if(reading.rpm != null) Color.rgb(76,225,132) else muted,true,Paint.Align.RIGHT)
            text(c,"GPS",813f,49f,11f,if(speedGps!=null) cyan else muted,true)
        }
        private fun navigation(c: Canvas) {
            section(c,18f,81f,180f,407f)
            text(c,"ATALHOS",39f,109f,13f,soft,true)
            line(c,35f,122f,181f,122f,tint(cyan,65))
            val data=listOf(Triple("▸","NAVEGAR","nav"),Triple("♫","MÚSICA","music"),
                Triple("◉","RÁDIO","radio"),Triple("⌁","BLUETOOTH","bt"),
                Triple("▦","APLICATIVOS","apps"),Triple("◇","GARAGEM","garage"),Triple("◷","COCKPIT","cockpit"))
            data.forEachIndexed { idx,it ->
                val y=133f+idx*47.5f
                val chosen=(it.third=="garage" && screen==Screen.GARAGE) || (it.third=="cockpit" && screen==Screen.COCKPIT)
                rect(c,28f,y,160f,42f,if(chosen) tint(cyan,48) else Color.rgb(17,28,41),
                    if(chosen) tint(cyan,190) else tint(cyan,45),12f)
                rect(c,36f,y+8f,27f,27f,tint(cyan,35),tint(cyan,100),7f)
                text(c,it.first,49.5f,y+28f,18f,cyan,true,Paint.Align.CENTER)
                text(c,it.second,73f,y+27f,15f,Color.WHITE,true)
                hotspot(28f,y,160f,42f,it.third)
            }
        }
        private fun vehicle(c: Canvas, garage: Boolean) {
            // Center stage uses original, independent Fit artwork; never extracted assets.
            text(c,if(garage) "MINHA GARAGEM" else "HONDA  /  FIT",224f,107f,24f,Color.WHITE,true,italic=true)
            text(c,if(garage) "HONDA FIT 2010  •  PRETO" else "2010  •  BLACK EDITION  •  PX5",226f,125f,11f,cyan,true)
            line(c,222f,134f,717f,134f,tint(cyan,85))
            p.reset();p.isAntiAlias=true;p.color=tint(cyan,16)
            c.drawOval(248f,181f,696f,445f,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=tint(cyan,48)
            c.drawOval(255f,242f,688f,448f,p)
            c.drawOval(276f,290f,668f,444f,p)
            p.style=Paint.Style.FILL
            line(c,228f,398f,720f,398f,tint(cyan,35),2f)
            for(i in 0..7) {
                val xx=250+i*58f
                line(c,xx,427f,xx+26f,427f,tint(cyan,50),2f)
            }
            val bob=(sin(System.nanoTime()/1_000_000_000.0*1.4)*3.5).toFloat()
            val b=carImage
            if(b!=null && robotProgress<.99f) {
                p.reset();p.isAntiAlias=true;p.isFilterBitmap=true;p.alpha=(255*(1-robotProgress)).roundToInt().coerceIn(0,255)
                val bounds=RectF(221f+robotProgress*60,170f+bob+robotProgress*30,
                    726f-robotProgress*60,422f+bob-robotProgress*10)
                c.drawBitmap(b,null,bounds,p)
            }
            if(robotProgress>.02f) drawRobot(c, robotProgress)
            if(garage) {
                rect(c,248f,409f,464f,34f,Color.rgb(8,16,26),tint(cyan,100),9f)
                text(c,"MODELO 2010    •    COR PRETA    •    CENTRAL PX5",480f,430f,12f,Color.WHITE,true,Paint.Align.CENTER)
                for(i in 0..2) {
                    val x=267f+i*152f
                    rect(c,x,451f,144f,31f,tint(accents[i],25),tint(accents[i],120),8f)
                    text(c,arrayOf("TEMA AZUL","TEMA VERMELHO","TEMA ÂMBAR")[i],x+72f,471f,12f,
                        accents[i],true,Paint.Align.CENTER)
                    hotspot(x,451f,144f,31f,"accent$i")
                }
            } else {
                rect(c,238f,435f,174f,40f,Color.rgb(21,34,48),tint(cyan,160),12f)
                text(c,if(isRobot) "↶  VOLTAR AO FIT" else "◇  TRANSFORMAR",325f,461f,15f,cyan,true,Paint.Align.CENTER)
                hotspot(238f,435f,174f,40f,"transform")
                rect(c,425f,435f,136f,40f,tint(cyan,30),tint(cyan,100),12f)
                text(c,"GARAGEM",493f,461f,15f,Color.WHITE,true,Paint.Align.CENTER)
                hotspot(425f,435f,136f,40f,"garage")
                rect(c,574f,435f,134f,40f,tint(cyan,30),tint(cyan,100),12f)
                text(c,"COCKPIT",641f,461f,15f,Color.WHITE,true,Paint.Align.CENTER)
                hotspot(574f,435f,134f,40f,"cockpit")
            }
        }
        private fun polygon(c: Canvas, color: Int, stroke: Int, pts: FloatArray) {
            val path=Path();path.moveTo(pts[0],pts[1]);var i=2
            while(i<pts.size) { path.lineTo(pts[i],pts[i+1]);i+=2 };path.close()
            p.reset();p.isAntiAlias=true;p.color=color;p.style=Paint.Style.FILL;c.drawPath(path,p)
            p.color=stroke;p.style=Paint.Style.STROKE;p.strokeWidth=2.5f;c.drawPath(path,p);p.style=Paint.Style.FILL
        }
        private fun drawRobot(c: Canvas, fraction: Float) {
            c.save();c.translate(486f,303f);c.scale(.66f+.34f*fraction,.66f+.34f*fraction)
            val graphite=Color.rgb(27,38,51);val metal=Color.rgb(69,87,99)
            val glow=tint(cyan,(235*fraction).roundToInt())
            p.reset();p.color=tint(cyan,(35*fraction).roundToInt());c.drawCircle(0f,14f,152f,p)
            polygon(c,metal,glow,floatArrayOf(-42f,4f,-62f,54f,-32f,92f,26f,92f,58f,50f,35f,4f))
            polygon(c,graphite,glow,floatArrayOf(-32f,-66f,26f,-66f,34f,-9f,-34f,-9f))
            polygon(c,metal,glow,floatArrayOf(-24f,-106f,22f,-106f,35f,-64f,-29f,-64f))
            polygon(c,graphite,glow,floatArrayOf(-106f,-43f,-52f,-22f,-68f,57f,-113f,72f,-134f,18f))
            polygon(c,graphite,glow,floatArrayOf(104f,-43f,52f,-22f,69f,57f,114f,72f,134f,18f))
            polygon(c,graphite,glow,floatArrayOf(-58f,85f,-5f,85f,-13f,172f,-57f,172f))
            polygon(c,graphite,glow,floatArrayOf(6f,85f,59f,85f,57f,172f,15f,172f))
            polygon(c,metal,glow,floatArrayOf(-67f,164f,-14f,164f,-10f,190f,-78f,190f))
            polygon(c,metal,glow,floatArrayOf(15f,164f,66f,164f,76f,190f,11f,190f))
            rect(c,-33f,-46f,65f,28f,Color.rgb(7,15,23),tint(cyan,190),8f)
            text(c,"FIT",0f,-26f,21f,glow,true,Paint.Align.CENTER,true)
            rect(c,-23f,-87f,47f,13f,tint(cyan,200),null,4f)
            for(x in listOf(-91f,91f)) {
                p.color=Color.rgb(12,17,23);c.drawCircle(x,18f,19f,p)
                p.style=Paint.Style.STROKE;p.color=glow;p.strokeWidth=4f;c.drawCircle(x,18f,18f,p);p.style=Paint.Style.FILL
            }
            c.restore()
        }
        private fun gauge(c: Canvas,cx:Float,cy:Float,r:Float,value:Float?, maxVal:Float,label:String,unit:String) {
            p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeWidth=12f;p.strokeCap=Paint.Cap.ROUND
            p.color=Color.rgb(29,43,56);c.drawArc(cx-r,cy-r,cx+r,cy+r,135f,270f,false,p)
            if(value!=null) {
                p.color=cyan;c.drawArc(cx-r,cy-r,cx+r,cy+r,135f,270f*(value/maxVal).coerceIn(0f,1f),false,p)
            }
            p.style=Paint.Style.FILL
            text(c,value?.roundToInt()?.toString()?:"--",cx,cy+12f,55f,Color.WHITE,true,Paint.Align.CENTER)
            text(c,unit,cx,cy+35f,15f,cyan,true,Paint.Align.CENTER)
            text(c,label,cx,cy-r-22f,16f,soft,true,Paint.Align.CENTER)
        }
        private fun cockpit(c: Canvas) {
            text(c,"COCKPIT / TELEMETRIA",225f,107f,24f,Color.WHITE,true,true.let { Paint.Align.LEFT })
            text(c,"DADOS REAIS • ELM327 + GPS",225f,125f,11f,cyan,true)
            section(c,219f,145f,500f,326f)
            val nowSpeed = if(System.currentTimeMillis()-lastLocationAt<12000) speedGps else null
            gauge(c,344f,302f,88f,nowSpeed?.toFloat(),220f,"VELOCIDADE GPS","km/h")
            gauge(c,588f,302f,88f,reading.rpm?.toFloat(),8000f,"ROTAÇÃO MOTOR","rpm")
            line(c,472f,173f,472f,411f,tint(cyan,55))
            rect(c,248f,415f,442f,38f,Color.rgb(13,25,38),tint(cyan,85),10f)
            text(c,"TEMP.: ${reading.coolantC?.let { "$it °C" } ?: "--"}     CARGA: ${reading.engineLoad?.let { "$it%" } ?: "--"}     VOLT.: ${reading.ecuVoltage?.let { "%.1f V".format(Locale.US,it) } ?: "--"}",
                469f,439f,17f,Color.WHITE,true,Paint.Align.CENTER)
            hotspot(219f,145f,500f,326f,"obd")
        }
        private fun telemetry(c: Canvas) {
            section(c,735f,81f,271f,407f)
            text(c,"MONITOR  OBD2",751f,108f,18f,Color.WHITE,true)
            val active=reading.rpm!=null || reading.coolantC!=null || reading.vehicleKmh!=null
            rect(c,911f,90f,82f,24f,if(active) Color.rgb(19,62,39) else Color.rgb(52,28,35),
                if(active) Color.rgb(53,214,113) else Color.rgb(170,74,86),7f)
            text(c,if(active) "ON-LINE" else "OFFLINE",952f,106f,12f,
                if(active) Color.rgb(99,249,163) else Color.rgb(252,159,169),true,Paint.Align.CENTER)
            text(c,reading.state.take(34),753f,130f,11f,muted)
            line(c,752f,140f,989f,140f,tint(cyan,80))
            val sensors=listOf(
                Triple("RPM",reading.rpm?.let { "$it rpm" }?:"--",reading.rpm?.toFloat()?.div(8000f)),
                Triple("TEMP. MOTOR",reading.coolantC?.let { "$it °C" }?:"--",reading.coolantC?.toFloat()?.div(115f)),
                Triple("VELOCIDADE",reading.vehicleKmh?.let { "$it km/h" }?:"--",reading.vehicleKmh?.toFloat()?.div(180f)),
                Triple("CARGA MOTOR",reading.engineLoad?.let { "$it %" }?:"--",reading.engineLoad?.toFloat()?.div(100f)),
                Triple("TENSÃO ECU",reading.ecuVoltage?.let { "%.1f V".format(Locale.US,it) }?:"--",reading.ecuVoltage?.toFloat()?.div(16f)),
                Triple("TENSÃO ELM",reading.adapterVoltage?.let { "%.1f V".format(Locale.US,it) }?:"--",reading.adapterVoltage?.toFloat()?.div(16f))
            )
            sensors.forEachIndexed { i, item ->
                val y=149f+i*45f
                rect(c,750f,y,240f,40f,Color.rgb(13,22,34),tint(cyan,36),9f)
                text(c,item.first,762f,y+16f,12f,muted,true)
                text(c,item.second,977f,y+19f,18f,if(item.third!=null) Color.WHITE else Color.rgb(111,128,145),true,Paint.Align.RIGHT)
                rect(c,760f,y+28f,216f,4f,Color.rgb(35,49,63),null,2f)
                item.third?.let { percent -> rect(c,760f,y+28f,216f*percent.coerceIn(0f,1f),4f,cyan,null,2f) }
            }
            rect(c,750f,428f,240f,42f,tint(cyan,35),tint(cyan,135),10f)
            text(c,if(active) "GERENCIAR ELM327" else "CONECTAR ELM327",870f,455f,16f,Color.WHITE,true,Paint.Align.CENTER)
            hotspot(750f,428f,240f,42f,"obd")
        }
        private fun dock(c: Canvas) {
            section(c,18f,498f,988f,84f)
            val items=listOf(Triple("WAZE","➤","nav"),Triple("MAPAS","⌖","maps"),
                Triple("SPOTIFY","♫","music"),Triple("YOUTUBE","▶","video"),
                Triple("SCANNER","◉","scanner"),Triple("OBD2","▤","obd"),
                Triple("AJUSTES","⚙","settings"),Triple("SAIR","✕","exit"))
            items.forEachIndexed{idx,it->
                val x=25f+idx*122f
                rect(c,x,506f,114f,67f,Color.rgb(16,28,42),tint(cyan,45),12f)
                p.reset();p.isAntiAlias=true;p.shader=LinearGradient(x,505f,x+87,558f,
                    tint(cyan,90),tint(cyan,17),Shader.TileMode.CLAMP)
                c.drawRoundRect(x+37f,510f,x+77f,547f,11f,11f,p);p.shader=null
                text(c,it.second,x+57f,537f,24f,Color.WHITE,true,Paint.Align.CENTER)
                text(c,it.first,x+57f,565f,12f,Color.WHITE,true,Paint.Align.CENTER)
                hotspot(x,506f,114f,67f,it.third)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x=event.x*1024f/width.coerceAtLeast(1)
            val y=event.y*600f/height.coerceAtLeast(1)
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressX=x;pressY=y;return true }
                MotionEvent.ACTION_UP -> {
                    if(abs(x-pressX)<24f && abs(y-pressY)<24f) {
                        val item=touches.asReversed().firstOrNull { x>=it.x && x<=it.x+it.w && y>=it.y && y<=it.y+it.h }
                        if(item!=null) { performClick(); action(item.key) }
                    }
                    return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick();return true }
    }
}
