package com.erelay.relay

import android.app.Application
import com.erelay.relay.data.RelayCore

/**
 * Tells the core where its folders are before anything can ask for a list,
 * and holds the one [RelayCore] every screen reads. The service announces
 * the folders too, because either may be the first thing alive.
 */
class RelayApp : Application() {

    lateinit var core: RelayCore
        private set

    override fun onCreate() {
        super.onCreate()
        AppFiles.announce(this)
        core = RelayCore(this)
    }
}
