package com.example.socialblocker

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Sess(val pkg: String, val start: Long, val end: Long)

object Store {
    val BLOCKED = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana"
    )
    private val zone = ZoneId.systemDefault()
    private fun p(c: Context) = c.getSharedPreferences("sb", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis()
    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // --- stima iniziale (punto 0) ---
    fun estimateH(c: Context) = p(c).getInt("estimateH", -1)
    fun setEstimate(c: Context, h: Int) {
        p(c).edit().putInt("estimateH", h).putLong("lastEnd", now()).putLong("startMs", now())
            .putString("lastDay", LocalDate.now().toString()).apply()
    }
    fun lastDay(c: Context) = p(c).getString("lastDay", LocalDate.now().toString())!!
    fun setLastDay(c: Context, d: String) = p(c).edit().putString("lastDay", d).apply()

    // --- sessione attiva (punto 1) ---
    fun sessionActive(c: Context) = p(c).getLong("sStart", 0) > 0
    fun sessionStart(c: Context) = p(c).getLong("sStart", 0)
    fun sessionPkg(c: Context) = p(c).getString("sPkg", "") ?: ""

    fun startSession(c: Context, pkg: String) {
        val s = p(c)
        val gap = if (lastEnd(c) > 0) now() - lastEnd(c) else 0L
        s.edit().putLong("awayClosed", s.getLong("awayClosed", 0) + gap)
            .putLong("sStart", now()).putString("sPkg", pkg).putLong("sNext", now() + TIMEOUT_MS).apply()
    }

    fun endSession(c: Context) {
        if (!sessionActive(c)) return
        val s = p(c)
        val line = "${sessionPkg(c)},${sessionStart(c)},${now()}\n"
        s.edit().putString("sessions", (s.getString("sessions", "") ?: "") + line)
            .putLong("sStart", 0).putLong("sNext", 0).putLong("lastEnd", now()).apply()
    }

    // --- seconda barriera dopo 15 minuti continuati ---
    // Per i test su telefono: metti 60_000L a entrambi (15 min -> 1 min, 1 ora -> 1 min)
    const val TIMEOUT_MS = 15 * 60 * 1000L
    const val HOUR_MS = 3_600_000L
    fun nextCheck(c: Context) = p(c).getLong("sNext", 0)
    fun extendCheck(c: Context) = p(c).edit().putLong("sNext", nextCheck(c) + TIMEOUT_MS).apply()

    /** Tempo sprecato oggi, sessione in corso compresa. */
    fun wastedToday(c: Context): Long =
        totalMs(c, LocalDate.now()) + (if (sessionActive(c)) now() - sessionStart(c) else 0L)

    // --- inattivita (punto 4) ---
    fun lastEnd(c: Context) = p(c).getLong("lastEnd", 0)
    fun sinceLastMs(c: Context) = if (lastEnd(c) == 0L) 0L else now() - lastEnd(c)
    /** Ore totali accumulate lontano dai social (per i rewards). */
    fun awayMs(c: Context): Long =
        p(c).getLong("awayClosed", 0) + (if (sessionActive(c)) 0L else sinceLastMs(c))

    /** Giorni dall'inizio del percorso (rewards cumulativi). */
    fun journeyDays(c: Context): Long {
        val s = p(c).getLong("startMs", 0)
        return if (s == 0L) 0L else (now() - s) / 86400000L
    }
    fun notifiedH(c: Context) = p(c).getLong("notifiedH", 0)
    fun setNotifiedH(c: Context, h: Long) = p(c).edit().putLong("notifiedH", h).apply()

    // --- diagnostica (mostrata nella schermata principale) ---
    fun setDebug(c: Context, t: String) = p(c).edit().putString("dbg", t).apply()
    fun debug(c: Context): String = p(c).getString("dbg", "nessun evento ricevuto") ?: ""
    fun setError(c: Context, t: String) = p(c).edit().putString("err", "${Fmt.clock(System.currentTimeMillis())} $t").apply()
    fun error(c: Context): String = p(c).getString("err", "") ?: ""

    /** Registro delle ultime osservazioni del servizio (si aggiunge solo quando cambia qualcosa). */
    fun log(c: Context, line: String) {
        val s = p(c)
        val old = (s.getString("log", "") ?: "").lines().filter { it.isNotBlank() }
        val n = (old + "${Fmt.clockSec(System.currentTimeMillis())} $line").takeLast(14)
        s.edit().putString("log", n.joinToString("\n")).apply()
    }
    fun logText(c: Context): String = p(c).getString("log", "") ?: ""
    fun setCrash(c: Context, t: String) { p(c).edit().putString("crash", t).commit() }
    fun crash(c: Context): String = p(c).getString("crash", "") ?: ""
    fun setPollInfo(c: Context, t: String) = p(c).edit().putString("poll", t).apply()
    fun pollInfo(c: Context): String = p(c).getString("poll", "Controlli: nessuno") ?: ""
    fun clearDiag(c: Context) {
        endSession(c)
        p(c).edit().remove("log").remove("crash").remove("err").remove("poll").apply()
    }

    // --- rinunce ---
    fun blocks(c: Context) = p(c).getInt("blocks", 0)
    fun addBlock(c: Context) = p(c).edit().putInt("blocks", blocks(c) + 1).apply()

    // --- sessioni passate (punto 3) ---
    fun sessions(c: Context): List<Sess> =
        (p(c).getString("sessions", "") ?: "").lines().filter { it.isNotBlank() }.map {
            val a = it.split(","); Sess(a[0], a[1].toLong(), a[2].toLong())
        }
    fun forDay(c: Context, d: LocalDate) = sessions(c).filter { day(it.start) == d }
    fun totalMs(c: Context, d: LocalDate) = forDay(c, d).sumOf { it.end - it.start }
    fun dayTotals(c: Context): Map<LocalDate, Long> =
        sessions(c).groupBy { day(it.start) }.mapValues { e -> e.value.sumOf { it.end - it.start } }

    // --- traguardi sbloccati: righe "id<TAB>titolo<TAB>messaggio" ---
    fun unlocked(c: Context): List<List<String>> =
        (p(c).getString("unl", "") ?: "").lines().filter { it.isNotBlank() }.map { it.split("\t") }
    fun has(c: Context, id: String) = unlocked(c).any { it[0] == id }
    fun addUnlocked(c: Context, id: String, title: String, msg: String) =
        p(c).edit().putString("unl", (p(c).getString("unl", "") ?: "") + "$id\t$title\t$msg\n").apply()
}

object Fmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
    fun dur(ms: Long): String {
        val m = ms / 60000
        return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
    }
    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
    fun clockSec(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hms)
    /** "2 ore e 5 minuti" */
    fun long(ms: Long): String {
        val m = ms / 60000
        val h = m / 60
        val mm = m % 60
        val hs = if (h == 1L) "1 ora" else "$h ore"
        val mins = if (mm == 1L) "1 minuto" else "$mm minuti"
        return if (h == 0L) mins else if (mm == 0L) hs else "$hs e $mins"
    }

    /** Righe finali del report: tempo sprecato e tempo guadagnato rispetto alla stima. */
    fun tail(c: Context, wasted: Long): String {
        val gained = maxOf(0L, Store.estimateH(c) * 3600000L - wasted)
        return "❌ Tempo sprecato sui social: ${long(wasted)}\n✅ Tempo guadagnato per te: ${long(gained)}"
    }

    fun report(c: Context, d: LocalDate): String {
        val l = Store.forDay(c, d)
        if (l.isEmpty()) return "Nessuna sessione.\n${tail(c, 0)}"
        val sb = StringBuilder()
        l.forEachIndexed { i, s -> sb.append("Sessione ${i + 1}: ${clock(s.start)} - ${clock(s.end)} (${dur(s.end - s.start)})\n") }
        sb.append(tail(c, Store.totalMs(c, d)))
        return sb.toString()
    }
}

/** Salva il motivo di un crash prima che il processo muoia: lo si legge nella schermata principale. */
object CrashLog {
    private var installed = false
    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Store.setCrash(app, "${Fmt.clockSec(System.currentTimeMillis())} ${e.javaClass.simpleName}: ${e.message} @ " +
                    e.stackTrace.take(3).joinToString(" < "))
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}
