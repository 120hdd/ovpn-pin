package com.erelay.relay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.erelay.relay.data.DeadExits
import com.erelay.relay.data.Said
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The exits that stopped answering, and which folders to take them out of.
 * Nothing is ticked to start: this moves somebody's files about, so it waits
 * to be told which. Whatever is set aside can be put back.
 */
@Composable
fun DropSheet(
    open: Boolean,
    dead: DeadExits,
    drop: suspend (List<String>) -> Said,
    restore: suspend (List<String>) -> Said,
    onClose: () -> Unit,
) {
    var ticked by remember { mutableStateOf(setOf<String>()) }
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) { if (!open) { ticked = emptySet(); said = null } }

    Sheet(open, "Set them aside", onClose) {
        Text(if (dead.dead > 0)
            "${dead.dead} exits refused or timed out the last time they were tested. Tick the " +
                "folders to take them out of; the race then stops asking them."
        else "Nothing is known to be dead right now.", style = Type.body,
            modifier = Modifier.padding(horizontal = Space.x1))
        Spacer(Modifier.height(Space.x4))

        if (dead.folders.isNotEmpty()) Group {
            dead.folders.forEachIndexed { i, f ->
                if (i > 0) Sep()
                val on = f.path in ticked
                CheckRow(f.label, null, on, { ticked = if (on) ticked - f.path else ticked + f.path }) {
                    Text("${f.count}", style = Type.mono.copy(color = Tone.fail), maxLines = 1, softWrap = false)
                }
            }
        }

        val preview = dead.exits.filter { it.folder in ticked }
        if (preview.isNotEmpty()) {
            Text("Would go", style = Type.subtitle, modifier = Modifier.padding(top = Space.x6, bottom = Space.x2))
            for (e in preview.take(60)) {
                Row(Modifier.fillMaxWidth().padding(vertical = Space.x1)) {
                    Flag(e.country, 20.dp, Modifier.padding(end = Space.x2))
                    Column(Modifier.weight(1f)) {
                        Text(e.file, style = Type.mono.copy(color = Tone.text2), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(listOf(e.ip, e.why).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (preview.size > 60) Text("and ${preview.size - 60} more", style = Type.caption)
        }

        // Each at its own width while both fit; sharing the line when they do
        // not, so the second is not squeezed to a sliver by the first.
        ButtonRow {
            Btn(if (busy) "Moving…" else "Set them aside", {
                busy = true
                scope.launch {
                    said = drop(ticked.toList())
                    ticked = emptySet()
                    busy = false
                }
            }, Modifier.weight(1f, fill = false), danger = true, enabled = !busy && ticked.isNotEmpty())
            Btn("Keep them", onClose, Modifier.weight(1f, fill = false), enabled = !busy)
        }

        if (dead.aside > 0) {
            Text("Set aside earlier", style = Type.subtitle, modifier = Modifier.padding(top = Space.x6, bottom = Space.x1))
            Text("${dead.aside} set aside earlier" +
                if (dead.shelved.isNotEmpty()) " · ${dead.shelved.joinToString(", ")}" else "",
                style = Type.caption)
            ButtonRow {
                Btn("Put them back", {
                    busy = true
                    scope.launch {
                        said = restore(emptyList())
                        busy = false
                    }
                }, enabled = !busy)
            }
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }
}
