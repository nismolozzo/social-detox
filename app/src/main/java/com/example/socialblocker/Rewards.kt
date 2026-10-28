package com.example.socialblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.time.LocalDate

object Notifier {
    fun post(c: Context, title: String, text: String) {
        try { postUnsafe(c, title, text) }
        catch (e: Throwable) { Store.setError(c, "notifica: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun postUnsafe(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rw", "Traguardi", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(c, "rw")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).build()
        nm.notify((System.nanoTime() % 100000).toInt(), n)
    }
}

object Rewards {
    private val deterrents = listOf(
        "Stai scrollando per piacere o per abitudine?",
        "Il feed sarà ancora lì domani. La tua vita di oggi, no.",
        "Davvero ti serve adesso?",
        "Respira. Poi decidi.",
        "Ogni minuto qui è un minuto che non torna."
    )
    fun deterrent() = deterrents.random()

    fun award(c: Context, id: String, title: String, msg: String) {
        if (Store.has(c, id)) return
        Store.addUnlocked(c, id, title, msg)
        Notifier.post(c, title, msg)
    }

    fun welcome(c: Context) = award(c, "welcome", "Benvenuto nel percorso",
        "Complimenti. Ammettere di avere un problema è il 50% della soluzione. Hai appena acceso la prima luce in un percorso che cambierà le tue giornate.")

    fun firstBlock(c: Context) = award(c, "first_block", "Prima resistenza",
        "Hai appena resistito alla prima spinta automatica. Hai scelto tu, non l’algoritmo. Questo è il primo mattone della tua libertà.")

    private val AWAY = listOf(6, 12, 24, 48, 72, 168)
    private val hourly = listOf(
        "Un’altra ora lontano dai social. Stai lottando, e stai vincendo.",
        "Un’ora in più di libertà. Il tuo cervello ti ringrazia.",
        "Un’altra ora tua. Ricorda perché hai iniziato.",
        "Ancora un’ora senza scroll. Stai riprendendo il controllo."
    )

    /** Somma cumulativa delle ore lontano dai social. */
    fun checkAway(c: Context) {
        if (Store.estimateH(c) < 0) return
        val h = Store.awayMs(c) / Store.HOUR_MS
        for (t in AWAY) if (h >= t) {
            when (t) {
                6 -> award(c, "away_6", "6 ore libere", "Sono passate 6 ore lontano dai social. Il tuo cervello ti sta ringraziando.")
                12 -> award(c, "away_12", "12 ore libere", "12 ore di fila! Stai ritrovando la concentrazione profonda.")
                24 -> award(c, "away_24", "🏆 PRIMO GIORNO SENZA SOCIAL",
                    "24 ore di vita reale riconquistate. Hai sbloccato il badge: Pioniere del Presente. Festeggia questo traguardo facendo qualcosa che ami senza lo smartphone in tasca.")
                else -> award(c, "away_$t", "$t ore libere", "Hai accumulato $t ore lontano dai social. Continua così.")
            }
        }
        // Notifica ogni ora lontano dai social, con totale cumulativo e giorni di percorso
        if (h > Store.notifiedH(c)) {
            Store.setNotifiedH(c, h)
            if (h.toInt() !in AWAY) {
                val d = Store.journeyDays(c)
                val path = if (d < 1) "meno di un giorno di percorso" else "$d giorni di percorso"
                Notifier.post(c, "Ora $h lontano dai social",
                    "${hourly[(h % hourly.size).toInt()]} In totale: $h ore riconquistate, $path.")
            }
        }
    }
}

object Daily {
    /** Chiude il giorno precedente: report + complimenti se sotto stima e/o media. */
    fun check(c: Context) {
        if (Store.estimateH(c) < 0) return
        val last = LocalDate.parse(Store.lastDay(c))
        if (!last.isBefore(LocalDate.now())) return
        val total = Store.totalMs(c, last)
        val prev = Store.dayTotals(c).filterKeys { it.isBefore(last) && it.isAfter(last.minusDays(8)) }
            .values.filter { it > 0 }
        val avg = if (prev.isEmpty()) 0L else prev.average().toLong()
        val est = Store.estimateH(c)
        Store.setLastDay(c, LocalDate.now().toString())
        Notifier.post(c, "Ecco il resoconto della tua giornata digitale", "Tempo sprecato sui social: ${Fmt.long(total)}")
        when {
            total < est * 3600000L -> Rewards.award(c, "day_$last", "Previsione battuta",
                "Avevi stimato di passare $est ore sui social, ma oggi ti sei fermato a ${Fmt.dur(total)}. Hai battuto le tue stesse previsioni e ti sei regalato del tempo vero.")
            avg > 0 && total < avg -> Rewards.award(c, "day_$last", "Sotto la tua media",
                "Oggi sei stato sui social ${Fmt.dur(total)}, meno della tua media di ${Fmt.dur(avg)}. Stai migliorando.")
        }
    }
}
