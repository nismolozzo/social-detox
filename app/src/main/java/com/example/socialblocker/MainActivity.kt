package com.example.socialblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var info: TextView? = null
    private var hero: TextView? = null
    private var banner = ""
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CrashLog.install(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFFDCE8E0.toInt()); addView(root) }) // verde salvia
    }

    override fun onResume() {
        super.onResume()
        Daily.check(this); Rewards.checkAway(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        build()
        h.post(tick)
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun tv(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size; setPadding(0, 8, 0, 8); setTextColor(0xFF22312A.toInt()) }

    private fun build() {
        root.removeAllViews(); info = null; hero = null
        if (Store.estimateH(this) < 0) { onboarding(); return }
        if (banner.isNotEmpty()) { root.addView(tv(banner)); banner = "" }
        hero = tv("", 26f).apply { setPadding(0, 8, 0, 24) }
        root.addView(hero)
        info = tv("", 15f)
        root.addView(info)
        root.addView(Button(this).apply {
            text = "Attiva il blocco (Accessibilità)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Prova la schermata di conferma"
            setOnClickListener {
                val svc = BlockerService.instance
                if (svc == null) android.widget.Toast.makeText(this@MainActivity,
                    "Servizio NON attivo: attivalo in Accessibilità", android.widget.Toast.LENGTH_LONG).show()
                else svc.testGate()
            }
        })
        root.addView(Button(this).apply {
            text = "Azzera sessione e diagnostica"
            setOnClickListener { Store.clearDiag(this@MainActivity); refresh() }
        })
        root.addView(Button(this).apply {
            text = "Batteria: nessuna restrizione"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Avvio automatico (Xiaomi) / info app"
            setOnClickListener {
                try {
                    startActivity(Intent().setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
        })
        refresh()
    }

    private fun onboarding() {
        root.addView(tv("Prima di iniziare il nostro viaggio, facciamo i conti con la realtà.\n\n" +
            "Quante ore al giorno stimi di passare intrappolato nei social media?", 20f))
        listOf(1 to "1h", 2 to "2h", 3 to "3h", 4 to "4h+").forEach { (n, label) ->
            root.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    Store.setEstimate(this@MainActivity, n)
                    Rewards.welcome(this@MainActivity)
                    banner = "Perfetto. Registrato. Da questo momento in poi, lavoreremo insieme per riprenderci questo tempo.\n"
                    build()
                }
            })
        }
    }

    private fun refresh() {
        val t = info ?: return
        hero?.text = if (Store.sessionActive(this)) "⏱️ Sei dentro un social adesso"
            else "🌱 Tempo lontano dai social:\n${Fmt.long(Store.sinceLastMs(this))}"
        val today = LocalDate.now()
        val est = Store.estimateH(this)
        val sb = StringBuilder()
        val enabledList = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        sb.append(when {
            BlockerService.instance != null -> "Blocco: ATTIVO ✓\n"
            enabledList.contains("BlockerService") ->
                "Blocco: acceso in Accessibilità ma il sistema NON lo ha avviato.\n" +
                "→ spegni e riaccendi l'interruttore; poi usa i pulsanti Batteria e Avvio automatico qui sotto\n"
            else -> "Blocco: non attivo (abilitalo in Accessibilità)\n"
        })
        sb.append("Sessione social in corso: ${if (Store.sessionActive(this)) Store.sessionPkg(this) else "no"}\n")
        sb.append("${Store.pollInfo(this)}\n")
        Store.crash(this).let { if (it.isNotEmpty()) sb.append("ULTIMO CRASH: $it\n") }
        Store.error(this).let { if (it.isNotEmpty()) sb.append("Ultimo errore: $it\n") }
        sb.append("Registro (ultime osservazioni, la più recente in fondo):\n${Store.logText(this)}\n")
        sb.append("Stima iniziale: ${if (est >= 4) "4h+" else "${est}h"} al giorno\n")
        sb.append("Ore totali lontano dai social: ${Store.awayMs(this) / Store.HOUR_MS} (badge a 24)\n")
        sb.append("Rinunce: ${Store.blocks(this)}\n")
        sb.append("In cammino da: ${Store.journeyDays(this)} giorni\n\n")
        sb.append("Report di oggi:\n${Fmt.report(this, today)}\n\n")
        sb.append("Report di ieri:\n${Fmt.report(this, today.minusDays(1))}\n\n")
        sb.append("Traguardi:\n")
        val u = Store.unlocked(this)
        if (u.isEmpty()) sb.append("Ancora nessuno.\n")
        u.forEach { sb.append("🏆 ${it[1]}\n${it[2]}\n\n") }
        t.text = sb.toString()
    }
}
