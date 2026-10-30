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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private enum class Screen(val title: String, val note: String) {
    MENU("Settings", ""),
    ACCOUNTS("Accounts", "your providers, sign-ins and servers"),
    CONNECTION("Connection", "your own tunnel, and what it leaves alone"),
    SERVERS("Servers", "finding them, pinning them, timing them"),
    LOOK("Look and feel", "theme, haptics, the orb"),
    ABOUT("About", "version, where things are kept"),
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
        open, "Relay", screen.title, onClose,
        onBack = if (screen == Screen.MENU) null else ({ screen = Screen.MENU }),
    ) {
        when (screen) {
            Screen.MENU -> {
                Spacer(Modifier.height(4.dp))
                for (s in listOf(Screen.ACCOUNTS, Screen.CONNECTION, Screen.SERVERS, Screen.LOOK, Screen.ABOUT)) {
                    MenuRow(s.title, s.note) { screen = s }
                    if (s != Screen.ABOUT) Divider()
                }
            }
            Screen.ACCOUNTS -> AccountsScreen(core, accounts, cards, pinRun, onAddAccount, notice)
            Screen.CONNECTION -> Connection(core, tunnel, allowLan)
            Screen.SERVERS -> Servers(core, serverCount, countryCount, pinPlan, pinRun,
                deadCount, asideCount, onImport, onDrop)
            Screen.LOOK -> Look(feel, onFeel)
            Screen.ABOUT -> About(core)
        }
    }
}

// -- accounts -----------------------------------------------------------------

/**
 * Accounts: one card per provider rather than a list of accounts in one pane
 * and the providers' steps in another. Everything about Surfshark - the
 * accounts it has, how far along it is, and the one thing to do next - is in
 * Surfshark's card, so there is nothing to match up between two lists.
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

    AccountsSummary(ready, providers.size, accounts.size)
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
                    // start it, and the progress shows under the cards.
                    "pin" -> core.pinFor(p).let { if (it.ok) core.startPin().takeIf { s -> !s.ok } else it }
                    "refresh" -> core.windscribeRefresh()
                    else -> null
                }
                busy = ""
            }
        }
    }

    if (run.running || run.finished.isNotEmpty()) Pane { PinRunView(run) { core.cancelPin() } }
    said?.let { SaidLine(it.text, bad = !it.ok) }
}

/** The line across the top: how many providers can connect, as a number and a bar. */
@Composable
private fun AccountsSummary(ready: Int, of: Int, accounts: Int) {
    val shape = RoundedCornerShape(Shape.card)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp)
            .clip(shape)
            .background(Tone.surface.copy(alpha = 0.72f))
            .background(Brush.linearGradient(listOf(Tone.ok.copy(alpha = 0.13f), Tone.accent.copy(alpha = 0.07f))))
            .border(1.dp, Tone.ok.copy(alpha = 0.2f), shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$ready", style = Type.title.copy(fontSize = 34.sp, lineHeight = 36.sp,
                color = if (ready > 0) Tone.ok else Tone.text))
            Text(" of $of ready", style = Type.strong.copy(color = Tone.textMuted),
                modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Pill(if (accounts == 1) "1 account" else "$accounts accounts", good = accounts > 0)
        }
        Text(when {
            ready == of -> "Every provider can connect."
            accounts == 0 -> "Add an account to connect."
            ready > 0 -> "Enough to connect. The other provider is optional."
            else -> "Almost there: get the servers and pin them."
        }, style = Type.small, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(of) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                    .background(if (i < ready) Tone.ok else Tone.control))
            }
        }
    }
}

/**
 * One provider: its mark and how it stands, the three steps to being able to
 * connect through it, its accounts, and the one button that is next.
 */
@Composable
private fun ProviderAccounts(
    provider: String, card: ProviderCard, accounts: List<Account>, busy: Boolean,
    onAdd: () -> Unit, onUse: (Account) -> Unit, onRemove: (Account) -> Unit, act: (String) -> Unit,
) {
    val name = providerName(provider)
    val brand = if (provider == Accounts.WINDSCRIBE) Tone.windscribe else Tone.surfshark
    val shape = RoundedCornerShape(Shape.card)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(Tone.surface.copy(alpha = 0.72f))
            .border(1.dp, if (card.usable) Tone.ok.copy(alpha = 0.35f) else Tone.divider, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(brand.copy(alpha = 0.14f))
                    .border(1.dp, brand.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (provider == Accounts.WINDSCRIBE) Glyph.windscribe else Glyph.surfshark, null,
                    Modifier.size(22.dp), tint = brand)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.strong.copy(fontSize = 17.sp))
                Text(when {
                    card.usable && card.waiting > 0 -> "${card.servers} ready · ${card.waiting} to pin"
                    card.usable -> "${card.servers} servers ready to connect"
                    card.servers > 0 -> "${card.servers} servers · add an account to use them"
                    card.account && card.waiting > 0 -> "${card.waiting} servers waiting to be pinned"
                    card.account -> "Signed in · no servers yet"
                    else -> "Not set up"
                }, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Windscribe only: its proxy credential goes stale now and then, and
            // this fetches another with the session already held - no puzzle.
            if (provider == Accounts.WINDSCRIBE && card.account) {
                IconButton(Glyph.refresh, "Refresh the Windscribe credential", { act("refresh") },
                    size = 40.dp, enabled = !busy)
            }
            Pill(if (card.usable) "Ready" else "Set up", good = card.usable, dot = card.usable)
        }

        Spacer(Modifier.height(16.dp))
        Steps(card)

        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tone.bg.copy(alpha = 0.45f))
                .border(1.dp, Tone.divider, RoundedCornerShape(14.dp))) {
                accounts.forEachIndexed { i, a ->
                    if (i > 0) Divider()
                    AccountLine(a, brand, onUse = { onUse(a) }, onRemove = { onRemove(a) })
                }
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

/** Account, servers, pinned: dots joined by a line that fills as each is done. */
@Composable
private fun Steps(card: ProviderCard) {
    val steps = listOf(
        "Account" to card.account,
        "Servers" to (card.servers > 0 || card.waiting > 0),
        "Pinned" to (card.servers > 0),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (word, done) ->
            if (i > 0) Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp).clip(CircleShape)
                .background(if (done) Tone.ok.copy(alpha = 0.55f) else Tone.divider))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (done) Tone.ok else Color.Transparent)
                        .border(1.5.dp, if (done) Tone.ok else Tone.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Glyph.check, null, Modifier.size(12.dp), tint = Tone.ink)
                    else Text("${i + 1}", style = Type.eyebrow.copy(color = Tone.textOff))
                }
                Spacer(Modifier.width(6.dp))
                Text(word, style = Type.small.copy(color = if (done) Tone.text else Tone.textOff))
            }
        }
    }
}

/**
 * One account: an initial, its name and state, and Use. Remove asks once
 * more in place, because it cannot be undone and the button is a thumb's
 * width from Use.
 */
@Composable
private fun AccountLine(a: Account, brand: Color, onUse: () -> Unit, onRemove: () -> Unit) {
    var sure by remember(a.id) { mutableStateOf(false) }
    val title = a.label.ifEmpty { a.username }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(if (a.active) brand.copy(alpha = 0.2f) else Tone.control),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1).uppercase().ifEmpty { "?" },
                style = Type.strong.copy(fontSize = 13.sp, color = if (a.active) brand else Tone.textMuted))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (a.signedIn) Tone.ok else Tone.work))
                Spacer(Modifier.width(6.dp))
                Text(listOfNotNull(if (a.active) "In use" else null,
                    if (a.signedIn) "signed in" else "not signed in").joinToString(" · "),
                    style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            sure -> {
                Btn("Remove", onRemove, danger = true)
                IconButton(Glyph.x, "Keep it", { sure = false }, size = 40.dp)
            }
            else -> {
                if (!a.active) Btn("Use", onUse)
                IconButton(Glyph.x, "Remove ${title}", { sure = true }, size = 40.dp, tint = Tone.textOff)
            }
        }
    }
}

/** A pin run as it goes: a bar, the counts, the last address, and Stop. */
@Composable
private fun PinRunView(run: PinRun, onStop: () -> Unit) {
    if (run.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pinning", style = Type.strong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            Btn("Stop", onStop, danger = true)
        }
        Spacer(Modifier.height(8.dp))
        Progress(run.done, run.total)
        Text("${run.done} of ${run.total} · ${run.reachable} answering · ${run.unreachable} not" +
            if (run.forged > 0) " · ${run.forged} forged answers seen" else "",
            style = Type.mono, modifier = Modifier.padding(top = 6.dp))
        if (run.last.isNotEmpty()) Text(run.last, style = Type.mono.copy(color = Tone.textMuted),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        SaidLine(run.finished, modifier = Modifier.padding(bottom = 2.dp))
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
            "provider under Accounts.") {
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
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Addresses per host", style = Type.body.copy(color = Tone.text),
                    modifier = Modifier.weight(1f, fill = false))
                InfoTip("A host can answer at several addresses. Each one becomes its own config, " +
                    "so more here means more exits to race and a longer pin.")
            }
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

    Pane(icon = Glyph.gauge, title = "Timing servers",
        pill = when {
            running -> "timing"
            ago == null -> "never"
            ago < 60 -> "${ago}m ago"
            ago < 60 * 24 -> "${ago / 60}h ago"
            else -> "${ago / (60 * 24)}d ago"
        }, pillGood = running || (ago != null && ago < 6 * 60),
        help = "One exit from each country is asked how quickly its proxy answers - six at " +
            "a time, a few seconds each, so a minute or so for the whole list and gentle on " +
            "the line. Only with the tunnel down: through it, every time would be the " +
            "tunnel's. The bolt on a country in the exit list times all of its exits.") {
        Text(when {
            running -> "Timing ${reach.done} of ${reach.total} countries…"
            reach.code == com.erelay.relay.data.QUICK && reach.said.isNotEmpty() -> reach.said
            ago == null -> "No country has been timed yet, so the Ping order is a guess."
            else -> "The exit list is ordered by these times."
        }, style = Type.small)
        if (running) {
            Spacer(Modifier.height(8.dp))
            Progress(reach.done, reach.total)
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("Time the list on its own",
            "When the exit list opens and the last timing is over six hours old, it runs " +
                "again in the background. Never while the tunnel is up.", auto) { core.setAutoQuick(it) }
        ButtonRow {
            if (running) Btn("Stop", { core.cancelReach() }, danger = true, modifier = Modifier.weight(1f))
            else Btn("Time every country", { said = core.quickTest().takeIf { !it.ok } },
                strong = true, enabled = !reach.running, icon = Glyph.bolt, modifier = Modifier.weight(1f))
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
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
                // A short last row keeps its swatches the size of the rest.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    Pane(icon = Glyph.sparkle, title = "Orb colours",
        help = "Traffic light: silver when not connected, yellow while connecting, green when " +
            "connected, red if it failed - the state reads at a glance from across a room. Desktop: the " +
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
            fontWeight = if (on) FontWeight.W600 else FontWeight.W400), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

// -- about -----------------------------------------------------------------------

@Composable
private fun About(core: RelayCore) {
    Pane(icon = Glyph.info, title = "Relay for Android",
        help = "Accounts are kept with a key the Android keystore holds; configs, pins and " +
            "test results are plain files in the data folder.") {
        Line("Version", core.appVersion)
        Line("Core", core.version)
        Line("Data", core.dataFolder)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = Type.small, modifier = Modifier.widthIn(min = 56.dp).padding(end = 8.dp))
        Text(value, style = Type.mono.copy(color = Tone.textMuted, fontWeight = FontWeight.Normal))
    }
}
