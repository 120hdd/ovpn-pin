package com.erelay.relay.data

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
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
    private val _tunnel = MutableStateFlow(readTunnel())
    val tunnel: StateFlow<TunnelPlan> = _tunnel.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()
    private val _cards = MutableStateFlow(mapOf(
        Accounts.SURFSHARK to ProviderCard(), Accounts.WINDSCRIBE to ProviderCard()))
    val cards: StateFlow<Map<String, ProviderCard>> = _cards.asStateFlow()

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
        orbColors = prefs.getString("orbColors", "signal") ?: "signal",
    )

    fun setFeel(next: Feel) {
        prefs.edit()
            .putString("theme", next.theme)
            .putBoolean("haptics", next.haptics)
            .putBoolean("orbTap", next.orbTap)
            .putBoolean("orbMotion", next.orbMotion)
            .putBoolean("meter", next.meter)
            .putString("orbColors", next.orbColors)
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
            up -> Mode.ON
            failed -> Mode.FAIL
            busy -> Mode.BUSY
            // A connect asked for but not yet seen by the service keeps the
            // window busy rather than flicking back to off.
            _conn.value.mode == Mode.BUSY && connecting -> Mode.BUSY
            else -> Mode.OFF
        }
        if (mode != Mode.BUSY) connecting = false
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
                if (creds == null) "{}"
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
        val c = catalogue()
        _countries.value = parseCountries(try { c.countriesJSON() } catch (e: Exception) { "[]" })
        _serverCount.value = c.count().toInt()
        _sources.value = readSources(c)
        _cards.value = readCards()
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
                Said.no("No servers yet. Get them under Settings → Sign-in, " +
                    "or import a folder of configs under Settings → Servers.")
            credentials(Accounts.SURFSHARK) == null && credentials(Accounts.WINDSCRIBE) == null ->
                Said.no("No account yet. Add one under Settings → Sign-in.")
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
            val creds = credentials(Accounts.SURFSHARK) ?: credentials(Accounts.WINDSCRIBE)
                ?: return Said.no("No account yet. Add one under Settings → Sign-in.")
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, "surfshark")
                .putExtra(RelayVpnService.EXTRA_USER, creds.first)
                .putExtra(RelayVpnService.EXTRA_PASSWORD, creds.second)
                .putExtra(RelayVpnService.EXTRA_CONFIG_DIR, AppFiles.pinned(app).absolutePath)
                .putExtra(RelayVpnService.EXTRA_COUNTRY, code)
            credentials(Accounts.WINDSCRIBE)?.let { (user, password) ->
                intent.putExtra(RelayVpnService.EXTRA_WS_USER, user)
                    .putExtra(RelayVpnService.EXTRA_WS_PASSWORD, password)
            }
            if (_source.value == "starred") {
                intent.putExtra(RelayVpnService.EXTRA_ONLY, starredFiles(catalogue()))
            }
        } else {
            val domain = prefs.getString("tunnelDomain", "").orEmpty()
            val password = prefs.getString("tunnelPassword", "").orEmpty()
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, way.key)
                .putExtra(RelayVpnService.EXTRA_DOMAIN, domain)
                .putExtra(RelayVpnService.EXTRA_TUNNEL_PASSWORD, password)
                .putExtra(RelayVpnService.EXTRA_EDGES, prefs.getString("tunnelEdges", "").orEmpty())
        }
        connecting = true
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
    fun testReach(code: String): Said {
        if (_reach.value.running) return Said.no("A test is already running.")
        val c = catalogue()
        val pool = try { c.poolFiles(code) } catch (e: Exception) { "" }
        if (pool.isBlank()) return Said.no("Nothing to test.")
        val total = pool.lines().count { it.isNotBlank() }
        _reach.value = ReachRun(code = code, total = total, running = true)
        scope.launch {
            val answer = try {
                JSONObject(c.testReach(code, 5000L, 12L, object : ReachProgress {
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
                        "Settings → Sign-in." else it
                }
                answer.optBoolean("cancelled") -> "Stopped."
                else -> "${answer.optInt("answered")} of ${answer.optInt("tested")} answered."
            }
            _reach.value = ReachRun(code = code, running = false, said = said)
            refreshList()
            refreshDead()
        }
        return Said.OK
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

    private fun readTunnel() = TunnelPlan(
        domain = prefs.getString("tunnelDomain", "").orEmpty(),
        hasPassword = !prefs.getString("tunnelPassword", "").isNullOrEmpty(),
        hasApiPassword = !prefs.getString("tunnelApiPassword", "").isNullOrEmpty(),
        edges = edgeList(),
    )

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
            if (domain.trim() != was) remove("tunnelEdges")
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
        return Said(true, "Carrying traffic - it comes out at ${seen.optString("ip")}.")
    }

    /**
     * One line that puts the installer on the server and runs it. The script
     * travels inside the command, because nothing hosts it. The provider
     * credentials stay placeholders rather than land in a shell history.
     */
    fun installCommand(domain: String): String? {
        val blob = try {
            app.assets.open("install-server.sh").use {
                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            return null
        }
        val host = domain.trim().ifEmpty { "yourdomain.com" }
        return "mkdir -p /opt/relay && echo '$blob' | base64 -d > /opt/relay/install-server.sh" +
            " && bash /opt/relay/install-server.sh $host <surfshark-user> <surfshark-pass>"
    }

    fun copy(text: String) {
        app.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Relay", text))
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
