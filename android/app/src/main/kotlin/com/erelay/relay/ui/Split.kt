package com.erelay.relay.ui

import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erelay.relay.data.InstalledApp
import com.erelay.relay.data.RelayCore
import com.erelay.relay.data.Said
import com.erelay.relay.data.SplitMode
import com.erelay.relay.data.SplitPlan
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import androidx.compose.foundation.lazy.itemsIndexed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → Split tunnel: what goes through Relay, and what goes straight out
 * over the line the phone already has.
 *
 * One choice and one list. The choice is which way the list works - left out
 * of the tunnel, or the only thing in it - and the list is the same list
 * either way, so turning one into the other keeps what was put on it. The
 * list is three kinds of thing: the Iranian preset (the core's domains and
 * address ranges, and its list of Iranian apps), apps picked one at a time,
 * and domains and ranges typed in.
 *
 * Nothing here touches a tunnel that is up. Which apps an interface admits is
 * fixed when Android establishes it, so a change waits for the next connect,
 * and says so while it waits - as the LAN switch does.
 */

private val MODE_NOTE = mapOf(
    SplitMode.OFF to "Everything goes through Relay",
    SplitMode.BYPASS to "The list goes straight out",
    SplitMode.ONLY to "Only the list goes through Relay",
)

private val MODE_SAID = mapOf(
    SplitMode.OFF to "Everything on the phone goes through Relay.",
    SplitMode.BYPASS to "Everything goes through Relay except what is on the list below, which goes " +
        "straight out over your own connection.",
    SplitMode.ONLY to "Only what is on the list below goes through Relay. Everything else goes " +
        "straight out over your own connection.",
)

private const val SPLIT_HELP = "Split tunnelling decides what the tunnel carries. Except these " +
    "sends the list straight out over your own line - Iranian banks, shops and government sites " +
    "that turn foreign addresses away, or are simply quicker from home. Only these is the other " +
    "way round: only the list goes through Relay. Apps are matched by app; sites by name or " +
    "address, whichever app opens them. A change applies the next time the tunnel comes up."

private const val LIST_HELP_OUT = "These skip the tunnel. An app on the list never enters it, and " +
    "a site or range on the list is sent straight out whichever app opens it - so an Iranian site " +
    "sees your own address, as it expects to."

private const val LIST_HELP_IN = "Only these use the tunnel. Everything an app on the list sends is " +
    "carried, and a site or range on the list is carried whichever app opens it. Everything else " +
    "goes straight out."

/*
 * Below Android 10 the platform cannot say which app a connection belongs to,
 * so the service cannot carry "these apps, and these sites from any app" on
 * one interface: it lets the apps in and the sites go. Said where the choice
 * is made, rather than discovered when a site is not carried.
 */
private const val OLD_ANDROID = "On Android 9 and older the list cannot hold apps and sites at " +
    "once. While any app is on it - one you picked, or an Iranian app the preset covers - only " +
    "those apps go through Relay, and the sites and ranges are not used."

private fun mixesOnOldAndroid(s: SplitPlan) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && s.hasDestinations && (s.apps.isNotEmpty() || s.iran)

@Composable
fun SplitScreen(core: RelayCore, onApps: () -> Unit) {
    val split by core.split.collectAsStateWithLifecycle()
    val iranApps by core.iranApps.collectAsStateWithLifecycle()
    val pending by core.splitPending.collectAsStateWithLifecycle()

    // The list's own title says which way it works, and keeps saying it
    // while it folds away after Off is chosen rather than changing its mind
    // on the way out.
    var lastOn by remember { mutableStateOf(SplitMode.BYPASS) }
    LaunchedEffect(split.mode) { if (split.mode != SplitMode.OFF) lastOn = split.mode }
    val listMode = if (split.mode == SplitMode.OFF) lastOn else split.mode

    Section("What goes through Relay", help = SPLIT_HELP) {
        // A field that opens into the three rather than three side by side:
        // "Except these" and "Only these" did not fit across a narrow phone
        // with a large font, which is what Dropdown is for.
        Dropdown(SplitMode.entries, split.mode, { it.label }, { core.setSplitMode(it) },
            caption = "Split tunnel", note = { MODE_NOTE.getValue(it) })
        Text(MODE_SAID.getValue(split.mode), style = Type.body,
            modifier = Modifier.padding(top = Space.x3, start = Space.x1, end = Space.x1))
        when {
            split.mode == SplitMode.ONLY && split.empty -> SaidLine(
                "Nothing is on the list yet, so everything still goes through Relay. Turn on " +
                    "Iranian sites and apps, pick some apps, or add a site below.", bad = true)
            split.mode == SplitMode.ONLY && mixesOnOldAndroid(split) -> SaidLine(OLD_ANDROID, bad = true)
            split.mode == SplitMode.BYPASS && split.empty ->
                SaidLine("Nothing is on the list yet, so nothing goes around Relay.")
        }
        if (pending) SaidLine("Applies when you reconnect.")
    }

    // Down from the section above, as a ColumnScope's would: this one is not
    // called in a column's scope, and the plain default grows sideways too.
    AnimatedVisibility(split.mode != SplitMode.OFF,
        enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column { SplitList(core, split, listMode, iranApps.size, onApps) }
    }
}

/** The list: the Iranian preset, the apps, and the sites and ranges typed in. */
@Composable
private fun SplitList(core: RelayCore, split: SplitPlan, mode: SplitMode, iranCount: Int, onApps: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }
    val out = mode == SplitMode.BYPASS

    Section(if (out) "Goes straight out" else "Goes through Relay",
        help = if (out) LIST_HELP_OUT else LIST_HELP_IN) {
        Group {
            SwitchRow("Iranian sites and apps",
                "Iranian domains and address ranges, plus " +
                    (if (iranCount > 0) "$iranCount known Iranian apps" else "the known Iranian apps"),
                split.iran, { core.setSplitIran(it) })
            Sep()
            NavRow("Apps", if (split.apps.isEmpty()) "None chosen yet" else "${split.apps.size} chosen", onApps)
        }
    }

    Section("Sites and addresses",
        note = "A domain takes every name under it: digikala.com is www.digikala.com too.") {
        Block {
            Field(typed, { typed = it; said = null }, "digikala.com or 5.6.0.0/16", mono = true,
                keyboard = KeyboardType.Uri)
            ButtonRow {
                Btn(if (busy) "Adding…" else "Add", {
                    busy = true
                    said = null
                    scope.launch {
                        val r = core.addSplitDomain(typed)
                        // Kept in the field when it was refused, so the typo is
                        // fixed where it was made.
                        if (r.ok) typed = ""
                        said = r
                        busy = false
                    }
                }, enabled = !busy && typed.isNotBlank(), icon = Glyph.plus)
            }
            said?.let { SaidLine(it.text, bad = !it.ok) }
        }
        if (split.domains.isNotEmpty()) {
            Spacer(Modifier.height(Space.x3))
            Group {
                split.domains.forEachIndexed { i, d ->
                    if (i > 0) Sep()
                    key(d) {
                        EntryRow(d) {
                            core.removeSplitDomain(d)
                            said = null
                        }
                    }
                }
            }
        }
    }
}

private val V4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** What an entry covers, in the words under it. */
private fun kindOf(entry: String) = when {
    '/' in entry -> "address range"
    V4.matches(entry) -> "address"
    else -> "and every name under it"
}

/** One typed entry, and the x that takes it off. */
@Composable
private fun EntryRow(entry: String, onRemove: () -> Unit) {
    androidx.compose.material3.ListItem(
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(entry, style = Type.ip, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(kindOf(entry), style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { IconButton(Glyph.x, "Remove $entry", onRemove, size = 40.dp, tint = Tone.text3) },
    )
}

// -- the app picker -------------------------------------------------------------

/**
 * The apps on the phone, to tick for the split tunnel. A sheet of its own over
 * Settings, because a list of a few hundred apps does not fit inside a pane,
 * and a search over it is the only way to find one on a small phone.
 *
 * The Iranian preset's apps are shown ticked and dimmed while it is on: they
 * are on the list already, and unticking one there would do nothing. Turning
 * the preset off hands them back.
 */
@Composable
fun SplitAppsSheet(open: Boolean, core: RelayCore, onClose: () -> Unit) {
    val split by core.split.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    // Read again on every opening - an app installed since is there - and
    // the last reading stays on screen meanwhile rather than a blank.
    LaunchedEffect(open) {
        if (open) apps = core.installedApps() else query = ""
    }

    Sheet(open, "Apps", onClose, scroll = false) {
        val q = query.trim().lowercase()
        val all = apps
        val preset = split.iran
        // Laid out once - on opening, on a new search - and not again as boxes
        // are ticked, so a row never moves out from under a finger. Ticked
        // apps first, then the ones the preset covers, then the rest, each by
        // name as the core sorted them.
        val order = remember(all, q) {
            all.orEmpty()
                .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                .sortedBy {
                    when {
                        it.pkg in split.apps -> 0
                        preset && it.iranian -> 1
                        else -> 2
                    }
                }
        }
        val haptics = rememberHaptics()

        Column(Modifier.padding(horizontal = Space.x4)) {
            Search(query, "Search apps") { query = it }
            Text(appsSaid(split), style = Type.caption, modifier = Modifier.padding(top = Space.x3, start = Space.x1, end = Space.x1))
        }
        when {
            all == null -> Text("Reading the apps on this phone…", style = Type.body,
                modifier = Modifier.fillMaxWidth().padding(Space.x6))
            order.isEmpty() -> Text(if (q.isEmpty()) "No apps to list." else "No app matches “$query”.",
                style = Type.body, modifier = Modifier.fillMaxWidth().padding(Space.x6))
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = Space.x2)) {
                // One Slate card down the list, cut across its rows.
                itemsIndexed(order, key = { _, a -> a.pkg }, contentType = { _, _ -> "app" }) { i, a ->
                    val ticked = a.pkg in split.apps
                    val covered = preset && a.iranian
                    Column(Modifier.padding(horizontal = Space.x4).slateSeg(segOf(i, order.size))) {
                        if (i > 0) Sep()
                        AppRow(a, on = ticked || covered, covered = covered) {
                            haptics.toggle(!ticked)
                            core.setSplitApp(a.pkg, !ticked)
                        }
                    }
                }
                item { Spacer(Modifier.height(Space.x6)) }
            }
        }
    }
}

/** What a tick means in the mode that is set, and what the preset has ticked already. */
private fun appsSaid(s: SplitPlan): String {
    val tick = if (s.mode == SplitMode.ONLY) "Ticked apps go through Relay."
        else "Ticked apps go straight out, not through Relay."
    return if (s.iran) "$tick Iranian apps are ticked while Iranian sites and apps is on." else tick
}

/**
 * One app: its icon, its name and package, IR when the core knows it as an
 * Iranian app, and the box. The whole row is the target, so a thumb on a
 * small phone does not have to find the box.
 */
@Composable
private fun AppRow(a: InstalledApp, on: Boolean, covered: Boolean, onToggle: () -> Unit) {
    androidx.compose.material3.ListItem(
        modifier = Modifier
            // The caller answers the touch, with the switch's own tick.
            .toggleable(on, enabled = !covered, role = Role.Checkbox) { onToggle() }
            .semantics {
                stateDescription = when {
                    covered -> "On the list through the Iranian preset"
                    on -> "On the list"
                    else -> "Not on the list"
                }
            }
            .padding(horizontal = Space.x1),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { AppIcon(a.pkg, a.label, 40.dp, Modifier.alpha(if (covered) 0.6f else 1f)) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Two lines before it is cut: a long name with a large font
                // still reads, and the mark beside it keeps its place.
                Text(a.label, style = Type.strong.copy(color = if (covered) Tone.text2 else Tone.text).script(a.label),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.iranian) IrMark()
            }
        },
        supportingContent = {
            Text(a.pkg, style = Type.mono.copy(color = Tone.text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            androidx.compose.material3.Checkbox(on, null, enabled = !covered, colors = checkColors())
        },
    )
}

/** IR, as an outlined badge beside the name: the core knows it as an Iranian app. */
@Composable
private fun IrMark() {
    CountBadge("IR", Modifier.padding(start = Space.x2))
}

/**
 * App icons, drawn once at the size they are shown and kept.
 *
 * Off the main thread: an icon is a binder call and often an adaptive drawable
 * to rasterise, and a list of a few hundred apps flung past would otherwise
 * stutter on every row that comes into view. Kept by size in bytes rather than
 * by count, so a phone with large icons does not keep more memory than one
 * with small ones.
 */
private object AppIcons {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(pm: PackageManager, pkg: String, px: Int): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        val got = try {
            pm.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
        } catch (e: Exception) {
            return null
        }
        cache.put(pkg, got)
        return got
    }
}

/** An app's icon, or its initial on a tile until the icon is in - or if it never is. */
@Composable
private fun AppIcon(pkg: String, label: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(AppIcons.peek(pkg), pkg, px) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIcons.load(context.packageManager, pkg, px) }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val got = icon
        if (got != null) {
            // The icon brings its own shape - the launcher's mask, for an
            // adaptive one - so nothing is drawn behind it.
            Image(got, null, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.3f)).background(Tone.surface2),
                contentAlignment = Alignment.Center) {
                Text(label.take(1).uppercase(), style = Type.strong.copy(color = Tone.text2).script(label))
            }
        }
    }
}
