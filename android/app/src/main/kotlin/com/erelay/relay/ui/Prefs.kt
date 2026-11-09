package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erelay.relay.Accounts
import com.erelay.relay.data.Account
import com.erelay.relay.data.Mode
import com.erelay.relay.data.PinPlan
import com.erelay.relay.data.PinRun
import com.erelay.relay.data.ProviderCard
import com.erelay.relay.data.RelayCore
import com.erelay.relay.data.Said
import com.erelay.relay.data.TunnelPlan
import com.erelay.relay.ui.theme.Palette
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [head] is what the sheet's own title says once the screen is open: one line,
 * cut short when it does not fit, so a long menu name gets a short one there.
 */
private enum class Screen(val title: String, val note: String, val head: String = title) {
    MENU("Settings", ""),
    ACCOUNTS("Accounts", "Providers, sign-ins and servers"),
    CONNECTION("Connection", "Your own tunnel, and the local network"),
    SPLIT("Split tunnel", "Which apps and sites go around Relay"),
    LAN("Share this connection", "Let devices on the same Wi-Fi use it", head = "Share this connection"),
    SHARE("Configs for other devices", "Links and QR codes", head = "Other devices"),
    SERVERS("Servers", "Finding, pinning and timing them"),
    LOOK("Look and feel", "Theme, haptics, the orb"),
    ABOUT("About", "Version and where things are kept"),
}

/**
 * Settings: a menu of screens. The panes that only mean something on Windows
 * are left out - there is no system proxy to route and no port to listen on,
 * because the tun carries everything.
 */
@Composable
fun Prefs(
    open: Boolean,
    core: RelayCore,
    accounts: List<Account>,
    cards: Map<String, ProviderCard>,
    tunnel: TunnelPlan,
    allowLan: Boolean,
    serverCount: Int,
    countryCount: Int,
    pinPlan: PinPlan,
    pinRun: PinRun,
    deadCount: Int,
    asideCount: Int,
    onClose: () -> Unit,
    onAddAccount: (String) -> Unit,
    onImport: () -> Unit,
    onDrop: () -> Unit,
    /** The split tunnel's app picker, a sheet over this one. */
    onSplitApps: () -> Unit,
    /** The chooser of locations to share with other devices, a sheet over this one. */
    onSharePlaces: () -> Unit,
    notice: String,
    feel: com.erelay.relay.data.Feel,
    onFeel: (com.erelay.relay.data.Feel) -> Unit,
    /** Where to open: "menu", "accounts" or "connection". */
    start: String = "menu",
) {
    var screen by rememberSaveable { mutableStateOf(Screen.MENU) }
    LaunchedEffect(open, start) {
        screen = if (!open) Screen.MENU else when (start) {
            "accounts" -> Screen.ACCOUNTS
            "connection" -> Screen.CONNECTION
            else -> Screen.MENU
        }
    }
    LaunchedEffect(open, screen) {
        if (open) {
            core.refreshAccounts()
            if (screen == Screen.SERVERS) core.refreshPinPlan()
        }
    }

    Sheet(
        open, screen.head, onClose,
        onBack = if (screen == Screen.MENU) null else ({ screen = Screen.MENU }),
    ) {
        when (screen) {
            Screen.MENU -> Menu { screen = it }
            Screen.ACCOUNTS -> AccountsScreen(core, accounts, cards, pinRun, onAddAccount, notice)
            Screen.CONNECTION -> Connection(core, tunnel, allowLan)
            // Its own screen rather than a third pane under Connection: the
            // list it keeps can run long, and Connection is long already.
            Screen.SPLIT -> SplitScreen(core, onSplitApps)
            Screen.LAN -> LanShare(core)
            // Beside the own server's screens, because everything on it goes
            // through that server: links for other devices, and the
            // locations the server leaves by for them.
            Screen.SHARE -> ShareScreen(core, tunnel, onSharePlaces)
            Screen.SERVERS -> Servers(core, serverCount, countryCount, pinPlan, pinRun,
                deadCount, asideCount, onImport, onDrop)
            Screen.LOOK -> Look(feel, onFeel)
            Screen.ABOUT -> About(core)
        }
    }
}

@Composable
private fun Menu(go: (Screen) -> Unit) {
    Section("Connect") {
        Group {
            NavRow(Screen.ACCOUNTS.title, Screen.ACCOUNTS.note, { go(Screen.ACCOUNTS) }, Glyph.key)
            Sep()
            NavRow(Screen.CONNECTION.title, Screen.CONNECTION.note, { go(Screen.CONNECTION) }, Glyph.tower)
            Sep()
            NavRow(Screen.SPLIT.title, Screen.SPLIT.note, { go(Screen.SPLIT) }, Glyph.route)
        }
    }
    Section("Share") {
        Group {
            NavRow(Screen.LAN.title, Screen.LAN.note, { go(Screen.LAN) }, Glyph.share)
            Sep()
            NavRow(Screen.SHARE.title, Screen.SHARE.note, { go(Screen.SHARE) }, Glyph.qr)
        }
    }
    Section("App") {
        Group {
            NavRow(Screen.SERVERS.title, Screen.SERVERS.note, { go(Screen.SERVERS) }, Glyph.folder)
            Sep()
            NavRow(Screen.LOOK.title, Screen.LOOK.note, { go(Screen.LOOK) }, Glyph.layers)
            Sep()
            NavRow(Screen.ABOUT.title, Screen.ABOUT.note, { go(Screen.ABOUT) }, Glyph.info)
        }
    }
}

// -- accounts -----------------------------------------------------------------

/**
 * Accounts: one section per provider rather than a list of accounts in one
 * place and the providers' steps in another. Everything about Surfshark - the
 * accounts it has, how far along it is, and the one thing to do next - is
 * under Surfshark's heading, so there is nothing to match up between two lists.
 */
@Composable
private fun AccountsScreen(
    core: RelayCore, accounts: List<Account>, cards: Map<String, ProviderCard>, run: PinRun,
    onAddAccount: (String) -> Unit, notice: String,
) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf("") }
    val providers = listOf(Accounts.SURFSHARK, Accounts.WINDSCRIBE)
    val ready = providers.count { cards[it]?.usable == true }

    // The summary is a sentence, not a card: the count is the one big thing.
    Column(Modifier.padding(top = Space.x4, start = Space.x1, end = Space.x1)) {
        Text("$ready of ${providers.size} ready", style = Type.display)
        Text(when {
            ready == providers.size -> "Every provider can connect."
            accounts.isEmpty() -> "Add an account to connect."
            ready > 0 -> "Enough to connect. The other provider is optional."
            else -> "Almost there: get the servers and pin them."
        }, style = Type.body, modifier = Modifier.padding(top = Space.x1))
    }
    if (notice.isNotEmpty()) SaidLine(notice)

    for (p in providers) {
        ProviderAccounts(p, cards[p] ?: ProviderCard(), accounts.filter { it.provider == p },
            busy = busy == p,
            onAdd = { onAddAccount(p) },
            onUse = { a -> scope.launch { said = core.useAccount(a.id) } },
            onRemove = { a -> scope.launch { said = core.removeAccount(a.id) } },
        ) { action ->
            busy = p
            said = null
            scope.launch {
                said = when (action) {
                    "servers" -> if (p == Accounts.SURFSHARK) core.surfsharkServers()
                                 else core.windscribeServers(freeOnly = false)
                    // Pinned right here: point the run at this provider's pile and
                    // start it, and the progress shows under the lists.
                    "pin" -> core.pinFor(p).let { if (it.ok) core.startPin().takeIf { s -> !s.ok } else it }
                    "refresh" -> core.windscribeRefresh()
                    else -> null
                }
                busy = ""
            }
        }
    }

    if (run.running || run.finished.isNotEmpty()) {
        Section("Pinning") { Block { PinRunView(run) { core.cancelPin() } } }
    }
    said?.let { SaidLine(it.text, bad = !it.ok) }
}

/**
 * One provider: how it stands, its accounts, and the one button that is next.
 * A group of rows, and under it the action.
 */
@Composable
private fun ProviderAccounts(
    provider: String, card: ProviderCard, accounts: List<Account>, busy: Boolean,
    onAdd: () -> Unit, onUse: (Account) -> Unit, onRemove: (Account) -> Unit, act: (String) -> Unit,
) {
    val name = providerName(provider)
    Section(name, note = when {
        card.usable && card.waiting > 0 -> "${card.servers} ready · ${card.waiting} to pin"
        card.usable -> "${card.servers} servers ready to connect"
        card.servers > 0 -> "${card.servers} servers · add an account to use them"
        card.account && card.waiting > 0 -> "${card.waiting} servers waiting to be pinned"
        card.account -> "Signed in · no servers yet"
        else -> "Not set up"
    }) {
        Group {
            // Windscribe only: its proxy credential goes stale now and then, and
            // this fetches another with the session already held - no puzzle.
            SettingRow("Account", desc = if (card.account) "Signed in" else "None yet",
                icon = if (provider == Accounts.WINDSCRIBE) Glyph.windscribe else Glyph.surfshark) {
                if (provider == Accounts.WINDSCRIBE && card.account) {
                    IconButton(Glyph.refresh, "Refresh the Windscribe credential", { act("refresh") },
                        size = 40.dp, enabled = !busy)
                } else if (card.account) {
                    Icon(Glyph.check, null, Modifier.size(18.dp), tint = Tone.ok)
                }
            }
            Sep()
            SettingRow("Servers", desc = if (card.servers > 0 || card.waiting > 0) "Fetched" else "Not fetched yet") {
                if (card.servers > 0 || card.waiting > 0) Icon(Glyph.check, null, Modifier.size(18.dp), tint = Tone.ok)
            }
            Sep()
            SettingRow("Pinned", desc = if (card.servers > 0) "${card.servers} to real addresses" else "Nothing pinned yet") {
                if (card.servers > 0) Icon(Glyph.check, null, Modifier.size(18.dp), tint = Tone.ok)
            }
            for (a in accounts) {
                Sep()
                AccountLine(a, onUse = { onUse(a) }, onRemove = { onRemove(a) })
            }
        }
        val (step, label) = when {
            !card.account -> "add" to "Add a $name account"
            card.waiting > 0 -> "pin" to "Pin ${card.waiting} waiting"
            card.servers == 0 -> "servers" to "Get servers"
            else -> "servers" to "Update servers"
        }
        ButtonRow {
            Btn(if (busy) "Working…" else label, { if (step == "add") onAdd() else act(step) },
                modifier = Modifier.weight(1f), enabled = !busy, strong = step != "servers" || card.servers == 0,
                icon = when (step) {
                    "add" -> Glyph.plus
                    "pin" -> Glyph.pin
                    else -> Glyph.refresh
                })
        }
    }
}

/**
 * One account: its name and state, and Use. Remove asks once more in place,
 * because it cannot be undone and the button is a thumb's width from Use.
 */
@Composable
private fun AccountLine(a: Account, onUse: () -> Unit, onRemove: () -> Unit) {
    var sure by remember(a.id) { mutableStateOf(false) }
    val title = a.label.ifEmpty { a.username }
    SettingRow(title, desc = listOfNotNull(if (a.active) "In use" else null,
        if (a.signedIn) "signed in" else "not signed in").joinToString(" · ")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                sure -> {
                    Btn("Remove", onRemove, danger = true)
                    IconButton(Glyph.x, "Keep it", { sure = false }, size = 40.dp)
                }
                else -> {
                    if (!a.active) Btn("Use", onUse)
                    IconButton(Glyph.x, "Remove ${title}", { sure = true }, size = 40.dp, tint = Tone.text3)
                }
            }
        }
    }
}

/** A pin run as it goes: a bar, the counts, the last address, and Stop. */
@Composable
private fun PinRunView(run: PinRun, onStop: () -> Unit) {
    if (run.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pinning", style = Type.strong, modifier = Modifier.weight(1f))
            Btn("Stop", onStop, danger = true)
        }
        Spacer(Modifier.height(Space.x2))
        Progress(run.done, run.total)
        Text("${run.done} of ${run.total} · ${run.reachable} answering · ${run.unreachable} not" +
            if (run.forged > 0) " · ${run.forged} forged answers seen" else "",
            style = Type.mono, modifier = Modifier.padding(top = Space.x2))
        if (run.last.isNotEmpty()) Text(run.last, style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Text(run.finished, style = Type.body)
    }
}

// -- connection -----------------------------------------------------------------

@Composable
private fun Connection(core: RelayCore, tunnel: TunnelPlan, allowLan: Boolean) {
    val scope = rememberCoroutineScope()
    var lanSaid by remember { mutableStateOf("") }

    Section("Local network") {
        Group {
            SwitchRow("Reach the local network",
                "The printer, the router's page, a NAS and adb over Wi-Fi stay reachable while the " +
                    "tunnel is up. A local address goes nowhere beyond the house.",
                allowLan, { on ->
                    val up = core.setAllowLan(on)
                    lanSaid = if (up) "Takes effect on the next connect." else ""
                })
        }
        SaidLine(lanSaid)
    }

    var domain by remember(tunnel.domain) { mutableStateOf(tunnel.domain) }
    var password by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }

    // The short way first: a server that is already set up printed a
    // relay:// key, and the key is the whole of what this asks for.
    Section("Your own tunnel",
        note = when {
            busy -> "Asking…"
            tunnel.ready -> "Ready."
            else -> "Not set up. Paste a key, or fill in the server below."
        },
        help = "A server of your own, by its IP address - or a domain, behind Cloudflare or " +
            "not. Type the address and copy the install command: it carries passwords this " +
            "app made, so once it has run on the server there is only Test left to press. " +
            "The tunnel password opens the tunnel; the API password is how server + exit " +
            "tells the server which location to leave by.") {
        Block {
            FieldLabel("Key")
            Field(key, { key = it }, "relay://…", mono = true, secret = true)
            ButtonRow {
                Btn("Use key", {
                    busy = true
                    said = null
                    scope.launch {
                        val r = core.importKey(key)
                        // Out of memory the moment it is on disk: it holds three passwords.
                        if (r.ok) key = ""
                        said = r
                        busy = false
                    }
                }, strong = !tunnel.ready, enabled = !busy && key.isNotBlank(), icon = Glyph.key)
            }
            if (tunnel.hasSs) {
                Spacer(Modifier.height(Space.x3))
                Segment(listOf("ss", "ws"), tunnel.transport,
                    { if (it == "ss") "Shadowsocks" else "WebSocket" },
                    { t -> said = core.setTunnelTransport(t) }, enabled = !busy)
                if (tunnel.transport == "ss") {
                    Text("Straight to ${tunnel.domain}:${tunnel.ssPort}, and :${tunnel.ssPort + 1} " +
                        "for server + exit.", style = Type.mono, modifier = Modifier.padding(top = Space.x2))
                }
            }
        }
    }

    Section("Server") {
        Block {
            FieldLabel("Address")
            Field(domain, { domain = it }, "Server IP or domain", mono = true)
            Spacer(Modifier.height(Space.x3))
            FieldLabel("Tunnel password")
            Field(password, { password = it },
                if (tunnel.hasPassword) "Saved" else "Made for you", secret = true)
            Spacer(Modifier.height(Space.x3))
            FieldLabel("API password")
            Field(api, { api = it },
                if (tunnel.hasApiPassword) "Saved" else "Made for you", secret = true)
            ButtonRow {
                Btn("Save", {
                    core.saveTunnel(domain, password, api)
                    password = ""
                    api = ""
                    said = Said(true, "Saved.")
                }, strong = true)
                Btn(if (busy) "Testing…" else "Test", {
                    busy = true
                    said = null
                    scope.launch { said = core.testTunnel(); busy = false }
                }, enabled = !busy && tunnel.ready)
            }
            // An address has no other way in: it is not behind Cloudflare.
            if (!tunnel.isAddress) ButtonRow {
                Btn("Find another way in", {
                    busy = true
                    said = null
                    scope.launch { said = core.rescanEdges(); busy = false }
                }, enabled = !busy && tunnel.domain.isNotEmpty(), icon = Glyph.refresh)
            }
            if (tunnel.pin.isNotEmpty()) {
                Text("Certificate: sha256 ${tunnel.pin.take(16)}…", style = Type.mono,
                    modifier = Modifier.padding(top = Space.x3))
            }
            if (tunnel.edges.isNotEmpty() && !tunnel.isAddress) {
                Text("Way in: ${tunnel.edges[0]}" + if (tunnel.edges.size > 1) "  (+${tunnel.edges.size - 1})" else "",
                    style = Type.mono, modifier = Modifier.padding(top = Space.x3))
            }
            said?.let { SaidLine(it.text, bad = !it.ok) }
        }
    }

    Section("Setting up the server",
        help = "Paste these into an SSH session on the server, as root. Install sets up " +
            "everything in one go, with this app's passwords - run it again any time to " +
            "repair or update. Status shows what is running; Uninstall takes the tunnel " +
            "off and leaves everything else on the server alone.") {
        Group {
            SettingRow("Copy the install command", desc = "Run on the server as root",
                icon = Glyph.copy, onClick = {
                    val cmd = core.installCommand(domain)
                    said = when {
                        domain.isBlank() -> Said(false, "Type the server's IP or domain first.")
                        cmd == null -> Said(false, "The installer is missing from this build.")
                        else -> { core.copy(cmd); Said(true, "Copied. Paste it on the server, then press Test.") }
                    }
                })
            Sep()
            SettingRow("Copy status", desc = "Shows what is running", icon = Glyph.copy, onClick = {
                core.copy(core.statusCommand()); said = Said(true, "Copied the status command.")
            })
            Sep()
            SettingRow("Copy uninstall", desc = "Leaves the rest of the server alone", icon = Glyph.copy,
                onClick = { core.copy(core.uninstallCommand()); said = Said(true, "Copied the uninstall command.") })
        }
    }
}

// -- share this connection ---------------------------------------------------------

/**
 * Settings → Share this connection: the tunnel offered, as a proxy, to the
 * devices on the Wi-Fi (or the hotspot) this phone is on. The service starts
 * it once the tunnel is up; the switch here starts or stops it at once.
 */
@Composable
private fun LanShare(core: RelayCore) {
    val scope = rememberCoroutineScope()
    val conn by core.conn.collectAsStateWithLifecycle()
    val on by core.lanShare.collectAsStateWithLifecycle()
    val port by core.lanSharePort.collectAsStateWithLifecycle()
    val saved by core.lanSharePassword.collectAsStateWithLifecycle()
    val lan by core.lanState.collectAsStateWithLifecycle()
    val up = conn.mode == Mode.ON
    var said by remember { mutableStateOf<Said?>(null) }

    // Asked every couple of seconds while this screen is the one showing.
    LaunchedEffect(Unit) {
        while (isActive) {
            core.refreshLanShare()
            delay(2500)
        }
    }

    Section("Share this connection",
        note = "Phones and laptops on the same Wi-Fi or hotspot can use Relay's connection through " +
            "this phone. Only devices on the same network can reach it, and only while Relay is connected.") {
        Group {
            SwitchRow("Share on Wi-Fi",
                when {
                    !on -> "Off"
                    !up -> "Starts when Relay connects."
                    lan.on -> "Sharing now"
                    else -> "Starting…"
                },
                on, { v -> scope.launch { said = core.setLanShare(v).takeIf { !it.ok } } })
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }

    if (on) {
        Section("Type this on the other device") {
            val shownPort = if (lan.on && lan.port > 0) lan.port else port
            Group {
                if (lan.on && lan.addrs.isNotEmpty()) {
                    lan.addrs.forEachIndexed { i, a ->
                        if (i > 0) Sep()
                        AddressRow(a, shownPort) { core.copy("$a:$shownPort") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                        Text(if (!up) "Connect first. The address shows here once Relay is up."
                             else "No Wi-Fi or hotspot address found. Join a Wi-Fi network or turn on the hotspot.",
                            style = Type.body)
                        Text("Port $shownPort", style = Type.mono, modifier = Modifier.padding(top = Space.x2))
                    }
                }
            }
            Text("Tap to copy.", style = Type.caption, modifier = Modifier.padding(start = Space.x1, top = Space.x1))
            Group(Modifier.padding(top = Space.x3)) {
                SettingRow("Devices using it", desc = "Connected right now") {
                    Text("${if (lan.on) lan.clients else 0}", style = Type.monoBody)
                }
            }
        }

        Section("Port and password") {
            var portText by remember(port) { mutableStateOf(port.toString()) }
            var pass by remember(saved) { mutableStateOf(saved) }
            Block {
                FieldLabel("Port")
                Field(portText, { portText = it.filter(Char::isDigit).take(5) }, "8877",
                    mono = true, keyboard = KeyboardType.Number)
                Spacer(Modifier.height(Space.x3))
                FieldLabel("Password", hint = "optional")
                Field(pass, { pass = it }, "None", secret = true)
                Text("When set, the other device has to sign in to the proxy: user name relay, and this password. On an iPhone, turn Authentication on under Configure Proxy.",
                    style = Type.caption, modifier = Modifier.padding(top = Space.x2))
                ButtonRow {
                    Btn("Save", {
                        scope.launch {
                            val p = portText.toIntOrNull() ?: 0
                            val a = core.setLanSharePort(p)
                            said = if (!a.ok) a else core.setLanSharePassword(pass).takeIf { !it.ok } ?: Said(true, "Saved.")
                        }
                    }, strong = true, enabled = portText != port.toString() || pass.trim() != saved)
                }
            }
        }
    }

    Section("How to use it") {
        Group {
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text("iPhone or iPad", style = Type.strong)
                Text("Settings → Wi-Fi → the (i) next to this network → Configure Proxy → Manual. " +
                    "Enter the server and port above.", style = Type.body,
                    modifier = Modifier.padding(top = Space.x1))
            }
            Sep()
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text("Another phone or a laptop", style = Type.strong)
                Text("Set the Wi-Fi network's proxy to manual HTTP, with the same server and port.",
                    style = Type.body, modifier = Modifier.padding(top = Space.x1))
            }
            Sep()
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text("With a hotspot", style = Type.strong)
                Text("Turn on this phone's hotspot, join it from the other device, and use the address " +
                    "shown above. It changes if the hotspot is turned off and on.", style = Type.body,
                    modifier = Modifier.padding(top = Space.x1))
            }
        }
    }
}

/** The three things a proxy form asks for, in the order it asks; the whole block copies. */
@Composable
private fun AddressRow(addr: String, port: Int, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ProxyLine("Proxy", "Manual")
            ProxyLine("Server", addr)
            ProxyLine("Port", port.toString())
        }
        Icon(Glyph.copy, "Copy", Modifier.size(20.dp), tint = Tone.text3)
    }
}

@Composable
private fun ProxyLine(label: String, value: String) {
    Row(Modifier.padding(vertical = Space.x1)) {
        Text(label, style = Type.caption, modifier = Modifier.width(56.dp))
        Text(value, style = Type.monoBody, modifier = Modifier.weight(1f))
    }
}

// -- servers ---------------------------------------------------------------------

@Composable
private fun Servers(
    core: RelayCore, serverCount: Int, countryCount: Int, plan: PinPlan, run: PinRun,
    deadCount: Int, asideCount: Int, onImport: () -> Unit, onDrop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Said?>(null) }

    Section("Server folder", note = "$serverCount servers · $countryCount countries",
        help = "Configs pinned to real addresses live here, in the app's own folder. Import a " +
            "folder of .ovpn files from the phone - they are copied in - or get them from a " +
            "provider under Accounts.") {
        Block {
            Text(core.dataFolder, style = Type.mono, maxLines = 3, overflow = TextOverflow.Ellipsis)
            ButtonRow { Btn("Import a folder…", onImport, icon = Glyph.folder) }
        }
    }

    Section("Pin to real addresses",
        note = when {
            run.running -> "Pinning…"
            plan.total > 0 -> "${plan.total} waiting"
            else -> null
        },
        help = "A downloaded config names a host, and on this line the resolver answers for " +
            "that host with a forged address. Pinning resolves each one over DNS-over-HTTPS " +
            "and writes the real address in. Direct asks from here; Through the tunnel asks " +
            "from wherever the tunnel comes out.") {
        Group {
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Segment(listOf("direct", "proxy"), plan.route.ifEmpty { "direct" },
                    { if (it == "direct") "Direct" else "Through the tunnel" },
                    { r -> scope.launch { core.setPinRoute(route = r) } }, enabled = !run.running)
            }
            Sep()
            SettingRow("Addresses per host",
                desc = "More means more exits to race and a longer pin.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Glyph.minus, "Fewer", { scope.launch { core.setPinRoute(maxIps = plan.maxIps - 1) } },
                        enabled = !run.running && plan.maxIps > 1)
                    Text(plan.maxIps.toString(), style = Type.monoBody)
                    IconButton(Glyph.plus, "More", { scope.launch { core.setPinRoute(maxIps = plan.maxIps + 1) } },
                        enabled = !run.running && plan.maxIps < 32)
                }
            }
            Sep()
            SwitchRow("Check each address answers",
                "Slower, and the folder only holds exits that were alive when pinned.",
                plan.test, { on -> scope.launch { core.setPinRoute(test = on) } })
        }
        for (b in plan.blockers) SaidLine(b.say, bad = true)
        if (plan.total > 0 && !run.running) {
            Text("${plan.total} to pin from ${plan.folder.substringAfterLast('/')}" +
                if (plan.minutes > 0) ", about ${plan.minutes} min" else "", style = Type.caption,
                modifier = Modifier.padding(start = Space.x1, top = Space.x2))
        }
        if (run.running) {
            Spacer(Modifier.height(Space.x3))
            Progress(run.done, run.total)
            Text("${run.done} of ${run.total} · ${run.reachable} answering · ${run.unreachable} not" +
                if (run.forged > 0) " · ${run.forged} forged answers seen" else "",
                style = Type.mono, modifier = Modifier.padding(top = Space.x2))
            if (run.last.isNotEmpty()) Text(run.last, style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!run.running && run.finished.isNotEmpty()) SaidLine(run.finished)
        ButtonRow {
            if (run.running) Btn("Stop", { core.cancelPin() }, danger = true)
            else Btn("Pin them", {
                said = core.startPin().takeIf { !it.ok }
            }, strong = true, enabled = plan.total > 0 && plan.blockers.isEmpty())
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }

    Section("Exits that stopped answering",
        help = "Exits that refused or timed out the last time the bolt on a country tested " +
            "them. Setting them aside keeps the race off them; they can be put back.") {
        Group {
            SettingRow(when {
                deadCount > 0 -> "$deadCount to set aside"
                asideCount > 0 -> "$asideCount set aside earlier"
                else -> "None"
            }, onClick = if (deadCount > 0 || asideCount > 0) onDrop else null, icon = Glyph.archive) {
                if (deadCount > 0 || asideCount > 0) Chevron()
            }
        }
    }

    Timing(core)
}

/**
 * Timing servers: one exit per country, so the Ping order in the exit list
 * means something. When it last ran, whether it runs on its own, and the
 * button that runs it now.
 */
@Composable
private fun Timing(core: RelayCore) {
    val quickAt by core.quickAt.collectAsStateWithLifecycle()
    val auto by core.autoQuick.collectAsStateWithLifecycle()
    val reach by core.reach.collectAsStateWithLifecycle()
    var said by remember { mutableStateOf<Said?>(null) }
    val running = reach.running && reach.code == com.erelay.relay.data.QUICK
    val ago = if (quickAt <= 0L) null else minutes(quickAt / 1000)

    Section("Timing servers",
        note = when {
            running -> "Timing…"
            ago == null -> "Never timed"
            ago < 60 -> "Timed ${ago}m ago"
            ago < 60 * 24 -> "Timed ${ago / 60}h ago"
            else -> "Timed ${ago / (60 * 24)}d ago"
        },
        help = "One exit from each country is asked how quickly its proxy answers - six at " +
            "a time, a few seconds each, so a minute or so for the whole list and gentle on " +
            "the line. Only with the tunnel down: through it, every time would be the " +
            "tunnel's. The bolt on a country in the exit list times all of its exits.") {
        Group {
            SwitchRow("Time the list on its own",
                "When the exit list opens and the last timing is over six hours old. Never while the tunnel is up.",
                auto, { core.setAutoQuick(it) })
            Sep()
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text(when {
                    running -> "Timing ${reach.done} of ${reach.total} countries…"
                    reach.code == com.erelay.relay.data.QUICK && reach.said.isNotEmpty() -> reach.said
                    ago == null -> "No country has been timed yet, so the Ping order is a guess."
                    else -> "The exit list is ordered by these times."
                }, style = Type.body)
                if (running) {
                    Spacer(Modifier.height(Space.x2))
                    Progress(reach.done, reach.total)
                }
                ButtonRow {
                    if (running) Btn("Stop", { core.cancelReach() }, danger = true, modifier = Modifier.weight(1f))
                    else Btn("Time every country", { said = core.quickTest().takeIf { !it.ok } },
                        strong = true, enabled = !reach.running, icon = Glyph.bolt, modifier = Modifier.weight(1f))
                }
            }
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }
}

@Composable
private fun Progress(done: Int, total: Int) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Tone.surface2)) {
        Box(Modifier.fillMaxWidth(if (total > 0) (done.toFloat() / total).coerceIn(0.02f, 1f) else 0.02f)
            .height(4.dp).background(Tone.text))
    }
}

// -- look and feel ----------------------------------------------------------------

@Composable
private fun Look(feel: com.erelay.relay.data.Feel, onFeel: (com.erelay.relay.data.Feel) -> Unit) {
    val chosen = Palette.of(feel.theme)
    Section("Theme",
        help = "The page and every surface on it. Text and the status colours stay the same " +
            "in all of them. Black switches an OLED screen's pixels off, which saves battery on " +
            "a phone that keeps a tunnel up all evening.") {
        // Three across; two rows when a narrow phone or a large font would cut a name.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val across = if (maxWidth >= 88.dp * scale * 3 + 16.dp) 3 else 2
            Column {
                for (row in Palette.entries.chunked(across)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = Space.x1),
                        horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        for (p in row) Swatch(p, p == chosen, Modifier.weight(1f)) {
                            onFeel(feel.copy(theme = p.key))
                        }
                        // A short last row keeps its swatches the size of the rest.
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    Section("Touch") {
        Group {
            SwitchRow("Haptic feedback",
                "A light tick under each press, and a buzz when the tunnel comes up or fails.",
                feel.haptics, { onFeel(feel.copy(haptics = it)) })
            Sep()
            SwitchRow("Tap the orb",
                "Tap to connect, tap again to disconnect. Hold while connected to reconnect " +
                    "through a fresh exit. Only the sphere itself.",
                feel.orbTap, { onFeel(feel.copy(orbTap = it)) })
        }
    }

    Section("Motion") {
        Group {
            SwitchRow("Animate the orb",
                "Off draws it once and leaves it still, which is less for the GPU to do.",
                feel.orbMotion, { onFeel(feel.copy(orbMotion = it)) })
            Sep()
            SwitchRow("Show the traffic meter",
                "The live wave of what is going up and down while connected.",
                feel.meter, { onFeel(feel.copy(meter = it)) })
        }
    }

    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var tileSaid by remember { mutableStateOf("") }
        Section("Quick settings") {
            Group {
                SettingRow("Add the Relay tile",
                    desc = "In the panel you pull down from the top: one press connects, another disconnects.",
                    icon = Glyph.bolt, onClick = {
                        val bar = context.getSystemService(android.app.StatusBarManager::class.java)
                        bar?.requestAddTileService(
                            android.content.ComponentName(context, com.erelay.relay.RelayTile::class.java),
                            "Relay",
                            android.graphics.drawable.Icon.createWithResource(context, com.erelay.relay.R.drawable.ic_relay),
                            context.mainExecutor,
                        ) { result ->
                            tileSaid = when (result) {
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Added."
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "It is already there."
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> ""
                                else -> "This phone did not offer to add it. Edit the quick-settings panel and drag Relay in."
                            }
                        }
                    })
            }
            SaidLine(tileSaid)
        }
    }
}

/** One theme as a little picture of itself: its page, a surface on it. */
@Composable
private fun Swatch(p: Palette, on: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(Shape.control)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .press(shape = shape, sink = true, feel = HapticFeedbackType.SegmentTick, onClick = onPick)
                .clip(shape)
                .background(p.bg)
                .border(if (on) 2.dp else 1.dp, if (on) Tone.text else Tone.strokeStrong, shape)
                .padding(Space.x2),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(Space.x1)).background(p.surface)
                .border(1.dp, p.stroke, RoundedCornerShape(Space.x1)))
        }
        Spacer(Modifier.height(Space.x2))
        Text(p.label, style = Type.caption.copy(color = if (on) Tone.text else Tone.text2,
            fontWeight = if (on) FontWeight.W600 else FontWeight.W400), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

// -- about -----------------------------------------------------------------------

@Composable
private fun About(core: RelayCore) {
    Section("Relay for Android",
        help = "Accounts are kept with a key the Android keystore holds; configs, pins and " +
            "test results are plain files in the data folder.") {
        Group {
            Line("Version", core.appVersion)
            Sep()
            Line("Core", core.version)
            Sep()
            Line("Data", core.dataFolder)
        }
    }
}

/** A label and its value in two columns; the value wraps rather than runs off. */
@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(Space.x4)) {
        Text(label, style = Type.caption, modifier = Modifier.width(64.dp))
        Text(value, style = Type.mono.copy(color = Tone.text), modifier = Modifier.weight(1f))
    }
}
