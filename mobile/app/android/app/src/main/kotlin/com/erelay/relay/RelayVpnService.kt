package com.erelay.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import relay.Client
import relay.Progress
import relay.Protector
import relay.Relay

/**
 * The tunnel's whole lifetime on Android.
 *
 * Three things here are not boilerplate, and each of them fails silently if
 * it is wrong:
 *
 *  - [Protector]. Once [VpnService.Builder.addRoute] sends 0.0.0.0/0 into the
 *    tun, the socket the core opens to the exit goes there too. The exit's
 *    address enters the tunnel, comes back out of the stack, is dialled
 *    again, and the phone spends a core talking to itself. Nothing throws.
 *    `protect()` is what breaks the loop, and it has to be handed to the core
 *    before anything dials.
 *
 *  - The descriptor is detached, and ownership goes with it. sing-tun does
 *    not duplicate the fd; it closes it when the tunnel ends. Holding the
 *    [ParcelFileDescriptor] as well means two owners for one descriptor, and
 *    Android's fdsan says so out loud and kills the process:
 *
 *        fdsan: attempted to close file descriptor 136, expected to be
 *        unowned, actually owned by ParcelFileDescriptor
 *
 *    So `detachFd()` hands it over for good, and nothing here closes it
 *    afterwards. The one case that has to be caught by hand is a tunnel that
 *    fails to start after the detach - there the fd is adopted back only to
 *    be closed.
 *
 *  - Foreground, with a notification, before anything else. A VPN service
 *    that is not in the foreground is killed as soon as the window goes away,
 *    and the tunnel goes with it.
 */
class RelayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The raw descriptor, once ownership has left ParcelFileDescriptor. Kept
    // only so that a status line can say whether one is open; nothing here
    // closes it, because the core does.
    private var tunFd: Int = -1
    private var client: Client? = null

    companion object {
        const val TAG = "Relay"
        const val ACTION_START = "com.erelay.relay.START"
        const val ACTION_STOP = "com.erelay.relay.STOP"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"

        // Windscribe's, which is a different account entirely. A folder can
        // hold both providers' exits, and an exit asked with the other one's
        // credential is refused - which reads on screen as "blocked here"
        // rather than as "wrong question".
        const val EXTRA_WS_USER = "wsUser"
        const val EXTRA_WS_PASSWORD = "wsPassword"
        const val EXTRA_CONFIG_DIR = "configDir"
        const val EXTRA_COUNTRY = "country"

        // Which files, when the window is connecting out of Starred rather
        // than out of everything. A set of names rather than a folder,
        // because that is what Starred is - a handful of exits spread across
        // every country, and no folder holds exactly them.
        const val EXTRA_ONLY = "only"

        // Which way out. "surfshark" races the pinned provider exits; the
        // rest go through the user's own server and differ only in the path
        // they ask it for.
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_TUNNEL_PASSWORD = "tunnelPassword"
        const val EXTRA_EDGES = "edges"

        private const val CHANNEL = "relay.tunnel"
        private const val NOTIFICATION = 1

        /**
         * Where the window is told to look, rather than the window being sent
         * a message. A broadcast wakes it; it reads these. That way a window
         * that was not listening yet - just opened, just rotated - still finds
         * the current state rather than waiting for the next change.
         */
        const val ACTION_STATUS = "com.erelay.relay.STATUS"

        @Volatile var status: String = "not connected"
            private set
        @Volatile var exitAddress: String = ""
            private set
        @Volatile var exitName: String = ""
            private set

        /**
         * Which config is carrying, and whose.
         *
         * Read by the window when it asks where the traffic is coming out:
         * that check goes through the exit, so it has to be opened with the
         * same provider's credential the race used.
         */
        @Volatile var exitFile: String = ""
            private set
        @Volatile var exitProvider: String = ""
            private set
        @Volatile var asked: Int = 0
            private set
        @Volatile var total: Int = 0
            private set
        @Volatile var tookMs: Int = 0
            private set

        /** When the current tunnel came up, in epoch seconds, or 0. */
        @Volatile var since: Long = 0
            private set

        /**
         * What went where, asked of whichever tunnel is up.
         *
         * On the companion rather than the instance because the window has
         * no handle on the service - it was never bound to one, deliberately,
         * so that closing the window cannot take the tunnel with it.
         */
        @Volatile private var live: Client? = null

        fun hostsJSON(): String =
            live?.hostsJSON() ?: """{"live":false,"rows":[]}"""

        /**
         * Say something on the service's behalf from somewhere that is not
         * the service - a refused permission, for instance, which the
         * Activity learns about and the service never does.
         */
        fun report(ctx: android.content.Context, text: String) {
            status = text
            ctx.sendBroadcast(Intent(ACTION_STATUS).setPackage(ctx.packageName))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before the core is asked for anything. Cheap, idempotent, and the
        // service may well be running with no window to have done it.
        AppFiles.announce(applicationContext)

        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION, notification("finding an exit"))

        // Whatever was carrying traffic a moment ago stops now.
        //
        // The core refuses to start a second tunnel on one Client, but every
        // connect builds a fresh Client - so that guard never sees the old
        // one, and pressing Connect twice left two interfaces up:
        //
        //     86: tun0  172.19.0.1/30
        //     87: tun1  172.19.0.1/30
        //
        // Android hands the newest one the routes, so it looked like it
        // worked. The first was still open, still holding a websocket, still
        // keeping the radio awake with a keepalive nobody was reading.
        stopTunnel()

        val user = intent?.getStringExtra(EXTRA_USER).orEmpty()
        val password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val wsUser = intent?.getStringExtra(EXTRA_WS_USER).orEmpty()
        val wsPassword = intent?.getStringExtra(EXTRA_WS_PASSWORD).orEmpty()
        val configDir = intent?.getStringExtra(EXTRA_CONFIG_DIR).orEmpty()
        val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: "auto"
        val only = intent?.getStringExtra(EXTRA_ONLY).orEmpty()
        val provider = intent?.getStringExtra(EXTRA_PROVIDER) ?: "surfshark"
        val domain = intent?.getStringExtra(EXTRA_DOMAIN).orEmpty()
        val tunnelPassword = intent?.getStringExtra(EXTRA_TUNNEL_PASSWORD).orEmpty()
        val edges = intent?.getStringExtra(EXTRA_EDGES).orEmpty()

        scope.launch {
            if (provider == "surfshark") {
                connect(user, password, wsUser, wsPassword, configDir, country, only)
            } else {
                connectServer(provider, domain, tunnelPassword, edges)
            }
        }
        return START_STICKY
    }

    /**
     * The interface both ways out are carried on.
     *
     * Shared rather than written twice, because everything it decides -
     * which addresses go in, which app is left out, whether the LAN is
     * spared - is a property of the tunnel and not of what is at the far end
     * of it. Two copies would agree today and disagree the first time one is
     * changed.
     */
    private fun buildInterface(): Builder {
        val b = Builder()
            .setSession("Relay")
            .addAddress(Relay.tunAddress(), Relay.tunPrefix().toInt())
            .addDnsServer(Relay.tunDNS())
            .setMtu(Relay.tunMTU().toInt())
            // Non-blocking: sing-tun reads the descriptor itself and a
            // blocking read would park its goroutine on a thread Android is
            // entitled to reclaim.
            .setBlocking(false)

        // Whether the printer, the NAS, the router's page and adb-over-wifi
        // stay reachable. On by default: local addresses are not routable
        // beyond the house, so nothing leaves by being excluded, and a VPN
        // that silently breaks the LAN is a VPN people blame for the wrong
        // thing.
        val allowLan = getSharedPreferences("relay", MODE_PRIVATE)
            .getBoolean("allowLan", true)

        if (!allowLan) {
            b.addRoute("0.0.0.0", 0)
        } else if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 can subtract a route. Below that there is only
            // adding, which is what Routes.exceptPrivate is for.
            b.addRoute("0.0.0.0", 0)
            for (p in Routes.PRIVATE) {
                b.excludeRoute(android.net.IpPrefix(
                    java.net.InetAddress.getByName(p.addr), p.bits))
            }
        } else {
            for (p in Routes.exceptPrivate()) b.addRoute(p.addr, p.bits)
        }

        // This app must not route its own traffic. Without it, the socket to
        // the exit is protected but every other thing the process does - a
        // crash report, an update check - re-enters its own tunnel.
        b.addDisallowedApplication(packageName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        return b
    }

    /**
     * The way out through the user's own server.
     *
     * No race and no catalogue: there is one server, and what varies is which
     * path it is asked for - /gw exits at the server itself, /ex at a
     * provider node it picks, /gwb is /gw without multiplexing. The tun, the
     * stack and the DNS above are the same ones the provider path uses,
     * because the core is written not to know which is underneath.
     */
    private fun connectServer(mode: String, domain: String, password: String, edges: String) {
        try {
            Relay.setProtector(object : Protector {
                override fun protect(fd: Long): Boolean =
                    this@RelayVpnService.protect(fd.toInt())
            })
            // Who opened each connection, for the log sheet. Handed over
            // beside the protector because both are the platform answering a
            // question the core cannot ask for itself.
            Relay.setNamer(AppNamer(applicationContext))

            if (domain.isEmpty() || password.isEmpty()) {
                fail("no server configured - set the domain and password in Settings")
                return
            }

            val c = Relay.newClient("", "")
            client = c
            live = c

            val cfg = Relay.newServerConfig()
            cfg.domain = domain
            cfg.password = password
            cfg.mode = mode
            cfg.edges = edges

            // Measured here rather than carried over from the desktop: which
            // Cloudflare addresses are filtered is a fact about this line, and
            // the phone is not always on the same line the desktop was.
            if (edges.isBlank()) {
                say("finding a way in")
                cfg.edges = try {
                    Relay.scanEdges(domain, 30000L)
                } catch (e: Exception) {
                    // Not fatal. The name may still carry the tunnel on a line
                    // that is only lying about the addresses.
                    Log.w(TAG, "edge scan: ${e.message}")
                    ""
                }
                // Kept, so the next connect does not pay for it again. Five
                // seconds of TLS handshakes is not much once and is a great
                // deal every time - and the answer does not change between
                // one connect and the next on the same line.
                //
                // Written from the service rather than handed back to the
                // Activity because the Activity may not exist: the tunnel
                // outlives the window, which is the whole point of it being
                // a service.
                if (cfg.edges.isNotBlank()) {
                    getSharedPreferences("relay", MODE_PRIVATE)
                        .edit().putString("tunnelEdges", cfg.edges).apply()
                }
            }

            say("opening the tunnel to $domain")

            val builder = buildInterface()

            val pfd = builder.establish()
            if (pfd == null) {
                fail("Android refused to open the interface")
                return
            }
            val fd = pfd.detachFd()
            try {
                c.startServerTunnel(fd.toLong(), Relay.tunMTU(), cfg)
            } catch (e: Exception) {
                ParcelFileDescriptor.adoptFd(fd).close()
                throw e
            }
            tunFd = fd

            exitAddress = domain
            exitFile = ""
            exitProvider = ""
            exitName = when (mode) {
                "multi" -> "$domain (server + exit)"
                "bulk" -> "$domain (bulk)"
                else -> "$domain (your server)"
            }
            since = System.currentTimeMillis() / 1000
            say("connected through $exitName")
        } catch (e: Exception) {
            Log.e(TAG, "server connect failed", e)
            fail(e.message ?: e.toString())
        }
    }

    private fun connect(
        user: String, password: String, wsUser: String, wsPassword: String,
        configDir: String, country: String, only: String,
    ) {
        try {
            // Before anything dials. See the class comment.
            Relay.setProtector(object : Protector {
                override fun protect(fd: Long): Boolean =
                    this@RelayVpnService.protect(fd.toInt())
            })
            // Who opened each connection, for the log sheet. Handed over
            // beside the protector because both are the platform answering a
            // question the core cannot ask for itself.
            Relay.setNamer(AppNamer(applicationContext))

            val c = Relay.newClient(user, password)
            // Both, when both are signed in. A pool holding one provider the
            // phone has no credential for still races the other half rather
            // than refusing outright - core.Logins.Keep decides that, and it
            // needs to have been told.
            c.setCredentials("windscribe", wsUser, wsPassword)
            client = c
            live = c

            // One scan rather than a file at a time: the folder is the truth,
            // and the same scan is what the window's country list came from -
            // so a country the page offers is a country the race can run in.
            c.scanFolder(configDir)
            // The same narrowing the window is showing. Without it, pressing
            // Connect from a Starred list would race the whole folder and
            // come back through an exit that is not in the list on screen.
            if (only.isNotBlank()) c.setOnly(only)
            if (c.count() == 0L) {
                fail("no pinned configs in $configDir")
                return
            }

            total = c.count().toInt()
            say("racing ${c.count()} exits")
            // Long literals, not Int. gomobile maps Go's int to Java long,
            // and Kotlin does not widen an Int to fill a Long parameter.
            val winner = c.raceIn(country, 6000L, 8L, object : Progress {
                override fun onProgress(n: Long, of: Long) {
                    asked = n.toInt()
                    total = of.toInt()
                    say("asked $n of $of")
                }
            })

            say("opening the tunnel via ${winner.name}")

            val builder = buildInterface()

            val pfd = builder.establish()
            if (pfd == null) {
                fail("Android refused to open the interface")
                return
            }

            // Handed over for good. See the class comment: two owners for one
            // descriptor is what fdsan kills the process for.
            val fd = pfd.detachFd()
            try {
                c.startTunnel(fd.toLong(), Relay.tunMTU(), winner.addr, winner.name,
                    winner.provider)
            } catch (e: Exception) {
                // Nobody owns it now, so adopt it back only to close it.
                ParcelFileDescriptor.adoptFd(fd).close()
                throw e
            }
            tunFd = fd

            exitAddress = winner.addr
            exitName = winner.name
            exitFile = winner.file
            exitProvider = winner.provider
            tookMs = winner.tookMs.toInt()
            since = System.currentTimeMillis() / 1000
            say("connected via ${winner.name} (${winner.addr}), ${winner.tookMs} ms")
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(e.message ?: e.toString())
        }
    }

    private fun stopTunnel() {
        try {
            client?.stopTunnel()
        } catch (e: Exception) {
            Log.w(TAG, "stopping the tunnel", e)
        }
        client = null
        live = null

        // Handed back, because it belongs to a service that is about to stop
        // being able to answer. VpnService.protect() on a dead service
        // returns false, and a false there is not a warning - core turns it
        // into a dial error, so every socket the window opens afterwards
        // fails with "the socket to the exit could not be kept out of the
        // tunnel" about a tunnel that is not there.
        //
        // The namer goes with it: it holds a Context and has nothing to
        // answer about once nothing is being carried.
        try {
            Relay.setProtector(null)
            Relay.setNamer(null)
        } catch (e: Exception) {
            Log.w(TAG, "letting go of the platform hooks", e)
        }
        // Nothing closes the descriptor here. stopTunnel() above ends the
        // stack, and the stack owns it - which is the whole point of handing
        // it over with detachFd().
        tunFd = -1
        exitAddress = ""
        exitName = ""
        exitFile = ""
        exitProvider = ""
        asked = 0
        total = 0
        tookMs = 0
        since = 0
        status = "not connected"
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    /** Android tearing the VPN down from Settings, rather than the app. */
    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    // -- saying what is happening --------------------------------------------

    private fun say(text: String) {
        status = text
        Log.i(TAG, text)
        notificationManager().notify(NOTIFICATION, notification(text))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun fail(text: String) {
        say("failed: $text")
    }

    private fun notificationManager() =
        getSystemService(NotificationManager::class.java)

    private fun notification(text: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Tunnel", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // NotificationCompat rather than Notification.Builder: the
        // channel-taking constructor is API 26 and minSdk here is 24, and the
        // compat one does the right thing on both without a version branch.
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Relay")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_relay)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
