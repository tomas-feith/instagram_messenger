package com.instachat.app

import android.app.Application
import com.instachat.app.notify.InboxWorker
import com.instachat.app.notify.ensureChannels

class InstaChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The channels exist before anything posts to them, so they appear in system
        // settings straight away and can be silenced before the first notification.
        ensureChannels(this)
        InboxWorker.schedule(this)
    }
}
