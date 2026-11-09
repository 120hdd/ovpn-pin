package com.erelay.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import android.os.SystemClock
import com.erelay.relay.data.SplitMode
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    @Volatile private var client: Client? = null

    // The watchdog over a tunnel that is up. See watch().
    private var watcher: Job? = null

    // The sentence the tunnel came up with, said again when a stall clears
    // without the exit having changed.
    private var connectedText = ""

    companion object {
        const val TAG = "Relay"
        const val ACTION_START = "com.erelay.relay.START"
        const val ACTION_STOP = "com.erelay.relay.STOP"

        /**
         * Where the traffic was measured to come out, from the window's
         * seen-as check. The notification names this rather than the
         * country the server was sold as, which is not always where it is.
         */
        const val ACTION_SEEN = "com.erelay.relay.SEEN"
        const val EXTRA_SEEN_COUNTRY = "seenCountry"
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
        // A server reached by IP: the certificate it must show, and the API
        // password server + exit sets the exit with.
        const val EXTRA_PIN = "pin"
        const val EXTRA_API_PASSWORD = "apiPassword"

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

        /** The measured exit country, two letters, or empty until checked. */
        @Volatile var seenCountry: String = ""
            private set

        /**
         * The tunnel is up but its exit has stopped carrying, and the service
         * is finding another. The interface stays up meanwhile - see watch() -
         * so [exitAddress] is still set, and this is what says that "up" is
         * not "connected" just now.
         */
        @Volatile var stalled: Boolean = false
            private set

        // How the watchdog paces itself. Counts are read every few seconds and
        // cost nothing; a real request through the exit is sent once a minute
        // while somebody is looking at the phone, and at once when the counts
        // say apps are failing.
        private const val WATCH_EVERY_MS = 10_000L
        private const val CHECK_EVERY_MS = 60_000L
        // A working exit answers the check in one to two seconds from Iran;
        // eight is slow enough to forgive a bad moment on the line.
        private const val CHECK_TIMEOUT_MS = 8_000L
        private const val RECHECK_AFTER_MS = 2_000L
        // Connections in a row the exit failed before it is asked directly.
        private const val FAILS_BEFORE_CHECK = 2
        // Checks in a row that fail before the exit is given up on. Two, so
        // one dropped packet on a bad line does not cost a working exit.
        private const val MISSES_BEFORE_SWITCH = 2

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

        /** Who is using the Wi-Fi share, asked of whichever tunnel is up. */
        fun lanShareJSON(): String =
            live?.lanShareJSON() ?: """{"on":false,"port":0,"addrs":[],"clients":0}"""

        /** Starts the share on the live tunnel. Throws when there is none, or the port is taken. */
        fun startLanShare(port: Int, password: String): String {
            val c = live ?: throw IllegalStateException("The tunnel is not up.")
            return c.startLANShare(port.toLong(), password)
        }

        fun stopLanShare() {
            try {
                live?.stopLANShare()
            } catch (e: Exception) {
                Log.w(TAG, "stopping the Wi-Fi share", e)
            }
        }

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

        if (intent?.action == ACTION_SEEN) {
            // Only a better word for the notification: the tunnel is not
            // touched, which is why this is checked before anything else.
            if (exitAddress.isNotEmpty()) {
                seenCountry = intent.getStringExtra(EXTRA_SEEN_COUNTRY).orEmpty()
                notificationManager().notify(NOTIFICATION, notification(status))
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // Off the main thread: closing the stack closes every connection
            // it carries, and that froze the window for as long as it took -
            // which was the stutter on every Disconnect.
            scope.launch {
                stopTunnel()
                stopSelf()
            }
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
        //
        // In the coroutine below rather than here, for the same reason as Stop:
        // this runs on the main thread and the window is drawing on it.

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
        val pin = intent?.getStringExtra(EXTRA_PIN).orEmpty()
        val apiPassword = intent?.getStringExtra(EXTRA_API_PASSWORD).orEmpty()

        scope.launch {
            stopTunnel()
            if (provider == "surfshark") {
                connect(user, password, wsUser, wsPassword, configDir, country, only)
            } else {
                connectServer(provider, domain, tunnelPassword, edges, pin,
                    ServerExit(country, apiPassword, user, password, wsUser, wsPassword))
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
     *
     * Handed back with it, the core's half of the split tunnel, which is
     * worked out here because it depends on what the interface was told:
     * the caller passes it on with [applySplit] before the tunnel starts.
     */
    private fun buildInterface(): Pair<Builder, CoreSplit> {
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

        // Which apps the interface admits, and what the core does with what
        // it is handed - Settings → Split tunnel. This app leaving itself
        // out happens in there too, because one of the ways to split cannot
        // have it said by name.
        val split = splitInto(b)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        return b to split
    }

    /**
     * What the core is told about the split tunnel. [mode] is what the core
     * does with what reaches it, and is not always the mode the setting
     * names: see [splitInto].
     */
    private class CoreSplit(
        val mode: SplitMode,
        val iran: Boolean,
        /** The user's domains, addresses and ranges, one a line, as saved. */
        val entries: String,
        /** The listed apps' uids, comma-joined, when the core is to ask whose a connection is. */
        val uids: String,
    )

    /**
     * Split tunnelling, decided half here and half in the core, because each
     * of them can do only half of it.
     *
     * The interface admits or leaves out an app by its name, and nothing
     * finer. The core sees every connection and where it is going, so it can
     * send a destination straight out - but only what the interface admitted
     * ever reaches it. So apps are settled here, and sites and ranges are the
     * core's to send round the tunnel:
     *
     *   Except these   the listed apps are left out of the interface, and
     *                  the core sends the listed destinations straight out.
     *
     *   Only these     apps alone: only they are let in, and the core carries
     *                  all of what arrives. Destinations alone: every app is
     *                  let in, and the core carries only what goes to them.
     *                  Both: every app is let in, and the core asks whose each
     *                  connection is (AppNamer, as relay.Owner) and carries
     *                  the listed apps' along with the listed destinations.
     *
     * Both at once needs that question answered, and Android only answers it
     * from 10 on. Below 10 the listed apps are let in and the destinations
     * are not acted on: an interface that admits only some apps cannot also
     * catch a site opened by any app. The window says so where it is chosen.
     *
     * Android throws when one interface is given apps to let in and apps to
     * leave out, so Only these with apps let in never leaves this app out by
     * name. It need not: an app that is not let in is out already.
     */
    private fun splitInto(b: Builder): CoreSplit {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val mode = SplitMode.of(prefs.getString("splitMode", "off"))
        val iran = prefs.getBoolean("splitIran", true)
        val entries = prefs.getString("splitDomains", "").orEmpty().trim()
        val hasDest = iran || entries.isNotEmpty()

        // This app must not route its own traffic. Without it, the socket to
        // the exit is protected but every other thing the process does - a
        // crash report, an update check - re-enters its own tunnel.
        fun leaveSelfOut() = b.addDisallowedApplication(packageName)
        fun told(m: SplitMode, uids: String = "") = CoreSplit(m, iran, entries, uids)

        if (mode == SplitMode.OFF) {
            leaveSelfOut()
            return told(SplitMode.OFF)
        }
        val apps = splitApps(prefs.getString("splitApps", "").orEmpty(), iran)

        if (mode == SplitMode.BYPASS) {
            for (pkg in apps.keys) {
                try {
                    b.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    // Uninstalled since it was looked up: nothing to leave out.
                }
            }
            leaveSelfOut()
            return told(if (hasDest) SplitMode.BYPASS else SplitMode.OFF)
        }

        // Only these.
        return when {
            // Nothing listed. A tunnel that carries nothing is a tunnel that
            // does nothing; carrying everything is the safer way to be wrong,
            // and the window warns before it comes to this.
            apps.isEmpty() && !hasDest -> {
                leaveSelfOut()
                told(SplitMode.OFF)
            }
            apps.isEmpty() -> {
                leaveSelfOut()
                told(SplitMode.ONLY)
            }
            hasDest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                leaveSelfOut()
                told(SplitMode.ONLY, apps.values.distinct().joinToString(","))
            }
            // Apps alone, or apps and destinations on a phone that cannot say
            // whose a connection is: the apps are let in, and whatever they
            // send is carried.
            else -> {
                var admitted = 0
                for (pkg in apps.keys) {
                    try {
                        b.addAllowedApplication(pkg)
                        admitted++
                    } catch (e: PackageManager.NameNotFoundException) {
                        // As above.
                    }
                }
                // An interface with no app let in carries every app, this one
                // included. If every listed app went away meanwhile, that is
                // what this is, and this app leaves itself out as usual.
                if (admitted == 0) leaveSelfOut()
                told(SplitMode.OFF)
            }
        }
    }

    /**
     * The listed apps that are on this phone, with their uids: the user's
     * picks, and the core's list of Iranian apps while that preset is on.
     * Never this app - [splitInto] leaves it out, or not, itself.
     */
    private fun splitApps(picked: String, iran: Boolean): Map<String, Int> {
        val listed = LinkedHashSet<String>()
        picked.lines().mapTo(listed) { it.trim() }
        if (iran) Relay.splitIranApps().lines().mapTo(listed) { it.trim() }
        val found = LinkedHashMap<String, Int>()
        for (pkg in listed) {
            if (pkg.isEmpty() || pkg == packageName) continue
            try {
                found[pkg] = packageManager.getApplicationInfo(pkg, 0).uid
            } catch (e: PackageManager.NameNotFoundException) {
                // Not on this phone. Most of the Iranian list is not, on any
                // one phone, and an interface told to leave out an app that is
                // not there throws rather than shrugging.
            }
        }
        return found
    }

    /**
     * The DNS server of the network under the tunnel, for the names the split
     * tunnel sends straight out. An Iranian site has to be looked up the way
     * the line would look it up, or its CDN answers with a foreign address
     * and "straight out" goes the long way round. IPv4, because that is what
     * the core dials; empty when there is none, and the core falls back on
     * its own.
     *
     * The active network first when it is not a VPN - it is the one the apps
     * left out of the tunnel are using - and otherwise any network that
     * reaches the internet without being one.
     */
    private fun directDns(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return ""
        fun underneath(n: Network): Boolean {
            val caps = cm.getNetworkCapabilities(n) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return try {
            val nets = ArrayList<Network>()
            val active = cm.activeNetwork
            if (active != null && underneath(active)) nets.add(active)
            for (n in cm.allNetworks) if (n != active && underneath(n)) nets.add(n)
            nets.firstNotNullOfOrNull { n ->
                cm.getLinkProperties(n)?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "finding the line's own DNS: ${e.message}")
            ""
        }
    }

    /**
     * The core's half of the split, handed over after the interface is built
     * and before the tunnel starts - the stack reads it as it comes up. False,
     * with the failure said, when the core refuses it: a tunnel that came up
     * routing other than it was told to would be worse than none.
     */
    private fun applySplit(c: Client, s: CoreSplit): Boolean {
        val dns = if (s.mode == SplitMode.OFF) "" else directDns()
        Log.i(TAG, "split: ${s.mode.key}, iran ${s.iran}, " +
            "${s.entries.lines().count { it.isNotBlank() }} entries, " +
            "${s.uids.split(',').count { it.isNotBlank() }} apps by uid, " +
            "line DNS ${dns.ifEmpty { "unknown" }}")
        return try {
            c.setSplit(s.mode.key, s.iran, s.entries, s.uids, dns)
            true
        } catch (e: Exception) {
            fail("the split tunnel settings were not taken: " + (e.message ?: e.toString()).take(140))
            false
        }
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
    /**
     * Puts the server's Shadowsocks settings on [cfg] when that is the way in,
     * and says whether it is. The same settings RelayCore.importKey writes.
     */
    private fun applySs(cfg: relay.ServerConfig): Boolean {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val secret = prefs.getString("tunnelSsPassword", "").orEmpty()
        if (prefs.getString("tunnelTransport", "") != "ss" || secret.isEmpty()) return false
        cfg.transport = "ss"
        cfg.ssMethod = prefs.getString("tunnelSsMethod", "").orEmpty()
        cfg.ssPassword = secret
        cfg.ssPort = prefs.getInt("tunnelSsPort", 0).toLong()
        cfg.ssExitPort = prefs.getInt("tunnelSsExitPort", 0).toLong()
        return true
    }

    /** What server + exit needs to point the server at the picked location. */
    private class ServerExit(
        val country: String, val apiPassword: String,
        val ssUser: String, val ssPassword: String, val wsUser: String, val wsPassword: String,
    )

    private fun connectServer(mode: String, domain: String, password: String, edges: String,
                              pin: String, exit: ServerExit) {
        try {
            Relay.setProtector(object : Protector {
                override fun protect(fd: Long): Boolean =
                    this@RelayVpnService.protect(fd.toInt())
            })
            // Who opened each connection, for the log sheet. Handed over
            // beside the protector because both are the platform answering a
            // question the core cannot ask for itself. The same lookup as a
            // uid is what splitting by app asks, when it has to ask.
            val namer = AppNamer(applicationContext)
            Relay.setNamer(namer)
            Relay.setOwner(namer)

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
            cfg.pin = pin

            // Shadowsocks, when the server's key offered it and it is the
            // chosen way in: straight to the address, so there are no edges
            // to measure. Read from the settings rather than carried in the
            // intent, so a restart by the system reaches the server the same
            // way the window did.
            val viaSs = applySs(cfg)

            // Measured here rather than carried over from the desktop: which
            // Cloudflare addresses are filtered is a fact about this line, and
            // the phone is not always on the same line the desktop was.
            if (!viaSs && edges.isBlank()) {
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

            if (!viaSs && cfg.edges.isBlank()) {
                fail("no way in to $domain answered - check the domain, or try " +
                    "Find another way in under Settings")
                return
            }

            // Server + exit: the server is told which exit to leave by - the
            // picked location's first, with that exit's own provider's
            // account - before anything goes through it.
            var exitPlace = ""
            if (mode == "multi") {
                say("checking the server answers")
                val pick = Relay.newClient(exit.ssUser, exit.ssPassword)
                try {
                    pick.load()
                    // From a server in Iran, upload is throttled per foreign
                    // exit: most in a city carry next to nothing up. Each is
                    // set and asked to carry an upload, and the first that
                    // does is kept - the location stays the one picked.
                    say("finding an exit that uploads")
                    val at = pick.bestServerExit(cfg, exit.apiPassword, exit.country.ifEmpty { "auto" },
                        exit.ssUser, exit.ssPassword, exit.wsUser, exit.wsPassword, 6L, 4000L)
                    exitPlace = at.name + if (at.tookMs > 0) ", ${at.tookMs} KB/s up" else ", upload throttled"
                } catch (e: Exception) {
                    fail("$domain did not take the exit: " + (e.message ?: e.toString()).take(140))
                    return
                }
            }

            // One real request through the server before the tunnel goes up.
            // Without it the interface came up for any domain at all - a
            // server that did not exist showed as connected, with every app's
            // traffic routed into nothing. A few seconds now buys a
            // "connected" that is true.
            say("checking the server answers")
            try {
                val seen = org.json.JSONObject(Relay.testServer(cfg, 20000L))
                // A server by address, met for the first time: its
                // certificate is kept, and required from then on.
                val seenPin = seen.optString("pin")
                if (cfg.pin.isEmpty() && seenPin.isNotEmpty()) {
                    cfg.pin = seenPin
                    getSharedPreferences("relay", MODE_PRIVATE)
                        .edit().putString("tunnelPin", seenPin).apply()
                }
            } catch (e: Exception) {
                fail("$domain did not carry a request: " + (e.message ?: e.toString()).take(140))
                return
            }

            say("opening the tunnel to $domain")

            val (builder, split) = buildInterface()
            if (!applySplit(c, split)) return

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
                "multi" -> "$domain (server + exit${if (exitPlace.isNotEmpty()) ", $exitPlace" else ""})"
                "bulk" -> "$domain (bulk)"
                else -> "$domain (your server)"
            }
            since = System.currentTimeMillis() / 1000
            connectedText = "connected through $exitName"
            say(connectedText)
            shareOnLan(c)
            watch(c) { redialServer(c) }
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
            // question the core cannot ask for itself. The same lookup as a
            // uid is what splitting by app asks, when it has to ask.
            val namer = AppNamer(applicationContext)
            Relay.setNamer(namer)
            Relay.setOwner(namer)

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
            // The configs behind the pick, as the window resolved them from
            // the row that was tapped - a country, a city, one provider's
            // share, one server, or Starred. Narrowed to exactly those, the
            // race takes the whole of what is left: asking the core to parse
            // the code again here is how a city or a single server came back
            // with nothing to race while a country connected.
            val narrowed = only.isNotBlank()
            if (narrowed) c.setOnly(only)
            if (c.count() == 0L) {
                fail(if (narrowed) "pick-gone" else "no pinned configs in $configDir")
                return
            }
            // A start with no list - an old intent, or Android restarting the
            // service on its own - still parses the code, as it always did.
            val pool = if (narrowed) "auto" else country

            total = c.count().toInt()
            say("racing ${c.count()} exits")
            val winner = race(c, pool)

            say("opening the tunnel via ${winner.name}")

            val (builder, split) = buildInterface()
            if (!applySplit(c, split)) return

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
            connectedText = "connected via ${winner.name} (${winner.addr}), ${winner.tookMs} ms"
            say(connectedText)
            shareOnLan(c)
            // A replacement comes from the same pick: the narrowed list again.
            watch(c) { switchExit(c, pool) }
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(e.message ?: e.toString())
        }
    }

    /**
     * One race over the pick, with the window told how it is going.
     *
     * Long literals, not Int. gomobile maps Go's int to Java long, and Kotlin
     * does not widen an Int to fill a Long parameter.
     */
    private fun race(c: Client, country: String): relay.Result {
        // The race keeps reporting after it has a winner - the exits still in
        // flight answer late - and each report used to overwrite the status,
        // so a connected tunnel read "asked 8 of 353" in the notification and
        // the window. Only the race's own lifetime counts.
        val racing = java.util.concurrent.atomic.AtomicBoolean(true)
        return try {
            c.raceIn(country, 6000L, 8L, object : Progress {
                override fun onProgress(n: Long, of: Long) {
                    if (!racing.get()) return
                    asked = n.toInt()
                    total = of.toInt()
                    say("asked $n of $of")
                }
            })
        } finally {
            racing.set(false)
        }
    }

    // -- noticing a tunnel that has stopped carrying ---------------------------

    /**
     * Watch a tunnel that is up, and do something when its exit stops carrying.
     *
     * Without this, "connected" was only ever true of the moment the tunnel
     * came up. Nothing looked again. When the exit stopped answering later -
     * filtered after a while on the line, overloaded, taken down - every new
     * connection waited out its fifteen seconds in the core and failed, every
     * DNS query waited out its ten, the phone went slow and then dead, and the
     * notification went on saying "Connected" for as long as anyone cared to
     * read it.
     *
     * Two signals, one cheap and one certain. The core counts connections the
     * exit itself failed in a row ([Client.health]); that costs nothing and is
     * read every few seconds. A count that climbs, or a minute with the screen
     * on, sends one real request through the exit ([Client.check]) - which is
     * the only way to catch an exit that takes CONNECT and then forwards
     * nothing, or a phone that is idle and so failing nothing.
     *
     * Two checks failing in a row is a stall, and [recover] runs. It does not
     * take the interface down: the exit is replaced under the running stack,
     * so apps see slow connections for a moment rather than a network that
     * vanishes, and nothing leaves by the bare line meanwhile.
     */
    private fun watch(c: Client, recover: suspend () -> Boolean) {
        watcher?.cancel()
        val power = getSystemService(PowerManager::class.java)
        watcher = scope.launch {
            var lastCheck = SystemClock.elapsedRealtime()
            var failsAtCheck = 0L
            var misses = 0
            // After a recovery that found nothing, wait longer each time: a
            // phone with no signal at all fails every check, and racing every
            // half a minute until it gets one back is a battery for nothing.
            var backoff = 0L
            while (isActive && client === c) {
                // A check that just failed is asked again almost at once: it
                // already waited out its own timeout, and every second spent
                // here is a second every app on the phone is waiting too.
                delay(if (misses > 0) RECHECK_AFTER_MS else WATCH_EVERY_MS + backoff)
                if (client !== c) break
                // Dozing, nothing on the phone has a network - this check
                // would fail and give up on a working exit for it.
                if (power?.isDeviceIdleMode == true) continue
                debugStall(c)
                val h = c.health() ?: break
                val now = SystemClock.elapsedRealtime()
                // Failures since the last check, not in all: a check that
                // passed does not reset the core's count, and an idle phone
                // would otherwise be checked every ten seconds after one bad
                // burst. A count that went down was reset by a new exit.
                if (h.fails < failsAtCheck) failsAtCheck = 0
                val failing = h.fails >= FAILS_BEFORE_CHECK && h.fails > failsAtCheck
                val due = now - lastCheck >= CHECK_EVERY_MS && power?.isInteractive != false
                if (!failing && !due && misses == 0 && !stalled) continue

                lastCheck = now
                failsAtCheck = h.fails
                val ok = try {
                    c.check(CHECK_TIMEOUT_MS)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "check through ${exitName.ifEmpty { exitAddress }} failed " +
                        "(${h.fails} in a row, last answer ${h.sinceOKMs / 1000}s ago): ${e.message}")
                    false
                }
                if (client !== c || !isActive) break

                if (ok) {
                    misses = 0
                    backoff = 0
                    if (stalled) {
                        // Back on its own - the line came back, or the
                        // exit did - without anything having been changed.
                        stalled = false
                        say(connectedText)
                    }
                    continue
                }
                misses++
                if (misses < MISSES_BEFORE_SWITCH) continue

                stalled = true
                val fixed = try {
                    recover()
                } catch (e: Exception) {
                    Log.w(TAG, "recovering the tunnel", e)
                    false
                }
                if (client !== c) break
                misses = 0
                lastCheck = SystemClock.elapsedRealtime()
                backoff = if (fixed) 0 else (backoff * 2).coerceIn(20_000L, 120_000L)
            }
        }
    }

    /**
     * Debug builds only: an exit dying under a connected tunnel, on demand.
     *
     *     adb shell run-as com.erelay.relay.debug touch files/debug-stall
     *
     * points the running tunnel at an address that answers nothing, and tells
     * the window nothing - which is exactly what the real failure looks like
     * from the phone. The watchdog is then expected to notice and move on.
     * A flag file rather than an intent because the service is not exported,
     * and should not be for this.
     */
    private fun debugStall(c: Client) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!java.io.File(filesDir, "debug-stall").delete()) return
        Log.w(TAG, "debug: pointing the tunnel at 192.0.2.1, which answers nothing")
        c.switchExit("192.0.2.1", exitName, exitProvider)
    }

    /**
     * A provider exit that stopped carrying, replaced by another from the same
     * pick. True when traffic is moving again.
     */
    private fun switchExit(c: Client, country: String): Boolean {
        val dead = exitAddress
        say("reconnecting: ${exitName.ifEmpty { dead }} stopped answering")
        // Out of this race and every one after it on this Client: it still
        // answers the race's quick question more often than not, which is how
        // it would win again and carry nothing again.
        c.avoid(dead)
        val winner = try {
            race(c, country)
        } catch (e: Exception) {
            if (client === c) say("reconnecting: no other exit answered - " +
                (e.message ?: e.toString()).take(100))
            return false
        }
        if (client !== c) return false
        c.switchExit(winner.addr, winner.name, winner.provider)
        Log.i(TAG, "switched from $dead to ${winner.addr} (${winner.name})")
        exitAddress = winner.addr
        exitName = winner.name
        exitFile = winner.file
        exitProvider = winner.provider
        tookMs = winner.tookMs.toInt()
        // Where it comes out is a fact about the old exit until asked again.
        seenCountry = ""
        since = System.currentTimeMillis() / 1000
        stalled = false
        connectedText = "connected via ${winner.name} (${winner.addr}), ${winner.tookMs} ms"
        say(connectedText)
        return true
    }

    /**
     * The user's own server, stopped carrying. There is no other server to go
     * to, so the session is dropped and dialled again - a websocket that went
     * quiet is not closed until smux's keepalive gives up on it, and until
     * then every connection is opened on a corpse.
     */
    private fun redialServer(c: Client): Boolean {
        say("reconnecting: $exitAddress stopped answering")
        c.resetServer()
        try {
            c.check(CHECK_TIMEOUT_MS)
        } catch (e: Exception) {
            if (client === c) say("reconnecting: $exitAddress is not answering - " +
                (e.message ?: e.toString()).take(100))
            return false
        }
        if (client !== c) return false
        stalled = false
        say(connectedText)
        return true
    }

    private fun stopTunnel() {
        watcher?.cancel()
        watcher = null
        stalled = false
        // A race in flight - the first one, or one replacing a stalled exit -
        // ends here rather than finishing on its own and opening something
        // nobody asked for any more.
        try {
            client?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stopping the race", e)
        }
        seenCountry = ""
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
        // The namer goes with it, in both its roles: it holds a Context and
        // has nothing to answer about once nothing is being carried.
        try {
            Relay.setProtector(null)
            Relay.setNamer(null)
            Relay.setOwner(null)
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

    @Volatile private var lastProgressAt = 0L

    private fun say(text: String) {
        status = text
        // A race reports once per exit it asks - hundreds, in a second or two.
        // Each was a notification and a broadcast, and each broadcast redrew
        // the window: the stutter while connecting. The count is still kept
        // exact in `asked`; it is only told about a few times a second.
        if (text.startsWith("asked")) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastProgressAt < 200) return
            lastProgressAt = now
        }
        Log.i(TAG, text)
        notificationManager().notify(NOTIFICATION, notification(text))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    /**
     * Share the tunnel on the Wi-Fi or hotspot, when Settings says to. It
     * stops with the tunnel (the core closes it), so there is no stop path
     * here; a failure is logged and the connection carries on without it.
     */
    private fun shareOnLan(c: Client) {
        val p = getSharedPreferences("relay", MODE_PRIVATE)
        if (!p.getBoolean("lanShare", false)) return
        try {
            val said = c.startLANShare(p.getInt("lanSharePort", 8877).toLong(),
                p.getString("lanSharePassword", "").orEmpty())
            Log.i(TAG, "sharing on the local network: $said")
        } catch (e: Exception) {
            Log.w(TAG, "sharing on the local network: ${e.message}")
        }
    }

    private fun fail(text: String) {
        say("failed: $text")
    }

    /**
     * The status line, said for a person rather than a log: a title that is
     * the state, and the detail under it. The log keeps the raw line.
     */
    private fun spoken(text: String): Pair<String, String> {
        val place = when {
            seenCountry.isNotEmpty() -> com.erelay.relay.ui.countryName(seenCountry)
            exitProvider.isNotEmpty() -> com.erelay.relay.ui.countryName(exitName.take(2))
            exitName.contains("server + exit") -> "your server's exit"
            exitAddress.isNotEmpty() -> "your server"
            else -> ""
        }
        return when {
            text.startsWith("connected") -> "Connected to ${place.ifEmpty { "Relay" }}" to
                (if (exitProvider.isNotEmpty()) exitName else exitAddress)
            text.startsWith("reconnecting") -> "Reconnecting…" to
                text.removePrefix("reconnecting: ").replaceFirstChar { it.uppercase() }
            text.startsWith("failed") -> "Could not connect" to
                text.removePrefix("failed: ").replaceFirstChar { it.uppercase() }
            text.startsWith("asked") -> "Connecting…" to "Asked ${asked} of $total exits"
            text.startsWith("racing") -> "Connecting…" to text.replaceFirstChar { it.uppercase() }
            text.startsWith("opening") || text.startsWith("finding") ||
                text.startsWith("checking") -> "Connecting…" to text.replaceFirstChar { it.uppercase() }
            else -> "Relay" to text.replaceFirstChar { it.uppercase() }
        }
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
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Disconnect from the shade, without opening anything.
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // NotificationCompat rather than Notification.Builder: the
        // channel-taking constructor is API 26 and minSdk here is 24, and the
        // compat one does the right thing on both without a version branch.
        val (title, body) = spoken(text)
        // Connected, the header counts up how long for: the platform's own
        // chronometer, ticking without a notification update every second.
        val up = text.startsWith("connected") && since > 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setShowWhen(up)
            .setUsesChronometer(up)
            .setWhen(if (up) since * 1000 else System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_relay)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
