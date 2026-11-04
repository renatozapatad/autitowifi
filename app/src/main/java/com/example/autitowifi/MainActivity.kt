package com.example.autitowifi

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var ip: EditText
    private lateinit var estado: TextView
    private lateinit var nivel: TextView
    private lateinit var ultimaOrden: TextView
    private val botones = mutableListOf<Button>()
    private val red = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var comando = "detener"
    @Volatile private var velocidad = 100
    @Volatile private var conectado = false
    @Volatile private var direccion = ""
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<ScrollView>(R.id.scrollPrincipal)
            .setOnApplyWindowInsetsListener { vista, margen ->
                vista.setPadding(margen.systemWindowInsetLeft, margen.systemWindowInsetTop,
                    margen.systemWindowInsetRight, margen.systemWindowInsetBottom)
                margen
            }
        ip = findViewById(R.id.edtIp)
        estado = findViewById(R.id.txtEstado)
        nivel = findViewById(R.id.txtVelocidad)
        ultimaOrden = findViewById(R.id.txtUltimaOrden)
        findViewById<Button>(R.id.btnAbrirWifi).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<Button>(R.id.btnProbarConexion).setOnClickListener { probarConexion() }

        findViewById<Button>(R.id.btnDetener).setOnClickListener { detener() }

        configurarBoton(R.id.btnAvanzar, "avanzar")
        configurarBoton(R.id.btnRetroceder, "retroceder")
        configurarBoton(R.id.btnIzquierda, "rotarIzquierda")
        configurarBoton(R.id.btnDerecha, "rotarDerecha")
        configurarBoton(R.id.btnCurvaIzquierda, "girarIzquierda")
        configurarBoton(R.id.btnCurvaDerecha, "girarDerecha")
        configurarBoton(R.id.btnProbarIzquierda, "motorIzquierdo")
        configurarBoton(R.id.btnProbarDerecha, "motorDerecho")
        findViewById<SeekBar>(R.id.seekVelocidad).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(barra: SeekBar?, valor: Int, usuario: Boolean) {
                    velocidad = valor + 20
                    nivel.text = "Velocidad $velocidad %"
                }
                override fun onStartTrackingTouch(barra: SeekBar?) {}
                override fun onStopTrackingTouch(barra: SeekBar?) {}
            })
        red.scheduleWithFixedDelay({
            if (conectado) enviarComando(comando)
        }, 0, 150, TimeUnit.MILLISECONDS)
    }

    private fun configurarBoton(id: Int, orden: String) {
        val boton = findViewById<Button>(id)
        botones.add(boton)
        boton.setOnClickListener { detener() }
        boton.setOnTouchListener { vista, evento ->
            when (evento.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (conectado) comando = orden
                    ultimaOrden.text = "Orden: $orden"
                    vista.isPressed = true
                    vista.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (evento.x < 0 || evento.y < 0 ||
                        evento.x > vista.width || evento.y > vista.height) detener()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    detener()
                    vista.parent.requestDisallowInterceptTouchEvent(false)
                    if (evento.actionMasked == MotionEvent.ACTION_UP) vista.performClick()
                }
            }
            true
        }
    }

    private fun detener() {
        comando = "detener"
        botones.forEach { it.isPressed = false }
        if (::ultimaOrden.isInitialized) ultimaOrden.text = "Orden: Detenido"
    }

    private fun habilitarBotones(valor: Boolean) {
        botones.forEach { it.isEnabled = valor }
    }

    private fun probarConexion() {
        val permiso = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT >= 37 && applicationInfo.targetSdkVersion >= 37 &&
            checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permiso), 1)
            estado.text = "Autoriza red local y pulsa Probar conexion nuevamente."
            return
        }
        detener()
        conectado = false
        habilitarBotones(false)
        direccion = "http://${ip.text.toString().trim()}"
        estado.text = "Probando conexion..."
        red.execute {
            try {
                check(pedir("/estado").trim() == "OK")
                runOnUiThread {
                    if (visible) {
                        conectado = true
                        habilitarBotones(true)
                        estado.text = "Conectado al autito."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { estado.text = "Sin conexion: ${e.message}" }
            }
        }
    }

    private fun enviarComando(orden: String) {
        try {
            val respuesta = pedir("/$orden?velocidad=$velocidad")
            check(respuesta.trim() == "OK")
        } catch (e: Exception) {
            conectado = false
            comando = "detener"
            runOnUiThread {
                detener()
                habilitarBotones(false)
                estado.text = "Conexion perdida. Pulsa Probar conexion."
            }
        }
    }

    private fun pedir(ruta: String): String {
        val gestor = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = gestor.allNetworks.firstOrNull {
            gestor.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: error("Conecta el celular al WiFi del auto")
        val conexion = wifi.openConnection(URL(direccion + ruta)) as HttpURLConnection
        return try {
            conexion.requestMethod = "GET"
            conexion.connectTimeout = 200
            conexion.readTimeout = 200
            conexion.useCaches = false
            conexion.setRequestProperty("Connection", "close")
            check(conexion.responseCode == 200) { "HTTP ${conexion.responseCode}" }
            conexion.inputStream.bufferedReader().use { it.readText() }
        } finally { conexion.disconnect() }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        conectado = false
        detener(); habilitarBotones(false)
        estado.text = "Pulsa Probar conexion antes de conducir."
    }

    override fun onPause() {
        detener()
        conectado = false
        visible = false
        if (direccion.isNotEmpty()) red.execute {
            try { pedir("/detener") } catch (_: Exception) {}
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) detener()
    }

    override fun dispatchTouchEvent(evento: MotionEvent): Boolean {
        if (evento.pointerCount > 1) { detener(); return true }
        return super.dispatchTouchEvent(evento)
    }

    override fun onDestroy() {
        red.shutdown()
        super.onDestroy()
    }
}
