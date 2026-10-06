package io.github.panuwattegif.readyproof

import android.app.Application

class ProofApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DriveSync.recover(this)
    }
}
