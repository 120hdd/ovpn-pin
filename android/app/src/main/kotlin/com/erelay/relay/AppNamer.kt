package com.erelay.relay

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import android.util.Log
import java.net.InetSocketAddress

/**
 * Which application opened a connection.
 *
 * The desktop answers this by looking up the owner of a local port. Android
 * has the same question and a different API for it:
 * `ConnectivityManager.getConnectionOwnerUid` takes the five-tuple the tun
 * already has and returns the uid that owns it, and `PackageManager` turns
 * that into something a person recognises.
 *
 * Added in Android 10. Below that the column stays empty, which is what it
 * was before this existed and is honest about a platform that will not say.
 *
 * Two caches, because this is called once per connection and a connection is
 * a thing an app makes hundreds of:
 *
 *   uid -> label   a PackageManager lookup is a binder call and a disk read
 *   nothing else   the five-tuple is never cached; it is different every time
 */
class AppNamer(private val ctx: Context) : relay.Namer {

    private val cm by lazy {
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    private val labels = HashMap<Int, String>()

    override fun name(protocol: Long, source: String?, destination: String?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val src = parse(source) ?: return ""
        val dst = parse(destination) ?: return ""
        val manager = cm ?: return ""

        val uid = try {
            manager.getConnectionOwnerUid(protocol.toInt(), src, dst)
        } catch (e: Exception) {
            // A SecurityException here is the platform declining, not a bug.
            // It happens for sockets that are not ours to ask about, and the
            // answer to that is the same as not knowing.
            return ""
        }
        if (uid == -1 || uid == android.os.Process.INVALID_UID) return ""

        synchronized(labels) { labels[uid] }?.let { return it }

        val label = labelFor(uid)
        synchronized(labels) { labels[uid] = label }
        return label
    }

    /**
     * A uid to something worth reading.
     *
     * The application's own label first - "Telegram", not
     * "org.telegram.messenger" - because the sheet is read by a person. The
     * package name when there is no label, and the bare uid when even the
     * package is not visible, which happens under package-visibility rules
     * for apps this one has no business knowing about.
     */
    private fun labelFor(uid: Int): String {
        val pm = ctx.packageManager
        val names = pm.getPackagesForUid(uid) ?: return "uid $uid"
        if (names.isEmpty()) return "uid $uid"

        for (pkg in names) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                if (label.isNotBlank()) return label
            } catch (e: PackageManager.NameNotFoundException) {
                // Visible in the uid but not to us. Try the next.
            }
        }
        // A shared uid with nothing readable in it still has a name worth
        // showing: several system components share uid 1000, and "android"
        // is more use than "uid 1000".
        return names.first()
    }

    /** "1.2.3.4:567" as the platform wants it. */
    private fun parse(addr: String?): InetSocketAddress? {
        val s = addr ?: return null
        val cut = s.lastIndexOf(':')
        if (cut <= 0) return null
        val host = s.substring(0, cut).trim('[', ']')
        val port = s.substring(cut + 1).toIntOrNull() ?: return null
        return try {
            InetSocketAddress(java.net.InetAddress.getByName(host), port)
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "unparseable address $s")
            null
        }
    }

    companion object {
        /** IPPROTO_TCP, which is all that reaches the handler. */
        val TCP: Int = OsConstants.IPPROTO_TCP
    }
}
