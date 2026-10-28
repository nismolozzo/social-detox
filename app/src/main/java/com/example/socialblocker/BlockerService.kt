package com.example.socialblocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class BlockerService : AccessibilityService() {
    companion object {
        var instance: BlockerService? = null
        // Testi del pulsante "Arresto forzato" nelle Impostazioni (italiano, inglese, varianti Xiaomi/Samsung)
        private val STOP_LABELS = listOf("arresto forzato", "forza arresto", "forza chiusura", "force stop", "termina", "arresta")
        private val OK_LABELS = listOf("ok", "sì", "si", "yes", "termina", "arresta", "forza arresto", "arresto forzato", "force stop")
    }

    private val h = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var current = ""
    private var launchedFor = 0L
    private var suppressUntil = 0L
    private var lastLogged = ""
    private var polls = 0
    private var nullPolls = 0
    private var lastSlow = 0L
    private val gate by lazy { GateOverlay(this) }

    // Chiusura forzata del social (Impostazioni -> Info app -> Arresto forzato)
    private var fsPkg = ""
    private var fsStep = 0
    private var fsStartedAt = 0L
    private var fsStepAt = 0L
    private var fsDeadline = 0L
    private var fsSeen = false

    /** Dopo "Rinuncio" non rimostrare subito la conferma mentre il sistema torna alla home. */
    fun suppress(ms: Long = 1500L) { suppressUntil = System.currentTimeMillis() + ms }

    /** Nessuna eccezione deve far crollare il servizio: la annoto nella diagnostica dell'app. */
    private fun safe(where: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            Store.setError(this, "$where: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { safe("screenOff") { fsPkg = ""; gate.dismiss(); endSession() } }
    }

    private val tick = object : Runnable {
        override fun run() {
            h.removeCallbacks(this)
            val c = this@BlockerService
            safe("tick") {
                if (System.currentTimeMillis() - lastSlow >= 15000L) {
                    lastSlow = System.currentTimeMillis()
                    Daily.check(c)
                    Rewards.checkAway(c)
                }
                if (fsPkg.isNotEmpty()) {
                    runForceStop()
                } else {
                    // Controllo attivo dell'app in primo piano: non dipende dagli eventi, che alcuni
                    // telefoni non inviano quando un'app viene solo riportata davanti.
                    val ap = activePkg()
                    polls++
                    if (ap == null) { nullPolls++; logSeen("controllo", "finestra attiva non leggibile") }
                    else { logSeen("controllo", ap); onForeground(ap, true) }
                    if (polls % 5 == 0) Store.setPollInfo(c, "Controlli: $polls, senza finestra leggibile: $nullPolls")
                    if (Store.sessionActive(c) && current !in Store.BLOCKED) endSession()
                    // Seconda barriera: ogni 15 minuti continuati di sessione
                    if (Store.sessionActive(c) && !gate.isShowing &&
                        System.currentTimeMillis() >= Store.nextCheck(c) && launchedFor != Store.nextCheck(c)) {
                        launchedFor = Store.nextCheck(c)
                        gate.show(Store.sessionPkg(c), true)
                    }
                }
                updateOverlay()
            }
            val delay = when {
                Store.sessionActive(c) -> 1000L
                screenOn() -> 500L   // controllo frequente: la conferma compare entro mezzo secondo
                else -> 60000L
            }
            h.postDelayed(this, delay)
        }
    }

    /** Pulsante "Prova la schermata di conferma" nell'app: prova solo il disegno dell'overlay. */
    fun testGate() { safe("prova") { gate.dismiss(); gate.show("test", false) } }

    // ---------------------------------------------------------------- chiusura forzata

    /** Chiamata da "Rinuncio": mentre la schermata e' ancora visibile apre le Impostazioni del social e preme "Arresto forzato". */
    fun closeSocial(pkg: String) {
        suppress(15000L)
        val now = System.currentTimeMillis()
        fsPkg = pkg; fsStep = 1; fsSeen = false
        fsStartedAt = now; fsStepAt = now; fsDeadline = now + 7000L
        Store.log(this, "chiusura forzata di $pkg: apro le Impostazioni")
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            Store.setError(this, "apertura Impostazioni: ${e.javaClass.simpleName}: ${e.message}")
            finishClose(false)
            return
        }
        kick()
    }

    private fun runForceStop() {
        if (fsPkg.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now > fsDeadline) { finishClose(false); return }
        val root = rootInActiveWindow
        val pk = root?.packageName?.toString() ?: ""
        val inSettings = pk.contains("settings") || pk.contains("securitycenter") ||
            pk.contains("packageinstaller") || pk.contains("permissioncontroller")
        if (inSettings) fsSeen = true
        else if (!fsSeen && now > fsStartedAt + 2500L) { finishClose(false); return }
        if (root == null || !inSettings) return

        when (fsStep) {
            1 -> {
                val btn = findByLabel(root, STOP_LABELS)
                if (btn != null) {
                    if (!btn.isEnabled) { finishClose(true); return } // social gia' fermo
                    if (clickUp(btn)) { fsStep = 2; fsStepAt = now; Store.log(this, "premuto Arresto forzato") }
                }
            }
            2 -> {
                var ok: AccessibilityNodeInfo? = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
                if (ok == null && now > fsStepAt + 1500L) ok = findByLabel(root, OK_LABELS)
                if (ok != null && clickUp(ok)) finishClose(true)
            }
        }
    }

    private fun finishClose(ok: Boolean) {
        val p = fsPkg
        fsPkg = ""; fsStep = 0
        suppress(2500L)
        try { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(p) } catch (e: Throwable) { }
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ safe("chiusura") { gate.dismiss() } }, 500L)
        Store.log(this, if (ok) "social chiuso: $p" else "chiusura forzata NON riuscita: $p")
    }

    private fun findByLabel(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen++ < 500) {
            val n = stack.removeLast()
            val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase().trim()
            if (t.isNotEmpty() && n.isVisibleToUser && labels.any { t == it || t.contains(it) }) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun clickUp(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var guard = 0
        while (n != null && guard++ < 6) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    // ---------------------------------------------------------------- rilevamento

    private fun screenOn() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    @Suppress("DEPRECATION")
    private fun activePkg(): String? {
        val root = rootInActiveWindow ?: return null
        val p = root.packageName?.toString()
        root.recycle()
        return p
    }

    private fun logSeen(src: String, pkg: String) {
        val t = "$src: $pkg"
        if (t == lastLogged) return
        lastLogged = t
        Store.log(this, t)
    }

    override fun onServiceConnected() {
        instance = this
        safe("onServiceConnected") {
            CrashLog.install(this)
            Store.endSession(this) // sessione rimasta aperta da un crash
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenOff, f)
            Store.log(this, "servizio connesso")
            kick()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Store.log(this, "servizio scollegato dal sistema")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        safe("onDestroy") {
            try { unregisterReceiver(screenOff) } catch (e: Exception) { }
            fsPkg = ""
            gate.dismiss()
            removeOverlay()
        }
        instance = null
        super.onDestroy()
    }

    fun kick() { h.removeCallbacks(tick); h.post(tick) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        safe("evento") {
            if (fsPkg.isNotEmpty()) { runForceStop(); return@safe }
            val pkg = e.packageName?.toString() ?: return@safe
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    logSeen("evento", pkg)
                    onForeground(pkg, false)
                }
                // Un social riportato davanti spesso genera solo eventi di contenuto
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (pkg in Store.BLOCKED && !Store.sessionActive(this) && !gate.isShowing &&
                        System.currentTimeMillis() >= suppressUntil && activePkg() == pkg) {
                        logSeen("contenuto", pkg)
                        onForeground(pkg, false)
                    }
                }
            }
        }
    }

    private fun onForeground(pkg: String, fromPoll: Boolean) {
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
        if (pkg == packageName) {
            // Gli eventi dei miei overlay vanno ignorati; se invece sono io la finestra attiva, sono uscito dal social
            if (fromPoll) { gate.dismiss(); endSession() }
            return
        }
        current = pkg
        if (Store.estimateH(this) < 0) return // onboarding non completato

        if (pkg in Store.BLOCKED) {
            if (Store.sessionActive(this) && Store.sessionPkg(this) == pkg) return
            if (gate.isShowing || System.currentTimeMillis() < suppressUntil) return
            endSession()
            gate.show(pkg, false)
            Store.log(this, "=> conferma mostrata per $pkg")
        } else {
            gate.dismiss()
            endSession()
        }
    }

    private fun endSession() {
        if (!Store.sessionActive(this)) return
        Store.endSession(this)
        removeOverlay()
        kick()
    }

    private fun updateOverlay() {
        if (!Store.sessionActive(this)) { removeOverlay(); return }
        val sec = (System.currentTimeMillis() - Store.sessionStart(this)) / 1000
        val min = sec / 60
        val color = when {
            min < 5 -> 0xFFF4B183.toInt()   // pesca
            min < 15 -> 0xFFE9A23B.toInt()  // ambra
            else -> 0xFFC8643C.toInt()      // terracotta (non rosso allarmante)
        }
        if (overlay == null) {
            val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(40, 20, 40, 20) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 140 }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, lp)
            overlay = tv
        }
        overlay?.text = "⏱️ Sei qui da $min min ${sec % 60}s..."
        overlay?.setTextColor(if (min >= 15) Color.WHITE else 0xFF3A2412.toInt())
        overlay?.background = GradientDrawable().apply { setColor(color); cornerRadius = 60f }
    }

    private fun removeOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        overlay = null
    }

    override fun onInterrupt() {}
}
