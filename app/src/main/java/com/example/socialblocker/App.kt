package com.example.socialblocker

import android.app.Application

/** Parte per prima in ogni avvio del processo: registra i crash e gli avvii. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            CrashLog.install(this)
            Store.log(this, "processo avviato")
        } catch (e: Throwable) { }
    }
}
