package id.bram.liriklagu

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Mesin {
    private const val RATE = 44100
    private const val DETIK_REKAM = 8
    private const val UA = "LirikLagu/1.0 (https://github.com/brot1994/LirikLagu)"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val utama = Handler(Looper.getMainLooper())

    private fun kabari(pesan: String? = null) {
        if (pesan != null) Status.pesan = pesan
        utama.post { Status.kabari() }
    }

    fun pref(ctx: Context) = ctx.getSharedPreferences("lirik", Context.MODE_PRIVATE)

    /** Dengarkan, kenali lagu, lalu ambil liriknya. */
    fun kenali(ctx: Context) {
        if (Status.sibuk) return
        Status.sibuk = true
        val app = ctx.applicationContext
        kabari("🎧 Mendengarkan… dekatkan HP ke sumber suara")
        scope.launch {
            try {
                val mulai = SystemClock.elapsedRealtime()
                val wav = rekam()
                if (wav == null) {
                    kabari("Mikrofon tidak bisa dipakai. Cek izin mikrofon atau app lain yang sedang memakai mikrofon.")
                    return@launch
                }
                kabari("🔎 Mengenali lagu…")
                val token = pref(app).getString("token", "")!!.trim()
                val hasil = kirimAudd(wav, token)
                if (hasil.error != null) { kabari(hasil.error); return@launch }
                if (hasil.judul.isEmpty()) {
                    kabari("Lagu tidak dikenali. Coba lagi saat bagian lagu yang lebih jelas / lebih keras.")
                    return@launch
                }
                kabari("🎵 ${hasil.judul} — ${hasil.artis}\nMencari lirik…")
                val lagu = cariLirik(hasil)
                Status.lagu = lagu
                Status.geserMs = pref(app).getLong("geserDefault", 0L)
                Status.posJangkarMs = hasil.timecodeMs
                Status.waktuJangkar = mulai
                kabari(
                    when {
                        lagu.adaSinkron -> ""
                        lagu.lirikBiasa.isNotBlank() -> "Lirik berjalan tidak tersedia, ditampilkan lirik lengkap."
                        else -> "Lagu dikenali, tetapi liriknya belum ada di database."
                    }
                )
            } catch (e: Exception) {
                kabari("Gagal: ${e.message ?: e.javaClass.simpleName}. Cek koneksi internet.")
            } finally {
                Status.sibuk = false
                kabari()
            }
        }
    }

    // ---------------- rekam ----------------

    @SuppressLint("MissingPermission")
    private fun rekam(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
        } catch (e: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }

        val total = RATE * 2 * DETIK_REKAM
        val pcm = ByteArray(total)
        var terisi = 0
        try {
            rec.startRecording()
            while (terisi < total) {
                val n = rec.read(pcm, terisi, minOf(minBuf, total - terisi))
                if (n < 0) break
                terisi += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (terisi < RATE) return null
        return keWav(pcm, terisi)
    }

    private fun keWav(pcm: ByteArray, len: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + len); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(len)
        val out = ByteArrayOutputStream(44 + len)
        out.write(h.array()); out.write(pcm, 0, len)
        return out.toByteArray()
    }

    // ---------------- AudD ----------------

    private class HasilAudd(
        val judul: String = "", val artis: String = "", val album: String = "",
        val tautan: String = "", val timecodeMs: Long = 0, val error: String? = null
    )

    private fun kirimAudd(wav: ByteArray, token: String): HasilAudd {
        val batas = "----LirikLagu" + System.currentTimeMillis()
        val conn = URL("https://api.audd.io/").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$batas")
        conn.setRequestProperty("User-Agent", UA)
        DataOutputStream(conn.outputStream).use { o ->
            fun field(nama: String, nilai: String) {
                o.writeBytes("--$batas\r\nContent-Disposition: form-data; name=\"$nama\"\r\n\r\n")
                o.write(nilai.toByteArray(Charsets.UTF_8)); o.writeBytes("\r\n")
            }
            if (token.isNotEmpty()) field("api_token", token)
            o.writeBytes("--$batas\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rekaman.wav\"\r\n")
            o.writeBytes("Content-Type: audio/wav\r\n\r\n")
            o.write(wav); o.writeBytes("\r\n--$batas--\r\n")
        }
        val kode = conn.responseCode
        val teks = (if (kode in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        val js = try { JSONObject(teks) } catch (e: Exception) {
            return HasilAudd(error = "Server pengenal lagu membalas tidak normal (kode $kode).")
        }
        if (js.optString("status") != "success") {
            val err = js.optJSONObject("error")
            val kodeErr = err?.optInt("error_code") ?: 0
            val msg = err?.optString("error_message") ?: "tidak diketahui"
            val saran = when (kodeErr) {
                900, 901, 902 -> "\nJatah gratis tanpa token habis atau token salah. Isi token AudD di ⚙ Pengaturan."
                else -> ""
            }
            return HasilAudd(error = "AudD: $msg$saran")
        }
        val r = js.optJSONObject("result") ?: return HasilAudd()
        return HasilAudd(
            judul = r.optString("title"),
            artis = r.optString("artist"),
            album = r.optString("album"),
            tautan = r.optString("song_link"),
            timecodeMs = waktuKeMs(r.optString("timecode"))
        )
    }

    private fun waktuKeMs(s: String): Long {
        if (s.isBlank()) return 0
        var total = 0L
        for (bagian in s.split(":")) total = total * 60 + (bagian.trim().toDoubleOrNull() ?: 0.0).toLong()
        return total * 1000
    }

    // ---------------- LRCLIB ----------------

    private fun ambil(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        val kode = c.responseCode
        val isi = (if (kode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return kode to isi
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun bersihkan(judul: String) = judul
        .replace(Regex("""\s*[(\[](feat|ft|with|remaster|live|from|official)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mix).*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun artisUtama(a: String) = a.split(Regex("""\s*(,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE))
        .firstOrNull()?.trim().orEmpty()

    private fun cariLirik(h: HasilAudd): Lagu {
        val judul = bersihkan(h.judul)
        val artis = artisUtama(h.artis)
        var terbaik: JSONObject? = null

        fun pertimbangkan(o: JSONObject?) {
            if (o == null || o.optBoolean("instrumental")) return
            val sinkron = !o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()
            val lama = terbaik
            val lamaSinkron = lama != null && !lama.isNull("syncedLyrics") && lama.optString("syncedLyrics").isNotBlank()
            if (lama == null || (sinkron && !lamaSinkron)) terbaik = o
        }

        try {
            val (k, isi) = ambil("https://lrclib.net/api/get?track_name=${enc(judul)}&artist_name=${enc(artis)}")
            if (k == 200) pertimbangkan(JSONObject(isi))
        } catch (_: Exception) {}

        fun cari(url: String) {
            try {
                val (k, isi) = ambil(url)
                if (k != 200) return
                val arr = JSONArray(isi)
                for (i in 0 until minOf(arr.length(), 10)) pertimbangkan(arr.getJSONObject(i))
            } catch (_: Exception) {}
        }
        val sudahSinkron = { terbaik?.let { !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() } == true }
        if (!sudahSinkron()) cari("https://lrclib.net/api/search?track_name=${enc(judul)}&artist_name=${enc(artis)}")
        if (!sudahSinkron()) cari("https://lrclib.net/api/search?q=${enc("$judul $artis")}")

        val t = terbaik
        val sinkron = if (t != null && !t.isNull("syncedLyrics")) t.optString("syncedLyrics") else ""
        val biasa = if (t != null && !t.isNull("plainLyrics")) t.optString("plainLyrics") else ""
        val durasi = ((t?.optDouble("duration", 0.0) ?: 0.0) * 1000).toLong()
        return Lagu(h.judul, h.artis, h.album, h.tautan, Lrc.urai(sinkron), biasa, durasi)
    }
}
