package id.bram.liriklagu

import android.os.SystemClock

data class BarisLirik(val waktuMs: Long, val teks: String)

data class Lagu(
    val judul: String,
    val artis: String,
    val album: String,
    val tautan: String,
    val baris: List<BarisLirik>,      // lirik tersinkron (bisa kosong)
    val lirikBiasa: String,           // lirik tanpa waktu (cadangan)
    val durasiMs: Long                // 0 = tidak diketahui
) {
    val adaSinkron get() = baris.isNotEmpty()
}

object Lrc {
    private val tag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun urai(lrc: String): List<BarisLirik> {
        val hasil = ArrayList<BarisLirik>()
        for (mentah in lrc.lines()) {
            val semua = tag.findAll(mentah).toList()
            if (semua.isEmpty()) continue
            val teks = mentah.substring(semua.last().range.last + 1).trim()
            for (m in semua) {
                val mnt = m.groupValues[1].toLong()
                val dtk = m.groupValues[2].toLong()
                val pecahan = m.groupValues[3]
                val ms = when (pecahan.length) {
                    0 -> 0L
                    1 -> pecahan.toLong() * 100
                    2 -> pecahan.toLong() * 10
                    else -> pecahan.take(3).toLong()
                }
                hasil.add(BarisLirik(mnt * 60_000 + dtk * 1000 + ms, teks))
            }
        }
        hasil.sortBy { it.waktuMs }
        return hasil
    }
}

/**
 * Status bersama antara layar utama dan subtitle melayang.
 */
object Status {
    @Volatile var lagu: Lagu? = null
    // posisi lagu (ms) pada saat jangkar, dan waktu jangkar (elapsedRealtime)
    @Volatile var posJangkarMs: Long = 0
    @Volatile var waktuJangkar: Long = 0
    @Volatile var geserMs: Long = 0          // koreksi manual pengguna
    @Volatile var pesan: String = "Tekan tombol 🎤 saat lagu sedang diputar"
    @Volatile var sibuk: Boolean = false

    private val pendengar = mutableSetOf<() -> Unit>()

    fun posisiSekarang(): Long =
        posJangkarMs + (SystemClock.elapsedRealtime() - waktuJangkar) + geserMs

    /** indeks baris yang sedang dinyanyikan, -1 bila belum mulai */
    fun indeksSekarang(): Int {
        val l = lagu ?: return -1
        if (!l.adaSinkron) return -1
        val pos = posisiSekarang()
        var lo = 0
        var hi = l.baris.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (l.baris[mid].waktuMs <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** true bila lagu kemungkinan besar sudah selesai */
    fun laguSelesai(): Boolean {
        val l = lagu ?: return false
        val pos = posisiSekarang()
        val akhir = when {
            l.durasiMs > 0 -> l.durasiMs + 3_000
            l.adaSinkron -> l.baris.last().waktuMs + 20_000
            else -> return false
        }
        return pos > akhir
    }

    fun tambahPendengar(f: () -> Unit) = synchronized(pendengar) { pendengar.add(f) }
    fun hapusPendengar(f: () -> Unit) = synchronized(pendengar) { pendengar.remove(f) }
    fun kabari() {
        val salinan = synchronized(pendengar) { pendengar.toList() }
        salinan.forEach { it() }
    }
}
