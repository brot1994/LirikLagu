package id.bram.liriklagu

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tJudul: TextView
    private lateinit var tArtis: TextView
    private lateinit var tStatus: TextView
    private lateinit var tPrev: TextView
    private lateinit var tNow: TextView
    private lateinit var tNext1: TextView
    private lateinit var tNext2: TextView
    private lateinit var kotakSinkron: LinearLayout
    private lateinit var gulirBiasa: ScrollView
    private lateinit var tBiasa: TextView
    private lateinit var bKenali: Button
    private lateinit var bOtomatis: Button
    private lateinit var tGeser: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var indeksTerakhir = -2
    private var laguTerakhir: Lagu? = null
    private var autoTerakhir = 0L

    private val KUNING = Color.parseColor("#FFC83D")
    private val REDUP = Color.parseColor("#77FFFFFF")
    private val LATAR = Color.parseColor("#0E0E14")
    private val KARTU = Color.parseColor("#1B1B26")

    private val dengar: () -> Unit = { segarkan(paksa = true) }

    private val detak = object : Runnable {
        override fun run() {
            segarkan(paksa = false)
            cekOtomatis()
            handler.postDelayed(this, 100)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(bangunTampilan())
        terapkanUkuran()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Mesin.pref(this).getBoolean("notifDitanya", false)
        ) {
            Mesin.pref(this).edit().putBoolean("notifDitanya", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }

    override fun onResume() {
        super.onResume()
        Status.tambahPendengar(dengar)
        segarkan(paksa = true)
        handler.post(detak)
    }

    override fun onPause() {
        super.onPause()
        Status.hapusPendengar(dengar)
        handler.removeCallbacks(detak)
    }

    // ---------------- tampilan ----------------

    private fun tombol(teks: String, latar: Int = KARTU, warnaTeks: Int = Color.WHITE): Button =
        Button(this).apply {
            text = teks
            isAllCaps = false
            setTextColor(warnaTeks)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(latar) }
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            stateListAnimator = null
        }

    private fun baris(teks: String = "", warna: Int = REDUP): TextView = TextView(this).apply {
        text = teks
        setTextColor(warna)
        gravity = Gravity.CENTER
        setPadding(dpi(16), dpi(6), dpi(16), dpi(6))
    }

    private fun bangunTampilan(): View {
        val akar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LATAR)
            setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
        }

        // kepala
        val kepala = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val kolomJudul = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tJudul = TextView(this).apply {
            text = "Lirik Lagu"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        }
        tArtis = TextView(this).apply { setTextColor(REDUP); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); maxLines = 1 }
        kolomJudul.addView(tJudul); kolomJudul.addView(tArtis)
        kepala.addView(kolomJudul, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bSet = tombol("⚙").apply { setOnClickListener { bukaPengaturan() } }
        kepala.addView(bSet, LinearLayout.LayoutParams(dpi(52), dpi(52)))
        akar.addView(kepala)

        // area lirik
        val area = FrameLayout(this)
        kotakSinkron = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        tPrev = baris(); tNow = baris(warna = KUNING).apply { typeface = Typeface.DEFAULT_BOLD }
        tNext1 = baris(); tNext2 = baris()
        listOf(tPrev, tNow, tNext1, tNext2).forEach { kotakSinkron.addView(it) }
        // ketuk baris berikutnya = lompat ke baris itu (sinkron manual)
        tNext1.setOnClickListener { lompatKe(indeksTerakhir + 1) }
        tPrev.setOnClickListener { lompatKe(indeksTerakhir - 1) }
        area.addView(kotakSinkron, FrameLayout.LayoutParams(-1, -1))

        tBiasa = TextView(this).apply {
            setTextColor(Color.WHITE); setLineSpacing(dp(4f), 1f); gravity = Gravity.CENTER
            setPadding(dpi(8), dpi(8), dpi(8), dpi(40))
        }
        gulirBiasa = ScrollView(this).apply { addView(tBiasa); visibility = View.GONE }
        area.addView(gulirBiasa, FrameLayout.LayoutParams(-1, -1))
        akar.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))

        tStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#BBFFFFFF")); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dpi(8), 0, dpi(8))
        }
        akar.addView(tStatus)

        // koreksi waktu
        val barisGeser = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val bMundur = tombol("−0,5 dtk").apply { setOnClickListener { geser(-500) } }
        tGeser = TextView(this).apply { setTextColor(REDUP); gravity = Gravity.CENTER; minWidth = dpi(90) }
        val bMaju = tombol("+0,5 dtk").apply { setOnClickListener { geser(500) } }
        barisGeser.addView(bMundur); barisGeser.addView(tGeser); barisGeser.addView(bMaju)
        akar.addView(barisGeser, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(10) })

        bKenali = tombol("🎤  Kenali Lagu", KUNING, Color.BLACK).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { mulaiKenali() }
        }
        akar.addView(bKenali, LinearLayout.LayoutParams(-1, dpi(64)))

        val barisBawah = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bMelayang = tombol("🪟  Subtitle melayang").apply { setOnClickListener { mulaiMelayang() } }
        bOtomatis = tombol("").apply {
            setOnClickListener {
                val p = Mesin.pref(this@MainActivity)
                p.edit().putBoolean("otomatis", !p.getBoolean("otomatis", true)).apply()
                segarkanTombolOtomatis()
            }
        }
        barisBawah.addView(bMelayang, LinearLayout.LayoutParams(0, dpi(52), 1f).apply { marginEnd = dpi(8) })
        barisBawah.addView(bOtomatis, LinearLayout.LayoutParams(0, dpi(52), 1f))
        akar.addView(barisBawah, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
        segarkanTombolOtomatis()
        return akar
    }

    private fun segarkanTombolOtomatis() {
        val on = Mesin.pref(this).getBoolean("otomatis", true)
        bOtomatis.text = if (on) "🔁  Lagu berikut: OTOMATIS" else "🔁  Lagu berikut: manual"
        bOtomatis.setTextColor(if (on) KUNING else Color.WHITE)
    }

    private fun terapkanUkuran() {
        val u = Mesin.pref(this).getInt("ukuran", 1)
        val besar = floatArrayOf(24f, 30f, 38f)[u]
        val kecil = floatArrayOf(17f, 21f, 26f)[u]
        tNow.setTextSize(TypedValue.COMPLEX_UNIT_SP, besar)
        listOf(tPrev, tNext1, tNext2).forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, kecil) }
        tBiasa.setTextSize(TypedValue.COMPLEX_UNIT_SP, kecil)
    }

    // ---------------- logika ----------------

    private fun segarkan(paksa: Boolean) {
        val lagu = Status.lagu
        if (paksa || lagu !== laguTerakhir) {
            laguTerakhir = lagu
            indeksTerakhir = -2
            tJudul.text = lagu?.judul ?: "Lirik Lagu"
            tArtis.text = lagu?.let { listOf(it.artis, it.album).filter { s -> s.isNotBlank() }.joinToString(" • ") } ?: ""
            tStatus.text = Status.pesan
            tStatus.visibility = if (Status.pesan.isBlank()) View.GONE else View.VISIBLE
            bKenali.isEnabled = !Status.sibuk
            bKenali.alpha = if (Status.sibuk) 0.5f else 1f
            bKenali.text = if (Status.sibuk) "⏳  Sedang memproses…" else "🎤  Kenali Lagu"
            val sinkron = lagu?.adaSinkron == true
            kotakSinkron.visibility = if (lagu == null || sinkron) View.VISIBLE else View.GONE
            gulirBiasa.visibility = if (lagu != null && !sinkron) View.VISIBLE else View.GONE
            tBiasa.text = lagu?.lirikBiasa ?: ""
            if (lagu == null) {
                tPrev.text = ""; tNow.text = "♪"; tNext1.text = "Putar lagu, lalu tekan Kenali Lagu"; tNext2.text = ""
            }
        }
        tGeser.text = "geser %+.1f dtk".format(Status.geserMs / 1000f)
        if (lagu == null || !lagu.adaSinkron) return

        val i = Status.indeksSekarang()
        if (i == indeksTerakhir) return
        indeksTerakhir = i
        val b = lagu.baris
        fun teks(k: Int) = if (k in b.indices) b[k].teks.ifBlank { "♪" } else ""
        tPrev.text = teks(i - 1)
        tNow.text = if (i < 0) "♪ ♪ ♪" else teks(i)
        tNext1.text = teks(i + 1)
        tNext2.text = teks(i + 2)
    }

    private fun lompatKe(k: Int) {
        val lagu = Status.lagu ?: return
        if (k !in lagu.baris.indices) return
        Status.geserMs += lagu.baris[k].waktuMs - Status.posisiSekarang() + 50
        indeksTerakhir = -2
        Status.kabari()
    }

    private fun geser(ms: Long) {
        Status.geserMs += ms
        indeksTerakhir = -2
        Status.kabari()
    }

    private fun cekOtomatis() {
        if (!Mesin.pref(this).getBoolean("otomatis", true)) return
        if (OverlayService.berjalan) return   // ditangani oleh subtitle melayang
        if (Status.sibuk || !Status.laguSelesai()) return
        val now = SystemClock.elapsedRealtime()
        if (now - autoTerakhir < 25_000) return
        autoTerakhir = now
        if (punyaIzinMic()) Mesin.kenali(this)
    }

    private fun punyaIzinMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun mulaiKenali() {
        if (!punyaIzinMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        Mesin.kenali(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            1 -> if (ok) Mesin.kenali(this) else Toast.makeText(this, "Izin mikrofon diperlukan untuk mengenali lagu", Toast.LENGTH_LONG).show()
            2 -> if (ok) mulaiMelayang()
        }
    }

    private fun mulaiMelayang() {
        if (!punyaIzinMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan \"Tampil di atas app lain\" untuk Lirik Lagu, lalu kembali dan tekan lagi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

    private fun bukaPengaturan() {
        val p = Mesin.pref(this)
        val isi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        isi.addView(TextView(this).apply {
            text = "Token AudD (pengenal lagu)\nDaftar gratis di dashboard.audd.io lalu salin API token-nya ke sini. Boleh dikosongkan untuk mencoba (jatah terbatas)."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        val eToken = EditText(this).apply {
            setText(p.getString("token", "")); hint = "tempel token di sini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        isi.addView(eToken)
        isi.addView(TextView(this).apply {
            text = "\nUkuran teks lirik"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        val grup = RadioGroup(this)
        listOf("Sedang", "Besar", "Sangat besar").forEachIndexed { i, s ->
            grup.addView(RadioButton(this).apply { text = s; id = 100 + i })
        }
        grup.check(100 + p.getInt("ukuran", 1))
        isi.addView(grup)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setView(ScrollView(this).apply { addView(isi) })
            .setPositiveButton("Simpan") { _, _ ->
                p.edit()
                    .putString("token", eToken.text.toString().trim())
                    .putInt("ukuran", (grup.checkedRadioButtonId - 100).coerceIn(0, 2))
                    .apply()
                terapkanUkuran()
                Status.kabari()
            }
            .setNeutralButton("Buka audd.io") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dashboard.audd.io/")))
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
