package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.Accounts
import com.erelay.relay.data.Account
import com.erelay.relay.data.PinPlan
import com.erelay.relay.data.PinRun
import com.erelay.relay.data.ProviderCard
import com.erelay.relay.data.RelayCore
import com.erelay.relay.data.Said
import com.erelay.relay.data.TunnelPlan
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.launch

private enum class Screen(val title: String, val note: String) {
    MENU("Settings", ""),
    SIGN_IN("Sign-in", "the accounts this app can use"),
    CONNECTION("Connection", "your own tunnel, and what it leaves alone"),
    SERVERS("Servers", "finding them, pinning them, timing them"),
    LOOK("Look and feel", "theme, haptics, the orb"),
    ABOUT("About this copy", "paths, versions"),
}

/**
 * Settings: a menu of four screens, the desktop's, with the panes that only
 * mean something on Windows left out - there is no system proxy to route and
 * no port to listen on, because the tun carries everything.
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
    notice: String,
    feel: com.erelay.relay.data.Feel,
    onFeel: (com.erelay.relay.data.Feel) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.MENU) }
    LaunchedEffect(open) { if (!open) screen = Screen.MENU }
    LaunchedEffect(open, screen) {
        if (open) {
            core.refreshAccounts()
            if (screen == Screen.SERVERS) core.refreshPinPlan()
        }
    }

    Sheet(
        open, "This copy", screen.title, onClose,
        onBack = if (screen == Screen.MENU) null else ({ screen = Screen.MENU }),
    ) {
        when (screen) {
            Screen.MENU -> {
                Spacer(Modifier.height(4.dp))
                for (s in listOf(Screen.SIGN_IN, Screen.CONNECTION, Screen.SERVERS, Screen.LOOK, Screen.ABOUT)) {
                    MenuRow(s.title, s.note) { screen = s }
                    if (s != Screen.ABOUT) Divider()
                }
            }
            Screen.SIGN_IN -> SignIn(core, accounts, cards, onAddAccount, notice)
            Screen.CONNECTION -> Connection(core, tunnel, allowLan)
            Screen.SERVERS -> Servers(core, serverCount, countryCount, pinPlan, pinRun,
                deadCount, asideCount, onImport, onDrop)
            Screen.LOOK -> Look(feel, onFeel)
            Screen.ABOUT -> About(core)
        }
    }
}

// -- sign-in ------------------------------------------------------------------

@Composable
private fun SignIn(
    core: RelayCore, accounts: List<Account>, cards: Map<String, ProviderCard>,
    onAddAccount: (String) -> Unit, notice: String,
) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf("") }
    val ready = accounts.count { it.signedIn }

    if (notice.isNotEmpty()) SaidLine(notice)

    Pane(icon = Glyph.key, title = "Accounts", pill = if (ready > 0) "$ready ready" else null) {
        if (accounts.isEmpty()) {
            Text("No accounts yet. Add one to connect.", style = Type.body)
        }
        for (a in accounts) AccountRow(a,
            onUse = { scope.launch { said = core.useAccount(a.id) } },
            onRemove = { scope.launch { said = core.removeAccount(a.id) } })
        ButtonRow { Btn("Add an account", { onAddAccount(Accounts.SURFSHARK) }) }
    }

    Pane(icon = Glyph.globe, title = "Servers from each provider") {
        for (p in listOf(Accounts.SURFSHARK, Accounts.WINDSCRIBE)) {
            val card = cards[p] ?: ProviderCard()
            ProviderStep(p, card, busy = busy == p) { action ->
                busy = p
                said = null
                scope.launch {
                    said = when (action) {
                        "sign-in" -> { onAddAccount(p); null }
                        "servers" -> if (p == Accounts.SURFSHARK) core.surfsharkServers()
                                     else core.windscribeServers(freeOnly = false)
                        "pin" -> core.pinFor(p).let { if (it.ok) Said(true,
                            "Ready to pin - Settings → Servers.") else it }
                        "refresh" -> core.windscribeRefresh()
                        else -> null
                    }
                    busy = ""
                }
            }
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }
}

@Composable
private fun AccountRow(a: Account, onUse: () -> Unit, onRemove: () -> Unit) {
    val lit = a.active && a.signedIn
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (lit) Tone.ok.copy(alpha = 0.05f) else Tone.bg.copy(alpha = 0.4f))
            .border(1.dp, if (lit) Tone.ok.copy(alpha = 0.6f) else Tone.divider, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.providerName.uppercase(),
            style = Type.eyebrow.copy(color = if (a.provider == Accounts.WINDSCRIBE) Tone.ok else Tone.textMuted),
            modifier = Modifier.clip(CircleShape).border(1.dp, Tone.border, CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label.ifEmpty { a.username }, style = Type.strong.copy(fontSize = 14.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (a.active) "in use" else null,
                if (a.signedIn) "signed in" else "not signed in").joinToString(" · "),
                style = Type.small, maxLines = 1)
        }
        if (!a.active) Btn("Use", onUse)
        Spacer(Modifier.width(6.dp))
        Btn("Remove", onRemove)
    }
}

/**
 * One provider's next step, in the order the desktop asks: sign in, get the
 * servers, pin what is waiting, and then keep them up to date.
 */
@Composable
private fun ProviderStep(provider: String, card: ProviderCard, busy: Boolean, act: (String) -> Unit) {
    val name = providerName(provider)
    val (step, label) = when {
        !card.account -> "sign-in" to "Sign in"
        card.waiting > 0 -> "pin" to "Pin ${card.waiting} waiting"
        card.servers == 0 -> "servers" to "Get servers"
        else -> "servers" to "Update servers"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderTag(provider, tested = card.usable, ok = card.usable)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.strong.copy(fontSize = 14.sp))
            Text(when {
                card.usable -> "${card.servers} ready to connect"
                card.servers > 0 -> "${card.servers} servers · no account"
                card.account -> "signed in · no servers yet"
                else -> "not set up"
            }, style = Type.small)
        }
        Btn(if (busy) "Working…" else label, { act(step) }, enabled = !busy)
    }
    if (provider == Accounts.WINDSCRIBE && card.account) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            InfoTip("Windscribe's proxy credential expires now and then. When its exits start " +
                "refusing the account, this fetches a fresh one with the session already held - " +
                "no puzzle.")
            Btn("Refresh credential", { act("refresh") }, enabled = !busy)
        }
    }
}

// -- connection -----------------------------------------------------------------

@Composable
private fun Connection(core: RelayCore, tunnel: TunnelPlan, allowLan: Boolean) {
    val scope = rememberCoroutineScope()
    var lanSaid by remember { mutableStateOf("") }

    Pane(icon = Glyph.route, title = null) {
        ToggleRow("Reach the local network",
            "The printer, the router's own page, a NAS and adb over wifi stay reachable while " +
                "the tunnel is up. A local address goes nowhere beyond the house, so nothing " +
                "leaves by being spared.",
            allowLan) { on ->
            val up = core.setAllowLan(on)
            lanSaid = if (up) "Takes effect on the next connect." else ""
        }
        SaidLine(lanSaid)
    }

    var domain by remember(tunnel.domain) { mutableStateOf(tunnel.domain) }
    var password by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }

    Pane(icon = Glyph.tower, title = "Your own tunnel",
        help = "A server of your own behind Cloudflare. The phone speaks the tunnel itself - " +
            "there is no client to install here. The tunnel password opens it; the API " +
            "password is for the server's own settings.",
        pill = when {
            busy -> "asking"
            tunnel.ready -> "ready"
            else -> "not set"
        }, pillGood = tunnel.ready) {
        Field(domain, { domain = it }, "yourdomain.com", mono = true)
        Spacer(Modifier.height(8.dp))
        Field(password, { password = it },
            if (tunnel.hasPassword) "Tunnel password (saved)" else "Tunnel password", secret = true)
        Spacer(Modifier.height(8.dp))
        Field(api, { api = it },
            if (tunnel.hasApiPassword) "API password (saved)" else "API password", secret = true)
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
        ButtonRow {
            Btn("Find another way in", {
                busy = true
                said = null
                scope.launch { said = core.rescanEdges(); busy = false }
            }, enabled = !busy && tunnel.domain.isNotEmpty(), icon = Glyph.refresh)
        }
        if (tunnel.edges.isNotEmpty()) {
            Text("Way in: ${tunnel.edges[0]}" + if (tunnel.edges.size > 1) "  (+${tunnel.edges.size - 1})" else "",
                style = Type.mono, modifier = Modifier.padding(top = 10.dp))
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Setting up the server", style = Type.strong.copy(fontSize = 14.sp))
            InfoTip("One line to paste into an SSH session on the server: it carries the " +
                "installer inside itself and runs it. The provider account stays as " +
                "placeholders, so it never lands in a shell history.")
        }
        ButtonRow {
            Btn("Copy the install command", {
                val cmd = core.installCommand(domain)
                said = if (cmd == null) Said(false, "The installer is missing from this build.")
                else { core.copy(cmd); Said(true, "Copied. Paste it on the server.") }
            }, icon = Glyph.copy)
        }
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

    Pane(icon = Glyph.folder, title = "Server folder", pill = "$serverCount · $countryCount countries",
        pillGood = serverCount > 0,
        help = "Configs pinned to real addresses live here, in the app's own folder. Import a " +
            "folder of .ovpn files from the phone - they are copied in - or get them from a " +
            "provider under Sign-in.") {
        Text(core.dataFolder, style = Type.mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ButtonRow { Btn("Import a folder…", onImport, icon = Glyph.folder) }
    }

    Pane(icon = Glyph.pin, title = "Pin to real addresses",
        pill = when {
            run.running -> "pinning"
            plan.total > 0 -> "${plan.total} waiting"
            else -> null
        }, pillGood = run.running || plan.total > 0,
        help = "A downloaded config names a host, and on this line the resolver answers for " +
            "that host with a forged address. Pinning resolves each one over DNS-over-HTTPS " +
            "and writes the real address in. Direct asks from here; Through the tunnel asks " +
            "from wherever the tunnel comes out.") {
        Segment(listOf("direct", "proxy"), plan.route.ifEmpty { "direct" },
            { if (it == "direct") "Direct" else "Through the tunnel" },
            { r -> scope.launch { core.setPinRoute(route = r) } }, enabled = !run.running)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Addresses per host", style = Type.body.copy(color = Tone.text))
            InfoTip("A host can answer at several addresses. Each one becomes its own config, " +
                "so more here means more exits to race and a longer pin.")
            Spacer(Modifier.weight(1f))
            IconButton(Glyph.minus, "Fewer", { scope.launch { core.setPinRoute(maxIps = plan.maxIps - 1) } },
                size = 34.dp, enabled = !run.running && plan.maxIps > 1)
            Text(plan.maxIps.toString(), style = Type.mono.copy(color = Tone.text, fontSize = 14.sp),
                modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(Glyph.plus, "More", { scope.launch { core.setPinRoute(maxIps = plan.maxIps + 1) } },
                size = 34.dp, enabled = !run.running && plan.maxIps < 32)
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Check each address answers",
            "Before writing a config, ask its address whether it takes a connection, and " +
                "leave out the ones that do not. Slower, and the folder only holds exits that " +
                "were alive when pinned.", plan.test) { on ->
            scope.launch { core.setPinRoute(test = on) }
        }
        for (b in plan.blockers) SaidLine(b.say, bad = true)
        if (plan.total > 0 && !run.running) {
            Text("${plan.total} to pin from ${plan.folder.substringAfterLast('/')}" +
                if (plan.minutes > 0) ", about ${plan.minutes} min" else "", style = Type.small,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (run.running) {
            Spacer(Modifier.height(10.dp))
            Progress(run.done, run.total)
            Text("${run.done} of ${run.total} · ${run.reachable} answering · ${run.unreachable} not" +
                if (run.forged > 0) " · ${run.forged} forged answers seen" else "",
                style = Type.mono, modifier = Modifier.padding(top = 6.dp))
            if (run.last.isNotEmpty()) Text(run.last, style = Type.mono.copy(color = Tone.textMuted),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    Pane(icon = Glyph.archive, title = "Exits that stopped answering",
        pill = if (deadCount > 0) "$deadCount" else null, pillGood = false,
        help = "Exits that refused or timed out the last time the bolt on a country tested " +
            "them. Setting them aside keeps the race off them; they can be put back.") {
        Text(when {
            deadCount > 0 -> "$deadCount to set aside."
            asideCount > 0 -> "$asideCount set aside earlier."
            else -> "None."
        }, style = Type.small)
        if (deadCount > 0 || asideCount > 0) ButtonRow { Btn("Review them", onDrop) }
    }

    Pane(icon = Glyph.gauge, title = "Timing servers",
        help = "The desktop times a server by raising a real OpenVPN tunnel through it. A " +
            "phone cannot: there is no OpenVPN here, and only one VPN may be up at a time - " +
            "this app is that one. What the phone measures instead is how quickly each " +
            "exit's proxy answers, which is the bolt on each row of the country list.") {
        Text("The bolt on a country times its exits.", style = Type.small)
    }
}

@Composable
private fun Progress(done: Int, total: Int) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Tone.control)) {
        Box(Modifier.fillMaxWidth(if (total > 0) (done.toFloat() / total).coerceIn(0.02f, 1f) else 0.02f)
            .height(4.dp).background(Tone.ok))
    }
}

// -- look and feel ----------------------------------------------------------------

@Composable
private fun Look(feel: com.erelay.relay.data.Feel, onFeel: (com.erelay.relay.data.Feel) -> Unit) {
    val chosen = com.erelay.relay.ui.theme.Palette.of(feel.theme)
    Pane(icon = Glyph.layers, title = "Theme",
        help = "The page and every surface on it. Text and the status colours stay the same " +
            "in all of them, so a status reads the same however dark the page. Black " +
            "switches an OLED screen's pixels off, which saves battery on a phone that keeps " +
            "a tunnel up all evening.") {
        for (row in com.erelay.relay.ui.theme.Palette.entries.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in row) Swatch(p, p == chosen, Modifier.weight(1f)) {
                    onFeel(feel.copy(theme = p.key))
                }
            }
        }
    }

    Pane(icon = Glyph.sparkle, title = "Orb colours",
        help = "Traffic light: red when not connected, yellow while connecting, green when " +
            "connected - the state reads at a glance from across a room. Desktop: the " +
            "desktop app's own colours, lavender at rest.") {
        Segment(listOf("signal", "desktop"), feel.orbColors,
            { if (it == "signal") "Traffic light" else "Desktop" },
            { onFeel(feel.copy(orbColors = it)) })
    }

    Pane(icon = Glyph.activity, title = "Touch") {
        ToggleRow("Haptic feedback",
            "A light tick under each press, and a buzz when the tunnel comes up or fails.",
            feel.haptics) { onFeel(feel.copy(haptics = it)) }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Tap the orb",
            "The sphere works like the Connect button: tap to connect, tap again to " +
                "disconnect. Hold it while connected to reconnect through a fresh exit. Only " +
                "the sphere itself, not the space around it.",
            feel.orbTap) { onFeel(feel.copy(orbTap = it)) }
    }

    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var tileSaid by remember { mutableStateOf("") }
        Pane(icon = Glyph.bolt, title = "Quick settings",
            help = "Relay as a tile in the panel you pull down from the top of the screen: " +
                "one press connects to your last pick, another disconnects. Long-press the " +
                "tile to open the app.") {
            ButtonRow {
                Btn("Add the Relay tile", {
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
                }, icon = Glyph.plus)
            }
            SaidLine(tileSaid)
        }
    }

    Pane(icon = Glyph.gauge, title = "Motion") {
        ToggleRow("Animate the orb",
            "Off draws it once and leaves it still - less for the GPU to do. It still " +
                "changes colour with the connection.",
            feel.orbMotion) { onFeel(feel.copy(orbMotion = it)) }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Show the traffic meter",
            "The live wave of what is going up and down while connected.",
            feel.meter) { onFeel(feel.copy(meter = it)) }
    }
}

/** One theme as a little picture of itself: its page, a card on it, its light. */
@Composable
private fun Swatch(
    p: com.erelay.relay.ui.theme.Palette, on: Boolean, modifier: Modifier, onPick: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .press(shape = RoundedCornerShape(12.dp), sink = true,
                    feel = HapticFeedbackType.SegmentTick, onClick = onPick)
                .clip(RoundedCornerShape(12.dp))
                .background(p.bg)
                .background(Brush.radialGradient(
                    listOf(p.glow.copy(alpha = (p.glow.alpha * 3f).coerceAtMost(1f)), Color.Transparent),
                    center = Offset(80f, 0f), radius = 160f))
                .border(if (on) 2.dp else 1.dp, if (on) Tone.ok else Tone.border, RoundedCornerShape(12.dp))
                .padding(8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(p.surface)
                .border(1.dp, Tone.divider, RoundedCornerShape(5.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text(p.label, style = Type.small.copy(color = if (on) Tone.text else Tone.textMuted,
            fontWeight = if (on) FontWeight.W600 else FontWeight.W400), maxLines = 1)
    }
}

// -- about -----------------------------------------------------------------------

@Composable
private fun About(core: RelayCore) {
    Pane(icon = Glyph.info, title = "This copy",
        help = "Accounts are kept with a key the Android keystore holds; configs, pins and " +
            "test results are plain files in the data folder.") {
        Line("App", "Relay for Android ${core.appVersion}")
        Line("Core", core.version)
        Line("Data", core.dataFolder)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = Type.small, modifier = Modifier.width(56.dp))
        Text(value, style = Type.mono.copy(color = Tone.textMuted, fontWeight = FontWeight.Normal))
    }
}
