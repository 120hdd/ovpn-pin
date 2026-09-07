package com.erelay.relay

import android.content.Context
import android.util.Log
import relay.Relay
import java.io.File

/**
 * The one directory this app can write to, and everything under it.
 *
 * The desktop works its layout out from where it was started. A phone has no
 * working directory and no notion of "beside the app": the APK is not a place
 * files live, and the writable folder is whatever Android hands over at
 * runtime. So it is asked for once and everything is derived.
 *
 * External files rather than internal, and that is the load-bearing choice:
 * `Android/data/<id>/files` is visible to a file manager and to `adb push`,
 * which is how configs got onto the phone before the app could fetch them
 * itself, and how somebody looks at what is there when something is wrong.
 * The cost is that uninstalling takes it all with it.
 *
 * The names match app/paths.py, with the folders a phone cannot use left out.
 */
object AppFiles {

    /**
     * External storage can be absent - a phone mid-eject, an emulator with no
     * SD - and getExternalFilesDir answers null when it is. Internal storage
     * is always there, so the app works with a file manager that cannot see
     * it rather than not at all.
     */
    fun root(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun pinned(ctx: Context) = File(root(ctx), "pinned")
    fun configs(ctx: Context) = File(root(ctx), "configs")
    fun windscribe(ctx: Context) = File(root(ctx), "windscribe")
    fun dropped(ctx: Context) = File(root(ctx), "dropped")
    fun state(ctx: Context) = File(root(ctx), ".state")

    /** Surfshark's service credential, where setup-phone.sh pushes it. */
    fun auth(ctx: Context) = File(root(ctx), "auth")

    /** Windscribe's proxy credential, which is a different account. */
    fun windscribeAuth(ctx: Context) = File(root(ctx), "auth-windscribe")

    /**
     * Tell the core where everything is, and make the folders.
     *
     * Called from the Activity and from the service, because either can be
     * the first thing alive: a phone that comes back with the tunnel on
     * starts the service with no window at all. Calling it twice is free.
     */
    fun announce(ctx: Context) {
        try {
            Relay.setDataDir(root(ctx).absolutePath)
            // Which core this build is carrying, said once.
            //
            // A stale libs/relay.aar after a bind signature change does not
            // fail to compile - Gradle is looking at a jar, not at the Go -
            // it fails at run time with a NoSuchMethodError naming a method
            // nobody wrote. One line in logcat turns that into a version
            // somebody can compare.
            Log.i(RelayVpnService.TAG, "${Relay.version()} at ${root(ctx)}")
        } catch (e: Exception) {
            // Nothing here can recover from this, but a log line naming it is
            // the difference between "the list is empty" and knowing why.
            Log.e(RelayVpnService.TAG, "data dir: ${e.message}")
        }
    }

    /** How many .ovpn files a folder holds, and 0 for one that is not there. */
    fun count(folder: File): Int =
        folder.listFiles { f -> f.name.endsWith(".ovpn") }?.size ?: 0

    /**
     * Unpinned configs waiting in an inbox, counted off their names.
     *
     * `.prod.` is Surfshark's and `.ws.` is Windscribe's, the same mark the
     * catalogue reads. Counted rather than opened: five hundred files opened
     * to learn what five hundred names already say is a settings sheet that
     * takes a second to appear.
     */
    fun waiting(folder: File, mark: String): Int =
        folder.listFiles { f -> f.name.endsWith(".ovpn") && f.name.contains(mark) }
            ?.size ?: 0
}
