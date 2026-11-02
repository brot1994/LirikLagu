package id.bram.liriklagu

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        @Volatile var berjalan = false
        private const val KANAL = "melayang"
        private const val AKSI_TUTUP = "id.bram.liriklagu.TUTUP"
    }

    private lateinit var wm: WindowManager
    private var kotak: LinearLayout? = null
    private lateinit var tNow: TextView
    private lateinit var tNext: TextView
    private lateinit var tInfo: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var indeksTerakhir = -2
    private var laguTerakhir: Lagu? = null
    private var autoTerakhir = 0L

    private val KUNING = Color.parseColor("#FFC83D")

    private val dengar: () -> Unit = { segarkan(true) }
    private val detak = object : Runnable {
        override fun run() {
            segarkan(false)
            cekOtomatis()
            handler.postDelayed(this, 100)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AKSI_TUTUP) { stopSelf(); return START_NOT_STICKY }
        mulaiDepan()
        if (kotak == null) pasangJendela()
        berjalan = true
        return START_NOT_STICKY
    }

    private fun mulaiDepan() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "Subtitle melayang", NotificationManager.IMPORTANCE_LOW))
        val buka = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val tutup = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(AKSI_TUTUP), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, KANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Lirik Lagu — subtitle melayang aktif")
            .setContentText("Ketuk untuk membuka app")
            .setContentIntent(buka)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Tutup subtitle", tutup
            ).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(7, notif)
        }
    }

    private fun tombolKecil(teks: String, aksi: () -> Unit) = TextView(this).apply {
        text = teks
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
        background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(Color.parseColor("#33FFFFFF")) }
        setOnClickListener { aksi() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pasangJendela() {
        wm = getSystemService(WindowManager::class.java)
        val ukuran = Mesin.pref(this).getInt("ukuran", 1)

        val k = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(Color.parseColor("#CC000000")) }
        }
        tInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#AAFFFFFF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER; maxLines = 2
        }
        tNow = TextView(this).apply {
            setTextColor(KUNING); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, floatArrayOf(20f, 24f, 30f)[ukuran])
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        tNext = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF")); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, floatArrayOf(15f, 18f, 22f)[ukuran])
        }
        val barisTombol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val jarak = { v: View -> LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(4); marginEnd = dpi(4) }.also { barisTombol.addView(v, it) } }
        jarak(tombolKecil("🎤") { Mesin.kenali(this) })
        jarak(tombolKecil("−0,5") { Status.geserMs -= 500; indeksTerakhir = -2; Status.kabari() })
        jarak(tombolKecil("+0,5") { Status.geserMs += 500; indeksTerakhir = -2; Status.kabari() })
        jarak(tombolKecil("📱") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        jarak(tombolKecil("✕") { stopSelf() })

        k.addView(tInfo); k.addView(tNow); k.addView(tNext)
        k.addView(barisTombol, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6) })

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Mesin.pref(this@OverlayService).getInt("posisiY", dpi(90))
            horizontalMargin = 0.03f
        }

        // seret naik-turun
        var awalY = 0; var awalSentuh = 0f
        k.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { awalY = lp.y; awalSentuh = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = (awalY - (e.rawY - awalSentuh)).toInt().coerceAtLeast(0)
                    wm.updateViewLayout(k, lp); true
                }
                MotionEvent.ACTION_UP -> {
                    Mesin.pref(this).edit().putInt("posisiY", lp.y).apply(); false
                }
                else -> false
            }
        }
        wm.addView(k, lp)
        kotak = k
        Status.tambahPendengar(dengar)
        segarkan(true)
        handler.post(detak)
    }

    private fun segarkan(paksa: Boolean) {
        if (kotak == null) return
        val lagu = Status.lagu
        if (paksa || lagu !== laguTerakhir) {
            laguTerakhir = lagu
            indeksTerakhir = -2
            val info = when {
                Status.sibuk || lagu == null -> Status.pesan
                else -> "${lagu.judul} — ${lagu.artis}" + if (Status.pesan.isNotBlank()) "\n${Status.pesan}" else ""
            }
            tInfo.text = info
            tInfo.visibility = if (info.isBlank()) View.GONE else View.VISIBLE
            if (lagu == null || !lagu.adaSinkron) {
                tNow.text = if (lagu == null) "♪" else "Lirik berjalan tidak tersedia"
                tNext.text = if (lagu == null) "Tekan 🎤 saat lagu diputar" else "Buka app (📱) untuk lirik lengkap"
                return
            }
        }
        if (lagu == null || !lagu.adaSinkron) return
        val i = Status.indeksSekarang()
        if (i == indeksTerakhir) return
        indeksTerakhir = i
        val b = lagu.baris
        fun teks(n: Int) = if (n in b.indices) b[n].teks.ifBlank { "♪" } else ""
        tNow.text = if (i < 0) "♪ ♪ ♪" else teks(i)
        tNext.text = teks(i + 1)
    }

    private fun cekOtomatis() {
        if (!Mesin.pref(this).getBoolean("otomatis", true)) return
        if (Status.sibuk || !Status.laguSelesai()) return
        val now = SystemClock.elapsedRealtime()
        if (now - autoTerakhir < 25_000) return
        autoTerakhir = now
        Mesin.kenali(this)
    }

    override fun onDestroy() {
        berjalan = false
        handler.removeCallbacks(detak)
        Status.hapusPendengar(dengar)
        kotak?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        kotak = null
        super.onDestroy()
    }
}
