package com.erelay.relay.data

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.PersistableBundle
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.erelay.relay.Accounts
import com.erelay.relay.AppFiles
import com.erelay.relay.Import
import com.erelay.relay.Pin
import com.erelay.relay.Providers
import com.erelay.relay.RelayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import relay.Client
import relay.PinProgress
import relay.ReachProgress
import relay.Relay

/** A yes or a sentence: what every action hands back to the screen. */
data class Said(val ok: Boolean, val text: String = "") {
    companion object {
        val OK = Said(true)
        fun no(why: String) = Said(false, why)
    }
}

/**
 * Everything the window knows and can do, in one place.
 *
 * What MainActivity and Bridge.kt were in the Flutter build, minus the page:
 * the same calls into the core and the same preferences under the same names,
 * so an install over the Flutter build keeps its picks, stars and server.
 * Results are typed state instead of JSON for a page to parse.
 *
 * Every call into Go blocks, so every one of them runs on [io]. The screens
 * read the flows and call the suspend functions from their own scope.
 *
 * [RelayVpnService] still owns the tunnel. Nothing here is bound to it: it
 * publishes into its companion and broadcasts, and this reads.
 */
class RelayCore(private val app: Application) {

    private val prefs = app.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Dispatchers.IO

    // -- state ----------------------------------------------------------------

    private val _conn = MutableStateFlow(Conn())
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _realIp = MutableStateFlow<Place?>(null)
    val realIp: StateFlow<Place?> = _realIp.asStateFlow()
    private val _realIpAsked = MutableStateFlow(false)
    val realIpAsked: StateFlow<Boolean> = _realIpAsked.asStateFlow()

    /** What the internet says the tunnel comes out as. Null until checked. */
    private val _seen = MutableStateFlow<Place?>(null)
    val seen: StateFlow<Place?> = _seen.asStateFlow()
    private val _seenFailed = MutableStateFlow(false)
    val seenFailed: StateFlow<Boolean> = _seenFailed.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries.asStateFlow()
    private val _serverCount = MutableStateFlow(0)
    val serverCount: StateFlow<Int> = _serverCount.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()
    private val _source = MutableStateFlow(prefs.getString("source", "all") ?: "all")
    val source: StateFlow<String> = _source.asStateFlow()

    private val _picked = MutableStateFlow(prefs.getString("picked", "auto") ?: "auto")
    val picked: StateFlow<String> = _picked.asStateFlow()
    private val _favourites = MutableStateFlow(favouriteSet().toSet())
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()
    private val _sort = MutableStateFlow(Sort.of(prefs.getString("sortBy", "ping")))
    val sort: StateFlow<Sort> = _sort.asStateFlow()

    private val _way = MutableStateFlow(Way.of(prefs.getString("mode", "surfshark")))
    val way: StateFlow<Way> = _way.asStateFlow()
    private val _allowLan = MutableStateFlow(prefs.getBoolean("allowLan", true))
    val allowLan: StateFlow<Boolean> = _allowLan.asStateFlow()
    private val _lanShare = MutableStateFlow(prefs.getBoolean("lanShare", false))
    /** Settings → Share this connection: whether to offer the tunnel to the Wi-Fi it is on. */
    val lanShare: StateFlow<Boolean> = _lanShare.asStateFlow()
    private val _lanSharePort = MutableStateFlow(prefs.getInt("lanSharePort", 8877))
    val lanSharePort: StateFlow<Int> = _lanSharePort.asStateFlow()
    private val _lanSharePassword = MutableStateFlow(prefs.getString("lanSharePassword", "").orEmpty())
    val lanSharePassword: StateFlow<String> = _lanSharePassword.asStateFlow()
    private val _lanState = MutableStateFlow(LanShareState())
    /** What the core says the share is doing right now; refreshed while the screen is visible. */
    val lanState: StateFlow<LanShareState> = _lanState.asStateFlow()
    private val _tunnel = MutableStateFlow(readTunnel())
    val tunnel: StateFlow<TunnelPlan> = _tunnel.asStateFlow()

    // Up here with the rest of the state rather than beside the functions
    // that use them, because readService() runs from init and resets one of
    // them: a property declared below init is still null when init runs.
    private val _split = MutableStateFlow(readSplit())
    /** Settings → Split tunnel. The service reads the same prefs when it builds the interface. */
    val split: StateFlow<SplitPlan> = _split.asStateFlow()
    private val _iranApps = MutableStateFlow<Set<String>>(emptySet())
    /** The core's list of Iranian apps, by package. Empty until read, once, off the main thread. */
    val iranApps: StateFlow<Set<String>> = _iranApps.asStateFlow()
    private val _splitPending = MutableStateFlow(false)
    /**
     * The split tunnel was changed while a tunnel was up, which was built with
     * the old one. Cleared when that tunnel goes down or another is started.
     */
    val splitPending: StateFlow<Boolean> = _splitPending.asStateFlow()

    // Up here for the same reason as the split's: declared below init, a
    // property is still unset when something launched from init gets to it.
    private val _export = MutableStateFlow(readExport(ExportRun()))
    /** Settings → Share with other devices: whether it can run, how it is going, what it made last. */
    val export: StateFlow<ExportRun> = _export.asStateFlow()
    private val _exportPicks = MutableStateFlow(prefLines("exportPicks"))
    /** The locations chosen to share, as pick codes, in the order their slots are given out. */
    val exportPicks: StateFlow<List<String>> = _exportPicks.asStateFlow()
    /** Every location on the phone, for the share chooser; null until asked, and after the folder changes. */
    @Volatile private var shareList: List<Country>? = null

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()
    private val _cards = MutableStateFlow(mapOf(
        Accounts.SURFSHARK to ProviderCard(), Accounts.WINDSCRIBE to ProviderCard()))
    val cards: StateFlow<Map<String, ProviderCard>> = _cards.asStateFlow()

    private val _quickAt = MutableStateFlow(prefs.getLong("quickAt", 0L))
    /** When one exit per country was last timed, in epoch milliseconds; 0 for never. */
    val quickAt: StateFlow<Long> = _quickAt.asStateFlow()
    private val _autoQuick = MutableStateFlow(prefs.getBoolean("autoQuick", true))
    /** Whether opening the exit list times a stale list on its own. */
    val autoQuick: StateFlow<Boolean> = _autoQuick.asStateFlow()

    fun setAutoQuick(on: Boolean) {
        _autoQuick.value = on
        prefs.edit().putBoolean("autoQuick", on).apply()
    }

    private val _reach = MutableStateFlow(ReachRun())
    val reach: StateFlow<ReachRun> = _reach.asStateFlow()

    private val _dead = MutableStateFlow(DeadExits())
    val dead: StateFlow<DeadExits> = _dead.asStateFlow()

    private val _pinPlan = MutableStateFlow(PinPlan())
    val pinPlan: StateFlow<PinPlan> = _pinPlan.asStateFlow()
    private val _pinRun = MutableStateFlow(PinRun())
    val pinRun: StateFlow<PinRun> = _pinRun.asStateFlow()

    private val _introSeen = MutableStateFlow(prefs.getBoolean("introSeen", false))
    val introSeen: StateFlow<Boolean> = _introSeen.asStateFlow()

    fun markIntroSeen() {
        prefs.edit().putBoolean("introSeen", true).apply()
        _introSeen.value = true
    }

    private val _setup = MutableStateFlow(Setup())
    val setup: StateFlow<Setup> = _setup.asStateFlow()

    /**
     * Make a signed-in provider usable: fetch its published servers, then pin
     * them to real addresses, one after the other. An account on its own is
     * not something to connect with - the folder it races is empty until
     * both have run - so this runs on its own the moment an account is
     * added, rather than waiting for somebody to find two buttons in Settings.
     *
     * Every signed-in provider with no servers yet, or just [only].
     */
    suspend fun prepareServers(only: String? = null): Said = withContext(io) {
        if (_setup.value.running) return@withContext Said.no("Already getting servers ready.")
        val todo = listOf(Accounts.SURFSHARK, Accounts.WINDSCRIBE).filter { p ->
            if (only != null) p == only
            else credentials(p) != null && (_cards.value[p]?.servers ?: 0) == 0
        }
        for (p in todo) {
            _setup.value = Setup(running = true, step = "servers", provider = p)
            val got = if (p == Accounts.SURFSHARK) surfsharkServers() else windscribeServers(freeOnly = false)
            if (!got.ok) {
                _setup.value = Setup(step = "failed", provider = p, error = got.text)
                return@withContext got
            }
            _setup.value = Setup(running = true, step = "pinning", provider = p)
            val aimed = pinFor(p)
            if (aimed.ok) {
                val started = startPin()
                if (!started.ok) {
                    _setup.value = Setup(step = "failed", provider = p, error = started.text)
                    return@withContext started
                }
                pinRun.first { !it.running }
            }
            refreshList()
        }
        if (_serverCount.value == 0) {
            val why = _pinRun.value.finished.ifEmpty { "No server answered. Try again in a moment." }
            _setup.value = Setup(step = "failed", error = why)
            return@withContext Said.no(why)
        }
        _setup.value = Setup(step = "done")
        Said.OK
    }

    private val _feel = MutableStateFlow(readFeel())
    val feel: StateFlow<Feel> = _feel.asStateFlow()

    private fun readFeel() = Feel(
        theme = prefs.getString("theme", "midnight") ?: "midnight",
        haptics = prefs.getBoolean("haptics", true),
        orbTap = prefs.getBoolean("orbTap", true),
        orbMotion = prefs.getBoolean("orbMotion", true),
        meter = prefs.getBoolean("meter", true),
    )

    fun setFeel(next: Feel) {
        prefs.edit()
            .putString("theme", next.theme)
            .putBoolean("haptics", next.haptics)
            .putBoolean("orbTap", next.orbTap)
            .putBoolean("orbMotion", next.orbMotion)
            .putBoolean("meter", next.meter)
            .apply()
        _feel.value = next
    }

    /**
     * Whether there is an account to connect with: Connect is off without one.
     * Kept, not read on demand - the window asked on every recomposition, which
     * was two files off the disk on the main thread for every redraw.
     */
    private val _hasCredentials = MutableStateFlow(false)
    val hasCredentials: StateFlow<Boolean> = _hasCredentials.asStateFlow()

    private fun readHasCredentials() {
        _hasCredentials.value = credentials(Accounts.SURFSHARK) != null ||
            credentials(Accounts.WINDSCRIBE) != null
    }

    // -- the service ------------------------------------------------------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = readService()
    }

    init {
        ContextCompat.registerReceiver(
            app, statusReceiver, IntentFilter(RelayVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        readService()
        scope.launch {
            Accounts.adopt(app)
            refreshList()
            refreshAccounts()
            refreshDead()
        }
        scope.launch { iranSet() }
        whoami()
    }

    /**
     * The service's companion, read into [conn].
     *
     * It publishes one sentence and a handful of numbers; the four looks
     * come from those. "failed: x" is the core's own word for why, and the
     * screens turn that word into advice.
     */
    private fun readService() {
        val note = RelayVpnService.status
        val up = RelayVpnService.exitAddress.isNotEmpty()
        val failed = note.startsWith("failed")
        val busy = !up && !failed && (RelayVpnService.total > 0 ||
            note.startsWith("racing") || note.startsWith("asked") ||
            note.startsWith("opening") || note.startsWith("finding") ||
            note.startsWith("checking"))
        val mode = when {
            // Up, but its exit stopped carrying and another is being found.
            // Shown as working rather than as on: "connected" here is the
            // claim that was false for as long as nothing checked it.
            up && RelayVpnService.stalled -> Mode.BUSY
            up -> Mode.ON
            failed -> Mode.FAIL
            busy -> Mode.BUSY
            // A connect asked for but not yet seen by the service keeps the
            // window busy rather than flicking back to off.
            _conn.value.mode == Mode.BUSY && connecting -> Mode.BUSY
            else -> Mode.OFF
        }
        if (mode != Mode.BUSY) connecting = false
        // No tunnel, so none built with an old split: the next one reads the new.
        if (mode == Mode.OFF || mode == Mode.FAIL) _splitPending.value = false
        val was = _conn.value
        _conn.value = Conn(
            mode = mode,
            note = note,
            asked = RelayVpnService.asked,
            total = RelayVpnService.total,
            exitAddress = RelayVpnService.exitAddress,
            exitName = RelayVpnService.exitName,
            exitProvider = RelayVpnService.exitProvider,
            since = RelayVpnService.since,
            failure = if (failed) note.removePrefix("failed: ").removePrefix("failed") else null,
        )
        if (up && was.exitAddress != RelayVpnService.exitAddress) startSeenAs()
        if (!up) {
            seenFor = ""
            _seen.value = null
            _seenFailed.value = false
        }
    }

    @Volatile private var connecting = false

    // -- addresses ----------------------------------------------------------------

    /** The address this phone has without the tunnel. Asked once, on the way up. */
    fun whoami() {
        scope.launch {
            val json = try { Relay.whereAmIJSON(8000L) } catch (e: Exception) { "{}" }
            _realIp.value = parsePlace(json)
            _realIpAsked.value = true
        }
    }

    @Volatile private var seenFor = ""

    /**
     * Ask, through the tunnel, where the traffic really comes out.
     *
     * Once per exit, with the winning exit's own provider credential - the
     * other provider's is refused and would read as an exit that cannot say
     * where it is. An empty answer is not remembered, so one timeout does not
     * stick for the life of the tunnel.
     */
    private fun startSeenAs() {
        val addr = RelayVpnService.exitAddress
        val name = RelayVpnService.exitName
        if (addr.isEmpty() || seenFor == addr) return
        val creds = credentials(
            if (RelayVpnService.exitProvider == Accounts.WINDSCRIBE) Accounts.WINDSCRIBE
            else Accounts.SURFSHARK) ?: credentials(Accounts.SURFSHARK)
        seenFor = addr
        _seen.value = null
        _seenFailed.value = false
        scope.launch {
            val json = try {
                // The user's own server: asked through the tunnel that is up,
                // because a server is not a provider exit to rebuild.
                if (RelayVpnService.exitProvider.isEmpty()) Relay.seenAsLiveJSON(20000L)
                else if (creds == null) "{}"
                else Relay.seenAsJSON(addr, name, creds.first, creds.second, 20000L)
            } catch (e: Exception) {
                "{}"
            }
            val place = parsePlace(json)
            if (RelayVpnService.exitAddress != addr) return@launch
            if (place == null) {
                seenFor = ""
                _seenFailed.value = true
            } else {
                _seen.value = place
                try {
                    app.startService(Intent(app, RelayVpnService::class.java)
                        .setAction(RelayVpnService.ACTION_SEEN)
                        .putExtra(RelayVpnService.EXTRA_SEEN_COUNTRY, place.country))
                } catch (e: Exception) {
                    // The notification keeps the claimed country; nothing else depends on it.
                    Log.w(RelayVpnService.TAG, "seen: ${e.message}")
                }
            }
        }
    }

    // -- the meter ------------------------------------------------------------------

    private var meterJob: Job? = null
    private var lastAt = 0L
    private var lastUp = 0L
    private var lastDown = 0L

    /**
     * Once a second while a window is showing, and not at all otherwise. A
     * tunnel with nobody looking should spend its battery on traffic, not on
     * counting it.
     */
    fun meter(on: Boolean) {
        meterJob?.cancel()
        if (!on) return
        meterJob = scope.launch {
            while (isActive) {
                _traffic.value = readTraffic()
                delay(1000)
            }
        }
    }

    /** Summed from the same rows the log sheet lists, so the two never disagree. */
    private fun readTraffic(): Traffic {
        var up = 0L
        var down = 0L
        var open = 0
        for (r in parseHosts(RelayVpnService.hostsJSON())) {
            up += r.up
            down += r.down
            open += r.live
        }
        val now = System.nanoTime()
        val seconds = if (lastAt == 0L) 0.0 else (now - lastAt) / 1e9
        val upRate = if (seconds > 0.05) ((up - lastUp) / seconds).toLong() else 0L
        val downRate = if (seconds > 0.05) ((down - lastDown) / seconds).toLong() else 0L
        lastAt = now
        lastUp = up
        lastDown = down
        return Traffic(
            live = RelayVpnService.exitAddress.isNotEmpty(),
            up = up, down = down,
            upRate = maxOf(0L, upRate), downRate = maxOf(0L, downRate),
            open = open,
        )
    }

    suspend fun hosts(): List<HostRow> = withContext(io) { parseHosts(RelayVpnService.hostsJSON()) }

    // -- the catalogue -----------------------------------------------------------
    //
    // One client, built once and kept, rather than a fresh scan per question.
    // Built with both providers' credentials, because the reach test asks the
    // exits a question and an exit asked without its credential says no.

    private var cat: Client? = null

    @Synchronized
    private fun catalogue(): Client {
        cat?.let { return it }
        val c = Relay.newClient("", "")
        for (name in listOf(Accounts.SURFSHARK, Accounts.WINDSCRIBE)) {
            credentials(name)?.let { (user, password) -> c.setCredentials(name, user, password) }
        }
        try {
            c.load()
        } catch (e: Exception) {
            // An empty folder is a phone nobody has put configs on yet, which
            // the window has a whole screen for.
            Log.i(RelayVpnService.TAG, "nothing to read yet: ${e.message}")
        }
        if (_source.value == "starred") {
            val files = starredFiles(c)
            if (files.isNotEmpty()) {
                try { c.setOnly(files) } catch (e: Exception) {
                    Log.w(RelayVpnService.TAG, "starred: ${e.message}")
                }
            }
        }
        cat = c
        return c
    }

    @Synchronized
    private fun forget() {
        cat = null
    }

    /** Re-read the folder and everything drawn from it. */
    suspend fun refreshList() = withContext(io) {
        forget()
        shareList = null
        val c = catalogue()
        _countries.value = parseCountries(try { c.countriesJSON() } catch (e: Exception) { "[]" })
        _serverCount.value = c.count().toInt()
        _sources.value = readSources(c)
        _cards.value = readCards()
        // Before loaded, never after: the window picks between the welcome and
        // the main screen the moment this flips, and with the accounts not yet
        // read it showed the welcome to a phone that was signed in, for a blink.
        readHasCredentials()
        _loaded.value = true
    }

    private fun starredFiles(c: Client): String = buildString {
        for (code in favouriteSet()) {
            val names = try { c.poolFiles(code) } catch (e: Exception) { "" }
            if (names.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(names)
            }
        }
    }

    private fun counts(c: Client, files: String): JSONObject = try {
        JSONObject(c.countsJSON(files))
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * The pools worth offering: Everything, and Starred when it holds
     * something. counts("") means Everything, so an empty Starred is checked
     * by its names first - otherwise it comes back as the whole world.
     */
    private fun readSources(c: Client): List<Source> {
        val rows = mutableListOf<Source>()
        val all = counts(c, "")
        if (all.optInt("exits") > 0) {
            rows += Source("all", "Everything",
                "${all.optInt("exits")} exits · every folder the app reads", all.optInt("places"))
        }
        val names = starredFiles(c)
        val starred = if (names.isBlank()) JSONObject() else counts(c, names)
        if (favouriteSet().isNotEmpty() && starred.optInt("exits") > 0) {
            rows += Source("starred", "Starred",
                "${starred.optInt("exits")} exits · only the places you picked out",
                starred.optInt("places"))
        }
        if (rows.none { it.key == _source.value }) _source.value = "all"
        return rows
    }

    suspend fun setSource(key: String): Said = withContext(io) {
        if (key != "all" && key != "starred") return@withContext Said.no("There is nothing in that one.")
        if (key == "starred" && starredFiles(catalogue()).isBlank()) {
            return@withContext Said.no("There is nothing in that one.")
        }
        prefs.edit().putString("source", key).apply()
        _source.value = key
        refreshList()
        Said.OK
    }

    /** The individual exits behind a row: `fr`, `fr/par`, with or without `:provider`. */
    suspend fun exitsIn(code: String): List<Exit> = withContext(io) {
        parseExits(try { catalogue().exitsInJSON(code) } catch (e: Exception) { "{}" })
    }

    /**
     * What each provider's card says: exits, an account, and configs waiting
     * to be pinned - asked in that order by the card's one button.
     */
    private fun readCards(): Map<String, ProviderCard> {
        var surfshark = 0
        var windscribe = 0
        for (name in AppFiles.pinned(app).list() ?: emptyArray()) {
            if (!name.endsWith(".ovpn")) continue
            if (name.contains(".ws.")) windscribe++ else surfshark++
        }
        val roster = _accounts.value
        return mapOf(
            Accounts.SURFSHARK to ProviderCard(
                servers = surfshark,
                account = roster.any { it.provider == Accounts.SURFSHARK && it.signedIn },
                waiting = AppFiles.waiting(AppFiles.configs(app), ".prod."),
            ),
            Accounts.WINDSCRIBE to ProviderCard(
                servers = windscribe,
                account = roster.any { it.provider == Accounts.WINDSCRIBE && it.signedIn },
                waiting = AppFiles.waiting(AppFiles.windscribe(app), ".ws."),
            ),
        )
    }

    // -- small remembered things --------------------------------------------------

    private fun favouriteSet(): MutableSet<String> =
        (prefs.getStringSet("favourites", emptySet()) ?: emptySet()).toMutableSet()

    suspend fun toggleFavourite(code: String): Boolean = withContext(io) {
        val set = favouriteSet()
        val on = !set.remove(code)
        if (on) set.add(code)
        prefs.edit().putStringSet("favourites", set).apply()
        _favourites.value = set.toSet()
        // Starring changes what Starred holds, and may empty it.
        if (_source.value == "starred") refreshList()
        else _sources.value = readSources(catalogue())
        on
    }

    fun setSort(sort: Sort) {
        prefs.edit().putString("sortBy", sort.key).apply()
        _sort.value = sort
    }

    fun remember(code: String) {
        prefs.edit().putString("picked", code).apply()
        _picked.value = code
    }

    // -- the connection ------------------------------------------------------------

    /** Android's consent dialog, or null once it has been given. */
    fun consent(): Intent? = VpnService.prepare(app)

    fun consentRefused() = RelayVpnService.report(app, "failed: permission refused")

    /**
     * Android 13 hides every notification until the app is allowed to post
     * them - including the VPN's own, which is the only way to see the tunnel
     * with the window closed. Asked once, on the first connect; a no is a no.
     */
    fun shouldAskNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotifications", false)

    fun askedNotifications() = prefs.edit().putBoolean("askedNotifications", true).apply()

    /**
     * Start the service toward [code]. The consent dialog has to have been
     * answered already - [consent] is the screen's job, because only an
     * Activity can show it.
     */
    /**
     * Whether the chosen way out has what it needs. Asked before Android's
     * consent dialog, so nobody grants a VPN to an app that then says it had
     * nothing to connect to.
     */
    fun ready(): Said = when (_way.value) {
        Way.PROVIDER -> when {
            AppFiles.count(AppFiles.pinned(app)) == 0 ->
                Said.no("No servers yet. Get them under Settings → Accounts, " +
                    "or import a folder of configs under Settings → Servers.")
            credentials(Accounts.SURFSHARK) == null && credentials(Accounts.WINDSCRIBE) == null ->
                Said.no("No account yet. Add one under Settings → Accounts.")
            else -> Said.OK
        }
        else -> if (_tunnel.value.ready) Said.OK
            else Said.no("Set the domain and passwords under Settings → Your own tunnel first.")
    }

    fun connect(code: String): Said {
        ready().let { if (!it.ok) return it }
        val intent = Intent(app, RelayVpnService::class.java)
            .setAction(RelayVpnService.ACTION_START)
        val way = _way.value
        if (way == Way.PROVIDER) {
            // One is enough to start: the core drops the exits there is no
            // credential for and only refuses when that leaves nothing.
            //
            // Each provider's own, and only its own. Surfshark's slot used to
            // take Windscribe's login when there was no Surfshark account, so
            // every Surfshark exit was asked with the wrong one and refused -
            // hidden in a country race by the Windscribe exits beside it, and
            // the whole answer for a Surfshark city or server picked alone.
            val surfshark = credentials(Accounts.SURFSHARK)
            val windscribe = credentials(Accounts.WINDSCRIBE)
            if (surfshark == null && windscribe == null) {
                return Said.no("No account yet. Add one under Settings → Accounts.")
            }
            // The exact configs behind the row that was tapped, read off the
            // same catalogue that drew it. The service used to be handed only
            // the code and work the pool out again from a scan of its own -
            // through RaceIn, which for years knew country codes and nothing
            // else. A country always connected; a city ("de/ber"), a provider
            // ("fr:windscribe") or one server ("file:x.ovpn") raced nothing
            // whenever the two readings disagreed: a core built before RaceIn
            // learnt those shapes (nothing fails to link - the signature never
            // changed), or a city key worked out from notes that had moved on
            // since the list was drawn. Starred has always been sent this way;
            // now every pick is, so what is raced is what was on screen.
            val c = catalogue()
            val only = when {
                code != "auto" -> try { c.poolFiles(code) } catch (e: Exception) { "" }
                _source.value == "starred" -> starredFiles(c)
                else -> ""
            }
            if (code != "auto" && only.isBlank()) {
                return Said.no("Nothing is left behind that pick - its servers were re-pinned " +
                    "or set aside. Pick it again from the list.")
            }
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, "surfshark")
                .putExtra(RelayVpnService.EXTRA_USER, surfshark?.first.orEmpty())
                .putExtra(RelayVpnService.EXTRA_PASSWORD, surfshark?.second.orEmpty())
                .putExtra(RelayVpnService.EXTRA_CONFIG_DIR, AppFiles.pinned(app).absolutePath)
                .putExtra(RelayVpnService.EXTRA_COUNTRY, code)
            windscribe?.let { (user, password) ->
                intent.putExtra(RelayVpnService.EXTRA_WS_USER, user)
                    .putExtra(RelayVpnService.EXTRA_WS_PASSWORD, password)
            }
            if (only.isNotBlank()) intent.putExtra(RelayVpnService.EXTRA_ONLY, only)
        } else {
            val domain = prefs.getString("tunnelDomain", "").orEmpty()
            val password = prefs.getString("tunnelPassword", "").orEmpty()
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, way.key)
                .putExtra(RelayVpnService.EXTRA_DOMAIN, domain)
                .putExtra(RelayVpnService.EXTRA_TUNNEL_PASSWORD, password)
                .putExtra(RelayVpnService.EXTRA_EDGES, prefs.getString("tunnelEdges", "").orEmpty())
                .putExtra(RelayVpnService.EXTRA_PIN, prefs.getString("tunnelPin", "").orEmpty())
            if (way == Way.SERVER_EXIT) {
                // The location picked, and every provider account on file:
                // the server leaves by that place's exit, opened with that
                // exit's own provider's credential, sent as it connects.
                intent
                    .putExtra(RelayVpnService.EXTRA_COUNTRY, code)
                    .putExtra(RelayVpnService.EXTRA_API_PASSWORD,
                        prefs.getString("tunnelApiPassword", "").orEmpty())
                credentials(Accounts.SURFSHARK)?.let { (user, password) ->
                    intent.putExtra(RelayVpnService.EXTRA_USER, user)
                        .putExtra(RelayVpnService.EXTRA_PASSWORD, password)
                }
                credentials(Accounts.WINDSCRIBE)?.let { (user, password) ->
                    intent.putExtra(RelayVpnService.EXTRA_WS_USER, user)
                        .putExtra(RelayVpnService.EXTRA_WS_PASSWORD, password)
                }
            }
        }
        connecting = true
        // This one is built from the split as it is now.
        _splitPending.value = false
        _conn.update { it.copy(mode = Mode.BUSY, failure = null, asked = 0, total = 0) }
        ContextCompat.startForegroundService(app, intent)
        return Said.OK
    }

    fun disconnect() {
        connecting = false
        app.startService(Intent(app, RelayVpnService::class.java)
            .setAction(RelayVpnService.ACTION_STOP))
    }

    // -- reach tests and dead exits ---------------------------------------------------

    /**
     * Ask the exits behind one row whether they answer, and keep the verdicts.
     * One row at a time: a whole-folder run is where the line starts dropping
     * connections and the answers stop being about the exits.
     */
    fun testReach(code: String, timeoutMs: Long = 5000L, width: Long = 12L): Said {
        if (_reach.value.running) return Said.no("A test is already running.")
        if (otherVpn()) {
            return Said.no("Another VPN is on. Its tunnel would answer for every server - " +
                "turn it off first.")
        }
        val c = catalogue()
        val pool = try { c.poolFiles(code) } catch (e: Exception) { "" }
        if (pool.isBlank()) return Said.no("Nothing to test.")
        val total = pool.lines().count { it.isNotBlank() }
        _reach.value = ReachRun(code = code, total = total, running = true)
        scope.launch {
            val answer = try {
                JSONObject(c.testReach(code, timeoutMs, width, object : ReachProgress {
                    override fun onReach(payload: String) {
                        val p = try { JSONObject(payload) } catch (e: Exception) { return }
                        _reach.update { it.copy(done = p.optInt("done", it.done),
                            total = p.optInt("total", it.total)) }
                    }
                }))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.toString())
            }
            val said = when {
                !answer.optBoolean("ok") -> answer.optString("error", "The test did not run.").let {
                    if (it.startsWith("no credentials for ")) "No ${it.removePrefix("no credentials for ")
                        .replaceFirstChar { c -> c.uppercase() }} account to test with - add one under " +
                        "Settings → Accounts." else it
                }
                answer.optBoolean("cancelled") -> "Stopped."
                code == QUICK -> "${answer.optInt("answered")} of ${answer.optInt("tested")} countries answered."
                else -> "${answer.optInt("answered")} of ${answer.optInt("tested")} answered."
            }
            if (code == QUICK && answer.optBoolean("ok") && !answer.optBoolean("cancelled")) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("quickAt", now).apply()
                _quickAt.value = now
            }
            _reach.value = ReachRun(code = code, running = false, said = said)
            refreshList()
            refreshDead()
        }
        return Said.OK
    }

    /**
     * One exit per country, so every row of the list has a time. Six at a
     * time with a short wait: a few dozen small requests, gentle on the line
     * and on the providers' logins.
     *
     * Only with the tunnel down. Through it, every exit would be timed from
     * wherever the tunnel comes out, which says nothing about this line.
     */
    fun quickTest(): Said {
        if (_conn.value.mode == Mode.ON || _conn.value.mode == Mode.BUSY) {
            return Said.no("Disconnect first: through the tunnel, the times would be the tunnel's.")
        }
        return testReach(QUICK, 3500L, 6L)
    }

    /** Whether the quick test is old enough to run again on its own. */
    fun quickDue(): Boolean =
        _autoQuick.value && System.currentTimeMillis() - _quickAt.value > QUICK_EVERY_MS && !otherVpn() &&
            !_reach.value.running && _conn.value.mode != Mode.ON && _conn.value.mode != Mode.BUSY

    /**
     * Whether some other app's VPN carries this phone's traffic. Through it,
     * every server is answered by that tunnel - a whole list timed at 1 ms -
     * so nothing is timed while one is up. Relay's own tunnel does not count:
     * the core's probes are protected from it.
     */
    private fun otherVpn(): Boolean {
        if (_conn.value.mode == Mode.ON || _conn.value.mode == Mode.BUSY) return false
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun cancelReach() {
        scope.launch { try { catalogue().cancelReach() } catch (e: Exception) { } }
    }

    suspend fun refreshDead() = withContext(io) {
        _dead.value = parseDead(try { catalogue().deadExitsJSON() } catch (e: Exception) { "{}" })
    }

    /** Move the dead ones out of the folders that were ticked. */
    suspend fun dropExits(folders: List<String>): Said = withContext(io) {
        val o = JSONObject(catalogue().dropExits(folders.joinToString("\n")))
        refreshList()
        refreshDead()
        if (o.optBoolean("ok")) Said(true, "Set ${o.optInt("moved")} aside.")
        else Said.no(o.optString("error", "Nothing was moved."))
    }

    suspend fun restoreDropped(tags: List<String>): Said = withContext(io) {
        val o = JSONObject(catalogue().restoreDropped(tags.joinToString("\n")))
        refreshList()
        refreshDead()
        if (o.optBoolean("ok")) Said(true, "Put ${o.optInt("back")} back.")
        else Said.no(o.optString("error", "Nothing came back."))
    }

    // -- importing -----------------------------------------------------------------

    /**
     * Configs, copied in from a folder somebody picked. A picked folder is a
     * content URI and the core wants a path, so they are copied into the
     * app's own folders, Surfshark and Windscribe each into its own pile.
     */
    suspend fun importTree(uri: Uri): Said = withContext(io) {
        val before = AppFiles.count(AppFiles.configs(app)) to AppFiles.count(AppFiles.windscribe(app))
        val got = Import.fromTree(app, uri)
        if (got.seen == 0) return@withContext Said.no("No .ovpn files in that folder.")
        val after = AppFiles.count(AppFiles.configs(app)) to AppFiles.count(AppFiles.windscribe(app))
        val folder = if (after.second - before.second > after.first - before.first)
            AppFiles.windscribe(app) else AppFiles.configs(app)
        Pin.rememberInbox(app, folder)
        refreshList()
        refreshPinPlan()
        Said(true, "Copied ${got.copied}" +
            (if (got.skipped > 0) ", ${got.skipped} already here" else "") + ".")
    }

    // -- accounts ----------------------------------------------------------------------

    private fun credentials(provider: String) = Accounts.credentials(app, provider)

    suspend fun refreshAccounts() = withContext(io) {
        _accounts.value = parseAccounts(Accounts.listing(app))
        readHasCredentials()
        _cards.value = readCards()
    }

    /**
     * Surfshark has no sign-in: the account is a service username and
     * password the exits check. An address in the username field is worth
     * naming, because every exit refuses it with the same silence as a wrong
     * password.
     */
    suspend fun addSurfshark(label: String, user: String, password: String): Said = withContext(io) {
        val username = user.trim()
        if (username.isEmpty()) return@withContext Said.no("Enter the username.")
        if (password.isEmpty()) return@withContext Said.no("Enter the password.")
        if (username.contains("@")) {
            return@withContext Said.no("That looks like your login email. Surfshark issues a " +
                "separate service username for manual setups - it is on the same page as " +
                "the config files.")
        }
        Accounts.put(app, Accounts.SURFSHARK, label.trim(), username, password = password)
        forget()
        refreshAccounts()
        Said.OK
    }

    private class Signin(val username: String, val password: String, val label: String)

    @Volatile private var pending: Signin? = null

    /** Half one of a Windscribe sign-in: fetch the puzzle. */
    suspend fun beginWindscribe(label: String, user: String, password: String):
        Pair<Said, Pair<String, Captcha>?> = withContext(io) {
        val username = user.trim()
        if (username.isEmpty()) return@withContext Said.no("Enter the username.") to null
        if (password.isEmpty()) return@withContext Said.no("Enter the password.") to null
        val begun = Providers.begin(username, password)
        if (!begun.optBoolean("ok")) {
            return@withContext Said.no(begun.optString("error", "Windscribe did not answer.")) to null
        }
        val captcha = parseCaptcha(begun.optJSONObject("captcha"))
            ?: return@withContext Said.no("Windscribe sent no puzzle.") to null
        pending = Signin(username, password, label.trim())
        Said.OK to (begun.optString("token") to captcha)
    }

    /**
     * Half two, sent the moment the puzzle is released. One attempt: a token
     * is spent whether the answer was right or not, and asking again with it
     * is how an account gets rate-limited - so the pending sign-in is dropped
     * either way.
     */
    suspend fun finishWindscribe(
        token: String, solution: String, trailX: List<Int>, trailY: List<Int>, code2fa: String,
    ): Said = withContext(io) {
        val waiting = pending ?: return@withContext Said.no(
            "Start the sign-in again - the first half of it has been forgotten.")
        try {
            val out = Providers.signIn(app, waiting.username, waiting.password, waiting.label,
                token, solution, trailX.joinToString(","), trailY.joinToString(","), code2fa)
            if (!out.optBoolean("ok")) {
                Said.no(out.optString("error").ifEmpty { "Windscribe said no." })
            } else {
                forget()
                refreshAccounts()
                Said.OK
            }
        } finally {
            pending = null
        }
    }

    suspend fun useAccount(id: String): Said = withContext(io) {
        val a = Accounts.activate(app, id) ?: return@withContext Said.no("No such account.")
        forget()
        refreshAccounts()
        Said(true, "Using ${a.optString("label")}.")
    }

    /** The configs stay: they cost nothing and are worth having if it comes back. */
    suspend fun removeAccount(id: String): Said = withContext(io) {
        Accounts.remove(app, id) ?: return@withContext Said.no("No such account.")
        forget()
        refreshAccounts()
        Said.OK
    }

    suspend fun windscribeRefresh(): Said = withContext(io) {
        val o = Providers.refresh(app)
        forget()
        if (o.optBoolean("ok")) Said(true, "Fresh credential.") else Said.no(o.optString("error"))
    }

    // -- providers' server lists --------------------------------------------------------

    /** Surfshark's published fleet; public, so no sign-in involved. */
    suspend fun surfsharkServers(): Said = withContext(io) {
        val o = Providers.surfshark()
        if (!o.optBoolean("ok")) return@withContext Said.no(o.optString("error", "Surfshark did not answer."))
        Pin.rememberInbox(app, AppFiles.configs(app))
        refreshList()
        refreshPinPlan()
        Said(true, "${o.optInt("added")} new, ${o.optInt("total")} in all. Pin them next.")
    }

    suspend fun windscribeServers(freeOnly: Boolean): Said = withContext(io) {
        val o = Providers.windscribe(freeOnly)
        if (!o.optBoolean("ok")) return@withContext Said.no(o.optString("error", "Windscribe did not answer."))
        Pin.rememberInbox(app, AppFiles.windscribe(app))
        refreshList()
        refreshPinPlan()
        Said(true, "${o.optInt("written")} written. Pin them next.")
    }

    // -- pinning ---------------------------------------------------------------------

    suspend fun refreshPinPlan() = withContext(io) {
        _pinPlan.value = parsePinPlan(Pin.plan(app))
    }

    suspend fun setPinRoute(route: String? = null, maxIps: Int? = null, test: Boolean? = null) =
        withContext(io) { _pinPlan.value = parsePinPlan(Pin.setRoute(app, route, maxIps, test)) }

    suspend fun pinFor(provider: String): Said = withContext(io) {
        val o = Pin.forProvider(app, provider)
        if (!o.optBoolean("ok")) return@withContext Said.no(o.optString("error"))
        _pinPlan.value = parsePinPlan(o)
        Said.OK
    }

    /**
     * Start a run. Everything after this arrives as the core's events, which
     * are folded into [pinRun] as they land.
     */
    fun startPin(): Said {
        if (_pinRun.value.running) return Said.no("Already pinning.")
        val o = Pin.start(app) { payload -> onPin(payload) }
        if (!o.optBoolean("ok")) return Said.no(o.optString("error", "Could not start."))
        _pinRun.value = PinRun(running = true, phase = "starting", total = o.optInt("total"))
        return Said.OK
    }

    fun cancelPin() { Pin.cancel() }

    private fun onPin(payload: String) {
        val p = try { JSONObject(payload) } catch (e: Exception) { return }
        when (p.optString("phase")) {
            "result" -> _pinRun.update {
                val outcome = p.optString("outcome")
                it.copy(phase = "result", done = p.optInt("done", it.done),
                    total = p.optInt("total", it.total),
                    reachable = it.reachable +
                        if (outcome == "reachable" || outcome == "written") 1 else 0,
                    unreachable = it.unreachable + if (outcome == "unreachable") 1 else 0,
                    last = listOf(p.optString("name"), p.optString("ip")).filter { s -> s.isNotEmpty() }
                        .joinToString("  "))
            }
            "forged" -> _pinRun.update { it.copy(forged = it.forged + 1,
                last = "${p.optString("host")} answered with a forged address") }
            "finished" -> {
                val said = when {
                    p.optString("error").isNotEmpty() -> p.optString("error")
                    p.optBoolean("cancelled") -> "Stopped after ${p.optInt("written")} written."
                    else -> "${p.optInt("written")} pinned, ${p.optInt("unreachable")} unreachable, " +
                        "${p.optInt("skipped")} skipped."
                }
                _pinRun.update { it.copy(running = false, phase = "finished", finished = said) }
                scope.launch { refreshList(); refreshPinPlan() }
            }
            else -> _pinRun.update { it.copy(phase = p.optString("phase"),
                total = p.optInt("total", it.total)) }
        }
    }

    // -- the user's own server --------------------------------------------------------

    private fun edgeList(): List<String> =
        prefs.getString("tunnelEdges", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun hasSs() = !prefs.getString("tunnelSsPassword", "").isNullOrEmpty()

    private fun viaSs() = hasSs() && prefs.getString("tunnelTransport", "") == "ss"

    private fun readTunnel() = TunnelPlan(
        domain = prefs.getString("tunnelDomain", "").orEmpty(),
        hasPassword = !prefs.getString("tunnelPassword", "").isNullOrEmpty(),
        hasApiPassword = !prefs.getString("tunnelApiPassword", "").isNullOrEmpty(),
        edges = edgeList(),
        pin = prefs.getString("tunnelPin", "").orEmpty(),
        transport = if (viaSs()) "ss" else "ws",
        hasSs = hasSs(),
        ssPort = prefs.getInt("tunnelSsPort", 0),
    )

    /**
     * Sets the whole server up from the key its installer printed: server,
     * both passwords, the certificate pin and - when the server offers it -
     * Shadowsocks, which then becomes the way in. Everything is replaced,
     * because a key is one server's complete description and half of it
     * mixed with what was there before is a server that exists nowhere.
     */
    suspend fun importKey(text: String): Said = withContext(io) {
        if (RelayVpnService.exitAddress.isNotEmpty() && _way.value != Way.PROVIDER) {
            return@withContext Said.no("Disconnect first - the tunnel is carrying the connection right now.")
        }
        val key = try {
            JSONObject(Relay.parseKey(text))
        } catch (e: Exception) {
            return@withContext Said.no(e.message ?: "That key could not be read.")
        }
        val ss = key.optJSONObject("ss")
        // The locations kept for other devices: an id their links are made
        // from, and how many. A key from an older installer has neither, and
        // then the share screen says to run the installer again.
        val shared = key.optJSONObject("export")?.takeIf { it.optString("id").isNotEmpty() }
        val exportId = shared?.optString("id").orEmpty()
        // Links made for another server - or for this one before it was
        // installed again, with new ids - no longer open anything.
        val stale = key.optString("host") != prefs.getString("tunnelDomain", "") ||
            exportId != prefs.getString("tunnelExportId", "")
        prefs.edit().apply {
            if (shared != null) {
                putString("tunnelExportId", exportId)
                putInt("tunnelExportSlots", shared.optInt("slots"))
            } else {
                remove("tunnelExportId"); remove("tunnelExportSlots")
            }
            if (stale) remove("exportLast")
            putString("tunnelDomain", key.optString("host"))
            putString("tunnelPassword", key.optString("password"))
            putString("tunnelApiPassword", key.optString("apiPassword"))
            // The key's pin is the server's own word for its certificate, so
            // even the first connection is checked against it.
            putString("tunnelPin", key.optString("pin"))
            remove("tunnelEdges")
            if (ss != null) {
                putString("tunnelSsMethod", ss.optString("method"))
                putString("tunnelSsPassword", ss.optString("password"))
                putInt("tunnelSsPort", ss.optInt("port"))
                putInt("tunnelSsExitPort", ss.optInt("exitPort"))
                putString("tunnelTransport", "ss")
            } else {
                remove("tunnelSsMethod"); remove("tunnelSsPassword")
                remove("tunnelSsPort"); remove("tunnelSsExitPort")
                putString("tunnelTransport", "ws")
            }
        }.apply()
        _tunnel.value = readTunnel()
        _export.update { readExport(it) }
        Said(true, "Set up for ${key.optString("host")}" +
            (if (ss != null) ", over Shadowsocks" else "") + ". Press Test to check it carries.")
    }

    /** Shadowsocks or the WebSocket, for a server whose key offered both. */
    fun setTunnelTransport(transport: String): Said {
        if (transport == "ss" && !hasSs()) return Said.no("This server's key had no Shadowsocks.")
        if (RelayVpnService.exitAddress.isNotEmpty() && _way.value != Way.PROVIDER) {
            return Said.no("Disconnect first - the tunnel is carrying the connection right now.")
        }
        prefs.edit().putString("tunnelTransport", if (transport == "ss") "ss" else "ws").apply()
        _tunnel.value = readTunnel()
        return Said(true, if (transport == "ss") "Straight to the server over Shadowsocks."
            else "Through the WebSocket, as before.")
    }

    /** The Shadowsocks settings onto a config, when that is the way in. */
    private fun applySs(cfg: relay.ServerConfig): Boolean {
        if (!viaSs()) return false
        cfg.transport = "ss"
        cfg.ssMethod = prefs.getString("tunnelSsMethod", "").orEmpty()
        cfg.ssPassword = prefs.getString("tunnelSsPassword", "").orEmpty()
        cfg.ssPort = prefs.getInt("tunnelSsPort", 0).toLong()
        cfg.ssExitPort = prefs.getInt("tunnelSsExitPort", 0).toLong()
        return true
    }

    /**
     * Blank means unchanged, not cleared: the passwords are never shown back,
     * so treating empty as erase would wipe them whenever the domain changed.
     * A new domain drops the edges, which belong to the old one.
     */
    fun saveTunnel(domain: String, password: String, apiPassword: String) {
        val was = prefs.getString("tunnelDomain", "")
        prefs.edit().apply {
            putString("tunnelDomain", domain.trim())
            if (password.isNotEmpty()) putString("tunnelPassword", password)
            if (apiPassword.isNotEmpty()) putString("tunnelApiPassword", apiPassword)
            // A new server: its edges, its certificate and its Shadowsocks
            // were the old one's - and so were its shared locations, and
            // the links made for them.
            if (domain.trim() != was) {
                remove("tunnelEdges"); remove("tunnelPin")
                remove("tunnelSsMethod"); remove("tunnelSsPassword")
                remove("tunnelSsPort"); remove("tunnelSsExitPort")
                putString("tunnelTransport", "ws")
                remove("tunnelExportId"); remove("tunnelExportSlots"); remove("exportLast")
            }
        }.apply()
        _tunnel.value = readTunnel()
        _export.update { readExport(it) }
    }

    /**
     * The two passwords, made here when there are none yet. The install
     * command carries them to the server, so the app already holds what the
     * server will ask for and nothing has to be copied back.
     */
    private fun ensurePasswords() {
        fun make(): String {
            val bytes = ByteArray(15).also { java.security.SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        prefs.edit().apply {
            if (prefs.getString("tunnelPassword", "").isNullOrEmpty()) putString("tunnelPassword", make())
            if (prefs.getString("tunnelApiPassword", "").isNullOrEmpty()) putString("tunnelApiPassword", make())
        }.apply()
        _tunnel.value = readTunnel()
    }

    fun setWay(way: Way) {
        prefs.edit().putString("mode", way.key).apply()
        _way.value = way
    }

    /** Takes effect on the next connect: routes are fixed when the tun is built. */
    fun setAllowLan(on: Boolean): Boolean {
        prefs.edit().putBoolean("allowLan", on).apply()
        _allowLan.value = on
        return RelayVpnService.exitAddress.isNotEmpty()
    }

    // -- sharing on the Wi-Fi ---------------------------------------------------------
    //
    // The service starts the share after the tunnel is up when the pref is on
    // (RelayVpnService.shareOnLan), and the core ends it with the tunnel. These
    // are for the switch pressed while connected, and for the screen's view of it.

    private fun tunnelUp() = RelayVpnService.exitAddress.isNotEmpty()

    /** Starts, restarts or stops the share to match the prefs, when a tunnel is up. */
    private fun applyLanShare(): Said {
        if (!tunnelUp()) return Said.OK
        RelayVpnService.stopLanShare()
        if (_lanShare.value) {
            try {
                RelayVpnService.startLanShare(_lanSharePort.value, _lanSharePassword.value)
            } catch (e: Exception) {
                Log.w(RelayVpnService.TAG, "sharing on the local network: ${e.message}")
                return Said(false, e.message ?: "Could not start sharing.")
            }
        }
        return Said.OK
    }

    /** On: starts at once when connected, else with the next connect. */
    suspend fun setLanShare(on: Boolean): Said = withContext(io) {
        prefs.edit().putBoolean("lanShare", on).apply()
        _lanShare.value = on
        val r = applyLanShare()
        readLanShare()
        r
    }

    /** A port from 1024 up: below that Android does not let an app listen. */
    suspend fun setLanSharePort(port: Int): Said {
        if (port !in 1024..65535) return Said(false, "Use a port between 1024 and 65535.")
        return withContext(io) {
            prefs.edit().putInt("lanSharePort", port).apply()
            _lanSharePort.value = port
            val r = if (_lanShare.value) applyLanShare() else Said.OK
            readLanShare()
            r
        }
    }

    suspend fun setLanSharePassword(password: String): Said = withContext(io) {
        val clean = password.trim()
        prefs.edit().putString("lanSharePassword", clean).apply()
        _lanSharePassword.value = clean
        val r = if (_lanShare.value) applyLanShare() else Said.OK
        readLanShare()
        r
    }

    /** Asks the core how the share stands. Cheap; called every few seconds while Settings shows it. */
    suspend fun refreshLanShare() = withContext(io) { readLanShare() }

    private fun readLanShare() {
        _lanState.value = try {
            val j = JSONObject(RelayVpnService.lanShareJSON())
            val a = j.optJSONArray("addrs")
            LanShareState(
                on = j.optBoolean("on"),
                port = j.optInt("port"),
                addrs = List(a?.length() ?: 0) { a!!.getString(it) },
                clients = j.optInt("clients"),
            )
        } catch (e: Exception) {
            LanShareState()
        }
    }

    // -- the split tunnel ----------------------------------------------------------
    //
    // Four prefs, read by the service when it builds the interface - which is
    // the only moment they can mean anything, since which apps an interface
    // admits is fixed when it is established. So, like the LAN switch, a
    // change takes effect on the next connect, and [splitPending] says so
    // while a tunnel built with the old one is still up.

    private fun prefLines(key: String): List<String> =
        prefs.getString(key, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun readSplit() = SplitPlan(
        mode = SplitMode.of(prefs.getString("splitMode", "off")),
        iran = prefs.getBoolean("splitIran", true),
        apps = prefLines("splitApps").toSet(),
        domains = prefLines("splitDomains").distinct(),
    )

    /** Saved, and noted as waiting for a reconnect if a tunnel is up or on its way. */
    private fun changedSplit(next: SplitPlan) {
        _split.value = next
        val mode = _conn.value.mode
        if (mode == Mode.ON || mode == Mode.BUSY) _splitPending.value = true
    }

    fun setSplitMode(mode: SplitMode) {
        prefs.edit().putString("splitMode", mode.key).apply()
        changedSplit(_split.value.copy(mode = mode))
    }

    fun setSplitIran(on: Boolean) {
        prefs.edit().putBoolean("splitIran", on).apply()
        changedSplit(_split.value.copy(iran = on))
    }

    fun setSplitApp(pkg: String, on: Boolean) {
        val was = _split.value.apps
        val next = if (on) was + pkg else was - pkg
        if (next == was) return
        prefs.edit().putString("splitApps", next.joinToString("\n")).apply()
        changedSplit(_split.value.copy(apps = next))
    }

    /**
     * Add what was typed: a domain (which takes every name under it), an
     * address, or a range. Several at once when they are pasted together,
     * split on spaces and commas - and then all or none, so a typo is fixed
     * in the field rather than hunted for in the list. The core says what is
     * acceptable and how it is written down, the same check the desktop's
     * list goes through.
     */
    suspend fun addSplitDomain(text: String): Said = withContext(io) {
        val typed = text.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (typed.isEmpty()) return@withContext Said.no("Type a domain, an address or a range.")
        val got = mutableListOf<String>()
        for (t in typed) {
            val n = try {
                Relay.splitNormalize(t).trim()
            } catch (e: Exception) {
                ""
            }
            if (n.isEmpty()) {
                return@withContext Said.no("“$t” is not a domain, an IPv4 address or a range " +
                    "like 5.6.0.0/16.")
            }
            if (n !in got) got += n
        }
        val had = _split.value.domains
        val fresh = got.filter { it !in had }
        if (fresh.isEmpty()) {
            return@withContext Said(true,
                if (got.size == 1) "${got[0]} is already on the list." else "Those are already on the list.")
        }
        val next = had + fresh
        prefs.edit().putString("splitDomains", next.joinToString("\n")).apply()
        changedSplit(_split.value.copy(domains = next))
        Said(true, if (fresh.size == 1) "Added ${fresh[0]}." else "Added ${fresh.size}.")
    }

    fun removeSplitDomain(entry: String) {
        val next = _split.value.domains.filter { it != entry }
        prefs.edit().putString("splitDomains", next.joinToString("\n")).apply()
        changedSplit(_split.value.copy(domains = next))
    }

    /**
     * The core's list of Iranian apps, read once. Empty is not remembered as
     * the answer, so a core that could not say the first time is asked again.
     */
    private fun iranSet(): Set<String> {
        _iranApps.value.takeIf { it.isNotEmpty() }?.let { return it }
        val got = try {
            Relay.splitIranApps().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            emptySet<String>()
        }
        _iranApps.value = got
        return got
    }

    /**
     * The apps the split tunnel's picker offers: everything with an icon in
     * the launcher, which is what a person thinks of as their apps - not the
     * hundreds of services and providers behind them - sorted by name, and
     * never this one, which the tunnel already leaves out of itself.
     */
    suspend fun installedApps(): List<InstalledApp> = withContext(io) {
        val pm = app.packageManager
        val iran = iranSet()
        val launchable = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet<String>()
        }
        val installed = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList<ApplicationInfo>()
        }
        installed
            .filter { it.packageName != app.packageName && it.packageName in launchable }
            .map { info ->
                InstalledApp(
                    pkg = info.packageName,
                    label = pm.getApplicationLabel(info).toString().ifBlank { info.packageName },
                    iranian = info.packageName in iran,
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Look for a way in now: fifty-odd TLS handshakes, a few seconds. */
    suspend fun rescanEdges(): Said = withContext(io) {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        if (domain.isEmpty()) return@withContext Said.no("No server domain saved yet.")
        val found = JSONObject(Relay.scanEdgesJSON(domain, 40000L))
        val edges = found.optJSONArray("edges").strings()
        if (edges.isNotEmpty()) {
            prefs.edit().putString("tunnelEdges", edges.joinToString("\n")).apply()
            _tunnel.value = readTunnel()
            Said(true, "${edges.size} ways in. Using ${edges[0]}.")
        } else {
            Said.no(found.optString("error").ifEmpty { "No way in answered." })
        }
    }

    /**
     * Carry something through the tunnel rather than checking a port. On a
     * failure, scan once for another way in and ask again through it - once,
     * because a Test that retries forever never finishes.
     */
    suspend fun testTunnel(): Said = withContext(io) {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        val password = prefs.getString("tunnelPassword", "").orEmpty()
        if (domain.isEmpty() || password.isEmpty()) {
            return@withContext Said.no("Set the domain and password first.")
        }
        val mode = if (_way.value == Way.PROVIDER) "single" else _way.value.key
        val cfg = Relay.newServerConfig()
        cfg.domain = domain
        cfg.password = password
        cfg.mode = mode
        cfg.edges = prefs.getString("tunnelEdges", "").orEmpty()
        cfg.pin = prefs.getString("tunnelPin", "").orEmpty()
        // Straight to the address: there is no other way in to go looking
        // for, so a failure is the answer rather than a reason to scan.
        if (applySs(cfg)) {
            return@withContext try {
                ask(cfg)
            } catch (e: Exception) {
                Said.no("$domain:${cfg.ssPort} did not carry anything over Shadowsocks: " +
                    (e.message ?: e.toString()).take(140))
            }
        }
        try {
            ask(cfg)
        } catch (first: Exception) {
            val scan = JSONObject(Relay.scanEdgesJSON(domain, 40000L))
            val found = scan.optJSONArray("edges").strings()
            if (found.isEmpty()) {
                return@withContext Said.no("No way in: " +
                    scan.optString("error").ifEmpty { first.message ?: first.toString() })
            }
            prefs.edit().putString("tunnelEdges", found.joinToString("\n")).apply()
            _tunnel.value = readTunnel()
            cfg.edges = found.joinToString("\n")
            try {
                ask(cfg)
            } catch (second: Exception) {
                Said.no("Found a way in at ${found[0]} and the tunnel still will not carry " +
                    "anything: " + (second.message ?: second.toString()).take(120))
            }
        }
    }

    private fun ask(cfg: relay.ServerConfig): Said {
        val seen = JSONObject(Relay.testServer(cfg, 20000L))
        // A server by address: its certificate, kept the first time it is
        // seen and required from then on.
        val pin = seen.optString("pin")
        if (pin.isNotEmpty() && cfg.pin.isEmpty()) {
            prefs.edit().putString("tunnelPin", pin).apply()
            _tunnel.value = readTunnel()
        }
        return Said(true, "Carrying traffic - it comes out at ${seen.optString("ip")}.")
    }

    /**
     * One line that puts the installer on the server and runs it, with the
     * passwords this app holds - made now if there are none - so that once it
     * has run there is nothing left to do here but Test. The script travels
     * inside the command, because nothing hosts it.
     *
     * The leading space keeps it out of the server's shell history (Ubuntu's
     * bash ignores lines that start with one), passwords and all. No provider
     * account goes in it: server + exit sends the picked location's own
     * credential each time it connects.
     */
    fun installCommand(host: String): String? {
        val blob = try {
            app.assets.open("install-server.sh").use {
                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            return null
        }
        val server = host.trim()
        if (server.isEmpty()) return null
        if (server != prefs.getString("tunnelDomain", "")) saveTunnel(server, "", "")
        ensurePasswords()
        val pw = prefs.getString("tunnelPassword", "").orEmpty()
        val api = prefs.getString("tunnelApiPassword", "").orEmpty()
        return " mkdir -p /opt/relay && echo '$blob' | base64 -d > /opt/relay/install-server.sh" +
            " && bash /opt/relay/install-server.sh $server --password '$pw' --api-password '$api'"
    }

    /** What is running on the server, and whether it answers. */
    fun statusCommand(): String =
        "systemctl --no-pager --lines=0 status gost sing-box nginx | grep -E '^. |Active:';" +
            " ss -lnt | grep -E ':443 |:838[89] |127.0.0.1:(1000[0-3]|18080) '"

    /** Takes the tunnel off the server again, leaving anything else there alone. */
    fun uninstallCommand(): String {
        val host = prefs.getString("tunnelDomain", "").orEmpty().ifEmpty { "SERVER" }
        return "systemctl disable --now gost sing-box;" +
            " rm -f /etc/systemd/system/gost.service /etc/systemd/system/sing-box.service" +
            " /usr/local/bin/gost /usr/local/bin/sing-box" +
            " /etc/nginx/sites-enabled/relay-$host.conf /etc/nginx/sites-available/relay-$host.conf;" +
            " rm -rf /etc/gost /etc/sing-box /etc/relay /opt/relay;" +
            " systemctl daemon-reload; systemctl reload nginx"
    }

    // -- configs for other devices ----------------------------------------------
    //
    // Settings → Share with other devices. The server keeps numbered slots for
    // devices that have no Relay of their own: slot 0 leaves from the server
    // itself, slots 1 to n from a provider exit this app chooses. A run finds,
    // for each location on the list, the exit there that uploads best from the
    // server - the very search server + exit makes before it connects - and
    // pins the next slot to it; then the core writes the links. Nothing here
    // builds a tunnel: the server's API is asked, and an upload is carried
    // through the server to time each exit, all from the app's own process.
    //
    // The socket protector is left as it is, which is how testTunnel leaves
    // it. While a tunnel is up it is the live service's protect(), and that
    // keeps these sockets out of the tunnel exactly as it keeps the service's
    // own. When the service stops it hands the hook back - stopTunnel() sets
    // it to null - so the core's dialControl never points at a service that
    // can no longer answer, and a dial from here is a plain dial.

    private fun readExport(was: ExportRun): ExportRun = was.copy(
        ready = !prefs.getString("tunnelDomain", "").isNullOrEmpty() &&
            !prefs.getString("tunnelPassword", "").isNullOrEmpty() &&
            !prefs.getString("tunnelExportId", "").isNullOrEmpty(),
        slots = prefs.getInt("tunnelExportSlots", 0),
        last = prefs.getString("exportLast", null)?.let { parseExportResult(it) },
    )

    /**
     * Put a location on the share list, or take it off. False when it would
     * not fit: the server keeps a fixed number, and the list is the order the
     * slots are given out in, so nothing is dropped off the end to make room.
     */
    fun setExportPick(code: String, on: Boolean): Boolean {
        val was = _exportPicks.value
        val next = when {
            on && code in was -> return true
            on && was.size >= _export.value.slots -> return false
            on -> was + code
            else -> was - code
        }
        prefs.edit().putString("exportPicks", next.joinToString("\n")).apply()
        _exportPicks.value = next
        return true
    }

    /**
     * Every location on the phone, for the share chooser. The exit list's own
     * when it shows everything; read afresh when it shows Starred, because a
     * location shared is looked for among every exit the phone has - the run
     * loads the whole folder, as server + exit does - and a chooser narrowed
     * to the starred few would hide most of what can be picked.
     */
    suspend fun shareCountries(): List<Country> = withContext(io) {
        shareList?.let { return@withContext it }
        val got = if (_source.value == "all" && _countries.value.isNotEmpty()) {
            _countries.value
        } else {
            try {
                val c = Relay.newClient("", "")
                c.load()
                parseCountries(c.countriesJSON())
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (got.isNotEmpty()) shareList = got
        got
    }

    private val exportBusy = AtomicBoolean(false)
    @Volatile private var exportStop = false

    /**
     * Start a run in the core's own scope rather than the screen's: one
     * location takes the better part of a minute, and a run over eight
     * should not end because somebody closed Settings to wait. The screen
     * reads [export] whenever it comes back.
     */
    fun startExport(picks: List<ExportPick>): Said {
        if (_export.value.running || exportBusy.get()) return Said.no("Already making configs.")
        scope.launch { runExport(picks) }
        return Said.OK
    }

    /** Stop after the location being tested: the core's search cannot be cut off half way. */
    fun stopExport() {
        if (!_export.value.running) return
        exportStop = true
        _export.update { it.copy(stopping = true) }
    }

    /**
     * Pin a slot to each of [picks], in order, and make every slot's links.
     *
     * The server's own exit-chain is read first and put back last, whatever
     * happens between: the search that finds a location's best exit leaves
     * that chain on the winner, and the user's own server + exit connection
     * leaves by it. Then the server is asked to write it all to its file, so
     * the slots outlive a reboot.
     *
     * A location that fails says why on its own row and the rest carry on. A
     * server that refuses - its API password, or no answer at all - stops the
     * run, because no other location would fare better; the slots pinned
     * before that still get their links.
     */
    suspend fun runExport(picks: List<ExportPick>): Said = withContext(io) {
        if (!exportBusy.compareAndSet(false, true)) return@withContext Said.no("Already making configs.")
        try {
            exportStop = false
            val said = try {
                exportNow(picks)
            } catch (e: Exception) {
                Said.no(e.message ?: e.toString())
            }
            if (!said.ok) _export.update { it.copy(error = said.text) }
            said
        } finally {
            _export.update { it.copy(running = false, stopping = false, phase = "") }
            exportBusy.set(false)
        }
    }

    private fun exportNow(picks: List<ExportPick>): Said {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        val password = prefs.getString("tunnelPassword", "").orEmpty()
        val api = prefs.getString("tunnelApiPassword", "").orEmpty()
        val id = prefs.getString("tunnelExportId", "").orEmpty()
        val slots = prefs.getInt("tunnelExportSlots", 0)
        when {
            domain.isEmpty() || password.isEmpty() ->
                return Said.no("Set up your own server under Settings → Connection first.")
            id.isEmpty() || slots < 1 ->
                return Said.no("Your server's key keeps no locations for other devices. Run the " +
                    "installer from this version on the server again and paste the new key it prints.")
            api.isEmpty() ->
                return Said.no("This server's API password is missing. Paste its key again under " +
                    "Settings → Connection.")
            picks.size > slots ->
                return Said.no("Your server keeps $slots locations. Take ${picks.size - slots} off the list.")
            // A connect through server + exit is setting the very chain this
            // borrows; the two would fight over it.
            _conn.value.mode == Mode.BUSY ->
                return Said.no("Wait for the connection to finish, then try again.")
        }
        // The same accounts server + exit connects with: each exit is set on
        // the server with its own provider's credential, and the slot keeps it.
        val surfshark = credentials(Accounts.SURFSHARK)
        val windscribe = credentials(Accounts.WINDSCRIBE)
        if (picks.isNotEmpty() && surfshark == null && windscribe == null) {
            return Said.no("No provider account to find exits with. Add one under Settings → Accounts.")
        }

        _export.update {
            it.copy(running = true, stopping = false, phase = "starting", index = 0, error = "",
                steps = picks.mapIndexed { i, p -> ExportStep(k = i + 1, code = p.code, name = p.name) })
        }

        // Built as the service builds it for server + exit: the upload that
        // times each exit is carried the way that connection carries it.
        val cfg = Relay.newServerConfig()
        cfg.domain = domain
        cfg.password = password
        cfg.mode = "multi"
        cfg.edges = prefs.getString("tunnelEdges", "").orEmpty()
        cfg.pin = prefs.getString("tunnelPin", "").orEmpty()
        val viaSs = applySs(cfg)
        if (!viaSs && cfg.edges.isBlank() && picks.isNotEmpty()) {
            cfg.edges = try {
                Relay.scanEdges(domain, 30000L)
            } catch (e: Exception) {
                ""
            }
            if (cfg.edges.isBlank()) {
                return Said.no("No way in to $domain answered. Press Test under Settings → Connection, " +
                    "then try again.")
            }
            prefs.edit().putString("tunnelEdges", cfg.edges).apply()
            _tunnel.value = readTunnel()
        }

        val notes = mutableListOf<String>()
        var fatal: String? = null
        if (picks.isNotEmpty()) {
            val c = Relay.newClient(surfshark?.first.orEmpty(), surfshark?.second.orEmpty())
            windscribe?.let { (user, pass) -> c.setCredentials(Accounts.WINDSCRIBE, user, pass) }
            try {
                c.load()
            } catch (e: Exception) {
                return Said.no("There are no servers on this phone to find exits in. Get them under " +
                    "Settings → Accounts.")
            }
            // A server that has never had server + exit set may have no
            // exit-chain to keep; then there is nothing to put back either.
            val saved: String? = try {
                Relay.saveServerChain(cfg, api, EXIT_CHAIN, EXPORT_API_MS)
            } catch (e: Exception) {
                val why = e.message ?: e.toString()
                if (!why.contains("no chain called")) return Said.no("$domain did not answer: ${why.take(160)}")
                null
            }
            try {
                for ((i, p) in picks.withIndex()) {
                    if (exportStop || fatal != null) {
                        setStep(i) { it.copy(phase = "skipped") }
                        continue
                    }
                    _export.update { it.copy(phase = "locations", index = i) }
                    setStep(i) { it.copy(phase = "testing") }
                    try {
                        val r = c.exportSlot(cfg, api, (i + 1).toLong(), p.code,
                            surfshark?.first.orEmpty(), surfshark?.second.orEmpty(),
                            windscribe?.first.orEmpty(), windscribe?.second.orEmpty(), 6L, 4000L)
                        setStep(i) {
                            it.copy(phase = "pinned", exit = r.name, provider = r.provider, upKBs = r.tookMs.toInt())
                        }
                    } catch (e: Exception) {
                        val why = e.message ?: e.toString()
                        if (serverRefused(why)) fatal = why
                        setStep(i) { it.copy(phase = "failed", error = placeFailed(why)) }
                    }
                }
            } finally {
                if (saved != null) {
                    _export.update { it.copy(phase = "restoring") }
                    try {
                        Relay.restoreServerChain(cfg, api, EXIT_CHAIN, saved, EXPORT_API_MS)
                    } catch (e: Exception) {
                        notes += "Your own server + exit could not be put back where it was (" +
                            (e.message ?: e.toString()).take(120) + "). The next connect through " +
                            "server + exit sets it again."
                    }
                }
            }
        }

        val steps = _export.value.steps
        val pinned = steps.filter { it.phase == "pinned" }
        if (pinned.isNotEmpty()) {
            _export.update { it.copy(phase = "saving") }
            try {
                Relay.persistServer(cfg, api, EXPORT_API_MS)
            } catch (e: Exception) {
                notes += "The server took the locations but could not save them (" +
                    (e.message ?: e.toString()).take(120) + "). They work until it restarts; make " +
                    "the configs again after that."
            }
        }

        _export.update { it.copy(phase = "links") }
        val asked = JSONArray().put(JSONObject().put("k", 0).put("name", SERVER_SLOT))
        for (s in pinned) asked.put(JSONObject().put("k", s.k).put("name", slotName(s.name)))
        val made = try {
            JSONArray(Relay.exportLinksJSON(domain, edgeList().firstOrNull().orEmpty(), id,
                asked.toString(), ssJson()))
        } catch (e: Exception) {
            return Said.no(e.message ?: "The links could not be made.")
        }
        val byK = made.objects().associateBy { it.optInt("k") }
        val own = byK[0]
        val rows = mutableListOf(ExportSlotLinks(
            k = 0, name = SERVER_SLOT, place = "", code = "", exit = domain,
            vless = own?.optString("vless").orEmpty(), vmess = own?.optString("vmess").orEmpty(),
            ss = own?.optString("ss").orEmpty(),
            error = if (own == null) "No link came back for the server." else "",
        ))
        // A run stopped part way leaves the slots after it as the last run
        // pinned them. Where that was the same place in the same slot, its
        // link still opens it, so it stays on screen rather than vanishing
        // because this run never got to it.
        val before = _export.value.last?.slots.orEmpty().associateBy { it.k }
        for (s in steps) {
            if (s.phase != "pinned" && s.phase != "failed") {
                before[s.k]?.takeIf { it.ok && it.code == s.code }?.let { rows += it }
                continue
            }
            val l = byK[s.k]
            rows += ExportSlotLinks(
                k = s.k, name = slotName(s.name), place = s.name, code = s.code,
                exit = s.exit, provider = s.provider, upKBs = s.upKBs,
                error = when {
                    s.phase == "failed" -> s.error
                    l == null -> "No link came back for this one."
                    else -> ""
                },
                vless = l?.optString("vless").orEmpty(), vmess = l?.optString("vmess").orEmpty(),
            )
        }
        val result = ExportResult(System.currentTimeMillis(), domain, rows, notes)
        prefs.edit().putString("exportLast", result.toJson()).apply()
        _export.update { it.copy(last = result) }
        fatal?.let { return Said.no("$domain stopped taking locations: ${it.take(160)}") }
        return Said(true, "Made ${rows.count { it.ok }} configs.")
    }

    private fun setStep(i: Int, change: (ExportStep) -> ExportStep) {
        _export.update { r -> r.copy(steps = r.steps.mapIndexed { j, s -> if (j == i) change(s) else s }) }
    }

    /**
     * Whether a slot failed at the server rather than at the location. The
     * core says it in words, not types: the API's own refusals begin "the
     * server", and a request that never reached it names the /api/ address
     * it was sent to.
     */
    private fun serverRefused(why: String) = why.startsWith("the server ") || "/api/" in why

    /** The core's word for why a location could not be pinned, said for a person. */
    private fun placeFailed(why: String): String = when {
        why.startsWith("no exit in") ->
            "This phone has no exits there any more. Pick it again from the list, or another."
        why.startsWith("no account for any exit in") ->
            "No account for the provider whose exits these are. Add one under Settings → Accounts."
        else -> why.take(160)
    }

    /** The key's Shadowsocks, for the server's own slot - or nothing, when the key had none. */
    private fun ssJson(): String = if (!hasSs()) "" else JSONObject()
        .put("method", prefs.getString("tunnelSsMethod", "").orEmpty())
        .put("password", prefs.getString("tunnelSsPassword", "").orEmpty())
        .put("port", prefs.getInt("tunnelSsPort", 0))
        .put("exitPort", prefs.getInt("tunnelSsExitPort", 0))
        .toString()

    private fun slotName(place: String) = "Relay · $place"

    /** A link as a QR code, for another phone's camera. Null when the core could not draw it. */
    suspend fun qrBitmap(text: String, px: Int): Bitmap? = withContext(io) {
        try {
            val png = Relay.qrpng(text, px.toLong())
            BitmapFactory.decodeByteArray(png, 0, png.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Onto the clipboard. A [sensitive] one - a link that opens the server -
     * is marked so, and Android 13 and later then shows dots in its preview
     * instead of the link, and keeps it out of the keyboard's suggestions.
     */
    fun copy(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("Relay", text)
        if (sensitive) {
            // ClipDescription.EXTRA_IS_SENSITIVE, by its value: the constant
            // is Android 13's, and older ones ignore the extra.
            clip.description.setExtras(PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            })
        }
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    val dataFolder: String get() = AppFiles.root(app).absolutePath
    val version: String get() = try { Relay.version() } catch (e: Throwable) { "" }
    val appVersion: String get() = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    @Suppress("unused")
    private fun JSONArray.toList(): List<String> = (0 until length()).map { optString(it) }
}

/** The pool code for one exit per country. */
const val QUICK = "quick"

/** How old a quick test may get before opening the list runs another: six hours. */
private const val QUICK_EVERY_MS = 6L * 60 * 60 * 1000

/**
 * The server's own way out to a provider, which server + exit points and a
 * share run borrows - each location's search leaves it on that location's
 * winner - and puts back.
 */
private const val EXIT_CHAIN = "exit-chain"

/** One request to the server's API: what the core gives its own SetExit. */
private const val EXPORT_API_MS = 15_000L

/** What another device lists the server's own slot as. */
const val SERVER_SLOT = "Relay · server"
