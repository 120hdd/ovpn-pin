package com.erelay.relay.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erelay.relay.data.Mode
import com.erelay.relay.data.RelayCore
import com.erelay.relay.data.Way
import kotlinx.coroutines.launch

/** What the window is, before it can be the main screen. */
enum class Gate { LOADING, INTRO, WELCOME, SETUP, READY }

/** Which sheet is over the main screen, if any. */
enum class Over { NONE, PICKER, PREFS, LOG, DROP }

/** A sheet that can open over another one. */
enum class Over2 { NONE, SOURCES, ADD_ACCOUNT, SPLIT_APPS, SHARE_PLACES }

/**
 * The window: the main screen, and whichever sheet is over it.
 *
 * Holds the one thing only an Activity can do - show Android's VPN consent -
 * and hands every other action straight to the core.
 */
@Composable
fun Root(core: RelayCore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val conn by core.conn.collectAsStateWithLifecycle()
    val realIp by core.realIp.collectAsStateWithLifecycle()
    val realIpAsked by core.realIpAsked.collectAsStateWithLifecycle()
    val seen by core.seen.collectAsStateWithLifecycle()
    val seenFailed by core.seenFailed.collectAsStateWithLifecycle()
    val traffic by core.traffic.collectAsStateWithLifecycle()
    val way by core.way.collectAsStateWithLifecycle()
    val tunnel by core.tunnel.collectAsStateWithLifecycle()
    val picked by core.picked.collectAsStateWithLifecycle()
    val countries by core.countries.collectAsStateWithLifecycle()
    val serverCount by core.serverCount.collectAsStateWithLifecycle()
    val loaded by core.loaded.collectAsStateWithLifecycle()
    val accounts by core.accounts.collectAsStateWithLifecycle()
    val dead by core.dead.collectAsStateWithLifecycle()
    val favourites by core.favourites.collectAsStateWithLifecycle()
    val sort by core.sort.collectAsStateWithLifecycle()
    val reach by core.reach.collectAsStateWithLifecycle()
    val sources by core.sources.collectAsStateWithLifecycle()
    val source by core.source.collectAsStateWithLifecycle()
    val cards by core.cards.collectAsStateWithLifecycle()
    val allowLan by core.allowLan.collectAsStateWithLifecycle()
    val pinPlan by core.pinPlan.collectAsStateWithLifecycle()
    val pinRun by core.pinRun.collectAsStateWithLifecycle()
    val feel by core.feel.collectAsStateWithLifecycle()
    androidx.compose.runtime.SideEffect {
        com.erelay.relay.ui.theme.Tone.palette = com.erelay.relay.ui.theme.Palette.of(feel.theme)
        // The one accent the app has is the connection state.
        com.erelay.relay.ui.theme.Tone.mode = conn.mode
    }

    var over by rememberSaveable { mutableStateOf(Over.NONE) }
    var over2 by rememberSaveable { mutableStateOf(Over2.NONE) }
    var notice by remember { mutableStateOf("") }
    // Which page Settings opens on: the menu from the gear, a page from the welcome.
    var prefsStart by rememberSaveable { mutableStateOf("menu") }
    // Which provider the add-account sheet opens on: the card it came from.
    var addFor by rememberSaveable { mutableStateOf("surfshark") }
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingCode by rememberSaveable { mutableStateOf("auto") }
    // How long the last tunnel carried, for the line after it closes.
    var lastSince by remember { mutableStateOf(0L) }
    var closedMinutes by remember { mutableStateOf<Int?>(null) }

    // The meter runs only while the window is in front of somebody.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycle) {
        val watch = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { foreground = true; core.meter(true) }
                Lifecycle.Event.ON_STOP -> { foreground = false; core.meter(false) }
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch); core.meter(false) }
    }

    // The connection coming up is the moment this app exists for; it is
    // felt as well as seen. A failure gets the heavier pattern.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var lastMode by remember { mutableStateOf(conn.mode) }
    LaunchedEffect(conn.mode) {
        if (conn.mode != lastMode && feel.haptics) {
            when (conn.mode) {
                Mode.ON -> haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                Mode.FAIL -> haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                else -> Unit
            }
        }
        lastMode = conn.mode
    }

    LaunchedEffect(conn.mode, conn.since) {
        if (conn.mode == Mode.ON) {
            lastSince = conn.since
            closedMinutes = null
        } else if (conn.mode == Mode.OFF && lastSince > 0) {
            closedMinutes = minutes(lastSince)
            lastSince = 0
        }
        if (conn.mode != Mode.OFF) said = null
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val out = core.connect(pendingCode)
            if (!out.ok) said = out.text to true
        } else {
            core.consentRefused()
        }
    }

    fun consentThenConnect(code: String) {
        val ask = core.consent()
        if (ask != null) {
            consent.launch(ask)
        } else {
            val out = core.connect(code)
            if (!out.ok) said = out.text to true
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whatever the answer, the connect it interrupted goes ahead.
        consentThenConnect(pendingCode)
    }

    fun connect(code: String) {
        pendingCode = code
        said = null
        core.ready().let { if (!it.ok) { said = it.text to true; return } }
        if (core.shouldAskNotifications()) {
            core.askedNotifications()
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        consentThenConnect(code)
    }

    // A folder of configs, from Android's own picker. Copied in by the core.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val r = core.importTree(uri)
            notice = r.text
            Toast.makeText(context, r.text, Toast.LENGTH_LONG).show()
        }
    }

    fun copy(text: String) {
        core.copy(text)
        Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
    }

    val names = remember(countries) { countries.associate { it.code to it.name } }
    val credentialOnFile by core.hasCredentials.collectAsStateWithLifecycle()
    val hasCredentials = accounts.any { it.signedIn } || credentialOnFile
    // Nothing to connect with or through: no account, no servers, no server of
    // its own. That is a first run, and it gets a question rather than a
    // Connect button that cannot work.
    val introSeen by core.introSeen.collectAsStateWithLifecycle()
    val setup by core.setup.collectAsStateWithLifecycle()
    // Usable means something to connect with: an account with servers to race,
    // or a server of its own. Until then the window is the way to get there,
    // not a Connect button that cannot work.
    val usable = (hasCredentials && serverCount > 0) || tunnel.ready
    val gate = when {
        !loaded -> Gate.LOADING
        usable -> Gate.READY
        !introSeen -> Gate.INTRO
        hasCredentials -> Gate.SETUP
        else -> Gate.WELCOME
    }
    // Signed in with nothing to race: fetch and pin, without being asked.
    LaunchedEffect(gate) {
        if (gate == Gate.SETUP && !setup.running && setup.step != "failed") core.prepareServers()
    }
    val showWelcome = gate != Gate.READY

    val (hint, hintBad) = said ?: hintOf(conn, way, seen, seenFailed, loaded, serverCount,
        countries.size, hasCredentials, picked, names, closedMinutes)

    androidx.compose.runtime.CompositionLocalProvider(LocalFeel provides feel) {
    Box(Modifier.fillMaxSize()) {
        when (gate) {
            Gate.LOADING -> Backdrop {}
            Gate.INTRO -> Intro(paused = !foreground || over2 != Over2.NONE) { core.markIntroSeen() }
            Gate.WELCOME -> Welcome(
                paused = !foreground || over != Over.NONE || over2 != Over2.NONE,
                onProvider = { provider -> addFor = provider; over2 = Over2.ADD_ACCOUNT },
                onServer = {
                    core.setWay(Way.SERVER)
                    prefsStart = "connection"
                    over = Over.PREFS
                },
            )
            Gate.SETUP -> SetupScreen(
                setup = setup, run = pinRun,
                paused = !foreground || over2 != Over2.NONE,
                onRetry = { scope.launch { core.prepareServers() } },
                onOtherAccount = { over2 = Over2.ADD_ACCOUNT },
            )
            Gate.READY -> Unit
        }
        if (gate == Gate.READY) Home(
            HomeState(
                conn = conn, realIp = realIp, realIpAsked = realIpAsked, seen = seen,
                seenFailed = seenFailed, traffic = traffic, way = way,
                tunnelReady = tunnel.ready, picked = picked,
                pickLabel = nameOfPick(picked, names, countries.associate { it.code to it.cities }),
                countries = countries.size,
                serverCount = serverCount, loaded = loaded, hasCredentials = hasCredentials,
                deadCount = dead.dead, hint = hint, hintBad = hintBad, feel = feel,
            ),
            orbPaused = over != Over.NONE || over2 != Over2.NONE || !foreground || showWelcome,
            onLog = { over = Over.LOG },
            onDrop = { over = Over.DROP },
            onSettings = { prefsStart = "menu"; over = Over.PREFS },
            onWay = { core.setWay(it) },
            onPick = { over = Over.PICKER },
            onAct = {
                when (conn.mode) {
                    Mode.BUSY, Mode.ON -> core.disconnect()
                    else -> connect(if (way == Way.SERVER) "auto" else picked)
                }
            },
            onCopy = ::copy,
            // A fresh race through a fresh exit: the service drops the old
            // tunnel itself when asked to start another.
            onReconnect = { if (conn.mode == Mode.ON) connect(if (way == Way.SERVER) "auto" else picked) },
        )

        Picker(
            open = over == Over.PICKER,
            m = PickerModel(
                countries = countries, favourites = favourites, sort = sort, picked = picked,
                reach = reach, sources = sources, source = source,
                exitsIn = { core.exitsIn(it) },
                onSort = { core.setSort(it) },
                onStar = { code -> scope.launch { core.toggleFavourite(code) } },
                onTest = { code -> core.testReach(code).let { if (!it.ok) Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show() } },
                onCancelTest = { core.cancelReach() },
                onChoose = { code ->
                    core.remember(code)
                    over = Over.NONE
                    // Choosing is the answer to the question the button asked:
                    // it connects, moving the tunnel if one is up.
                    if (conn.mode != Mode.BUSY) connect(code)
                },
                onSources = { over2 = Over2.SOURCES },
                onTestAll = { core.quickTest().let { if (!it.ok) Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show() } },
                onOpen = { if (core.quickDue()) core.quickTest() },
                connects = conn.mode != Mode.BUSY,
            ),
            onClose = { over = Over.NONE },
        )

        Prefs(
            open = over == Over.PREFS, core = core, accounts = accounts, cards = cards,
            tunnel = tunnel, allowLan = allowLan, serverCount = serverCount,
            countryCount = countries.size, pinPlan = pinPlan, pinRun = pinRun,
            deadCount = dead.dead, asideCount = dead.aside,
            onClose = { over = Over.NONE; notice = "" },
            onAddAccount = { provider -> addFor = provider; over2 = Over2.ADD_ACCOUNT },
            onImport = { importer.launch(null) },
            onDrop = { over = Over.DROP },
            onSplitApps = { over2 = Over2.SPLIT_APPS },
            onSharePlaces = { over2 = Over2.SHARE_PLACES },
            notice = notice,
            start = prefsStart,
            feel = feel,
            onFeel = { core.setFeel(it) },
        )

        LogSheet(over == Over.LOG, live = conn.mode == Mode.ON, hosts = { core.hosts() },
            onClose = { over = Over.NONE })

        DropSheet(over == Over.DROP, dead,
            drop = { core.dropExits(it) }, restore = { core.restoreDropped(it) },
            onClose = { over = Over.NONE })

        SourceSheet(over2 == Over2.SOURCES, sources, source,
            onPick = { key -> scope.launch {
                val r = core.setSource(key)
                if (!r.ok) Toast.makeText(context, r.text, Toast.LENGTH_SHORT).show()
                over2 = Over2.NONE
            } },
            onImport = { over2 = Over2.NONE; importer.launch(null) },
            onClose = { over2 = Over2.NONE })

        // Over Settings → Split tunnel, which it was opened from and goes back to.
        SplitAppsSheet(over2 == Over2.SPLIT_APPS, core, onClose = { over2 = Over2.NONE })

        // Over Settings → Share with other devices, likewise.
        SharePlacesSheet(over2 == Over2.SHARE_PLACES, core, onClose = { over2 = Over2.NONE })

        AddAccount(over2 == Over2.ADD_ACCOUNT, provider = addFor,
            taken = accounts.map { it.provider }.toSet(),
            onClose = { over2 = Over2.NONE },
            addSurfshark = { label, user, pass -> core.addSurfshark(label, user, pass) },
            beginWindscribe = { label, user, pass -> core.beginWindscribe(label, user, pass) },
            finishWindscribe = { token, solution, xs, ys, code -> core.finishWindscribe(token, solution, xs, ys, code) },
            onDone = { msg, added ->
                // A new account is fetched and pinned at once. On a first run
                // the setup screen shows it; added later from Settings, the
                // progress shows under the provider cards in Accounts.
                notice = msg
                over2 = Over2.NONE
                scope.launch { core.prepareServers(added) }
            })
    }
    }



    LaunchedEffect(Unit) { scope.launch { core.refreshList() } }
}

/**
 * The line under the card. The desktop's sentences, with the ones about
 * Windows said the way a phone means them.
 */
private fun hintOf(
    conn: com.erelay.relay.data.Conn, way: Way, seen: com.erelay.relay.data.Place?,
    seenFailed: Boolean, loaded: Boolean, serverCount: Int, countries: Int,
    hasCredentials: Boolean, picked: String, names: Map<String, String>, closedMinutes: Int?,
): Pair<String, Boolean> = when (conn.mode) {
    Mode.ON -> {
        val claimed = if (way == Way.PROVIDER) conn.exitName.take(2).lowercase() else ""
        val measured = seen?.country.orEmpty()
        when {
            measured.isNotEmpty() && claimed.isNotEmpty() && measured != claimed &&
                !(measured == "gb" && claimed == "uk") ->
                "Sold as ${countryName(claimed)}, but its traffic really comes out in " +
                    "${countryName(measured)}. What websites see is the second one." to false
            seenFailed -> "Connected, but the independent check did not answer. " +
                "If pages load, it is fine." to false
            else -> "DNS is re-asked over HTTPS. UDP does not cross this tunnel." to false
        }
    }
    Mode.FAIL -> failureSaid(conn.failure.orEmpty(), picked, names) to (conn.failure != "cancelled")
    Mode.BUSY -> "" to false
    Mode.OFF -> when {
        closedMinutes != null && closedMinutes > 0 ->
            "Tunnel closed. Carried for $closedMinutes minute${if (closedMinutes == 1) "" else "s"}." to false
        closedMinutes != null -> "Tunnel closed." to false
        !loaded -> "" to false
        way == Way.PROVIDER && serverCount == 0 ->
            "No servers yet. Get them under Settings → Accounts." to false
        way == Way.PROVIDER && !hasCredentials ->
            "Add an account under Settings → Accounts to connect." to false
        way == Way.PROVIDER -> "$countries countries ready." to false
        else -> "" to false
    }
}

/** The core's word for why, turned into what to do about it. */
fun failureSaid(why: String, picked: String, names: Map<String, String>): String = when {
    why.startsWith("all-refused") -> if (picked == "auto")
        "No server accepted just now. That happens — wait a moment and try again."
    else "No ${nameOfPick(picked, names)} server accepted just now. Try again, or let it take " +
        "whichever answers first."
    why.startsWith("credentials-refused") ->
        "${why.removePrefix("credentials-refused").trimStart(':', ' ').ifEmpty { "Every server turned this account away" }}. " +
            "So this is the account rather than the servers. Check the service username and " +
            "password on the provider's manual-setup page — they are not the email you log in " +
            "with, and they change when you reset them there."
    why.startsWith("no-servers") -> "No servers for that country."
    // The service narrowed to the pick's configs and none were in the folder
    // any more: re-pinned under new names, or set aside, since the list was read.
    why.startsWith("pick-gone") -> "Those servers are no longer in the folder - they were " +
        "re-pinned or set aside. Pick the place again from the list."
    // Every exit behind the pick is one provider's, and that provider has no
    // account here: a Windscribe-only phone picking a Surfshark city.
    why.startsWith("no credentials for ") -> "There is no " +
        providerName(why.removePrefix("no credentials for ").trim()) + " account on this phone, " +
        "and everything behind that pick is theirs. Add one under Settings → Accounts, or pick " +
        "somewhere the other provider has."
    why.startsWith("no-credentials") || why.startsWith("no credentials") ->
        "There is no account to sign in with. Add one under Settings → Accounts."
    why.startsWith("permission refused") ->
        "Android was not given permission to start a VPN. Connect again and allow it."
    why.startsWith("cancelled") -> "Cancelled."
    why.isNotEmpty() -> "Could not connect: $why"
    else -> "Could not connect. Try once more."
}
