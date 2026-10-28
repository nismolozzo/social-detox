package com.example.socialblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Schermata di conferma disegnata come finestra di sistema dal servizio di accessibilita'.
 * Non avvia nessuna Activity: Android 10+ blocca l'avvio di Activity da background,
 * mentre un overlay di accessibilita' si mostra sopra qualsiasi app.
 */
class GateOverlay(private val s: AccessibilityService) {
    private var view: LinearLayout? = null
    private var timer: CountDownTimer? = null

    val isShowing: Boolean get() = view != null

    private fun wm() = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun show(pkg: String, timeout: Boolean) {
        if (view != null) return

        val title = if (timeout) {
            val min = (System.currentTimeMillis() - Store.sessionStart(s)) / 60000
            "Sei qui dentro da $min minuti.\nVuoi davvero continuare a scrollare o è il momento di chiudere?"
        } else Rewards.deterrent()

        val info = (if (timeout) "" else "Sei stato lontano dai social per ${Fmt.long(Store.sinceLastMs(s))}.\n") +
            "❌ Tempo sprecato sui social oggi: ${Fmt.long(Store.wastedToday(s))}"

        val root = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
            setBackgroundColor(0xFFFBE3CF.toInt()) // pesca morbido: "fermati a riflettere"
            isClickable = true // assorbe i tocchi: l'app sotto non si puo' usare
        }
        root.addView(TextView(s).apply {
            text = title; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 32); setTextColor(0xFF3A2412.toInt())
        })
        root.addView(TextView(s).apply {
            text = info; gravity = Gravity.CENTER; setTextColor(0xFF3A2412.toInt())
        })

        // RINUNCIA / ESCI: grande e colorato (menta = successo)
        root.addView(Button(s).apply {
            text = if (timeout) "ESCO ORA\nTorno alla vita reale" else "RINUNCIO\nResto libero"
            textSize = 24f; setTextColor(0xFF06382A.toInt())
            background = GradientDrawable().apply { setColor(0xFF1ED9A4.toInt()); cornerRadius = 48f }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 380).apply { topMargin = 48 }
            setOnClickListener {
                if (pkg == "test") { dismiss(); return@setOnClickListener }
                Store.addBlock(s)
                try { Rewards.firstBlock(s) } catch (e: Throwable) { Store.setError(s, "premio: ${e.javaClass.simpleName}: ${e.message}") }
                if (timeout) Store.endSession(s)
                val svc = BlockerService.instance
                if (svc != null) {
                    // La schermata resta davanti mentre il servizio chiude il social dalle Impostazioni
                    showBusy("Chiudo il social…")
                    svc.closeSocial(pkg)
                } else {
                    dismiss()
                    s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }
        })

        // PROSEGUI: piccolo, disattivato per 5 secondi
        val stay = Button(s).apply {
            text = if (timeout) "Altri 15 minuti tra 5..." else "Accesso consentito tra 5..."
            textSize = 12f; isEnabled = false
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 64 }
            setOnClickListener {
                if (pkg == "test") { dismiss(); return@setOnClickListener }
                if (timeout) Store.extendCheck(s) else Store.startSession(s, pkg)
                dismiss()
                BlockerService.instance?.kick()
            }
        }
        root.addView(stay)

        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm().addView(root, lp)
        view = root

        timer = object : CountDownTimer(5000, 250) {
            override fun onTick(ms: Long) {
                stay.text = (if (timeout) "Altri 15 minuti tra " else "Accesso consentito tra ") + "${(ms + 999) / 1000}..."
            }
            override fun onFinish() {
                stay.isEnabled = true
                stay.text = if (timeout) "Stai altri 15 minuti" else "Entra comunque"
            }
        }.start()
    }

    /** Sostituisce il contenuto con un solo messaggio (la finestra resta a schermo intero). */
    private fun showBusy(message: String) {
        timer?.cancel(); timer = null
        val v = view ?: return
        v.removeAllViews()
        v.addView(TextView(s).apply {
            text = message; textSize = 22f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF3A2412.toInt())
        })
    }

    fun dismiss() {
        timer?.cancel(); timer = null
        view?.let { try { wm().removeView(it) } catch (e: Exception) { } }
        view = null
    }
}
