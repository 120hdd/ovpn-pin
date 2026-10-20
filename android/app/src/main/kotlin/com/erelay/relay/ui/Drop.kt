package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.erelay.relay.data.DeadExits
import com.erelay.relay.data.Said
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

    Sheet(open, "Exits that stopped answering", "Set them aside", onClose) {
        Text(if (dead.dead > 0)
            "${dead.dead} exits refused or timed out the last time they were tested. Tick the " +
                "folders to take them out of; the race then stops asking them."
        else "Nothing is known to be dead right now.", style = Type.small)
        Spacer(Modifier.height(12.dp))

        for (f in dead.folders) {
            val on = f.path in ticked
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) Tone.fail.copy(alpha = 0.08f) else Tone.surface)
                    .border(1.dp, if (on) Tone.fail.copy(alpha = 0.5f) else Tone.divider, RoundedCornerShape(12.dp))
                    .tap { ticked = if (on) ticked - f.path else ticked + f.path }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Toggle(on, { ticked = if (on) ticked - f.path else ticked + f.path })
                Spacer(Modifier.width(12.dp))
                Text(f.label, style = Type.strong, modifier = Modifier.weight(1f))
                Text("${f.count}", style = Type.mono.copy(color = Tone.fail))
            }
        }

        val preview = dead.exits.filter { it.folder in ticked }
        if (preview.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("WOULD GO", style = Type.eyebrow)
            for (e in preview.take(60)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(flagOf(e.country), modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.file, style = Type.mono.copy(color = Tone.textMuted), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(listOf(e.ip, e.why).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = Type.mono, maxLines = 1)
                    }
                }
            }
            if (preview.size > 60) Text("and ${preview.size - 60} more", style = Type.small)
        }

        ButtonRow {
            Btn(if (busy) "Moving…" else "Set them aside", {
                busy = true
                scope.launch {
                    said = drop(ticked.toList())
                    ticked = emptySet()
                    busy = false
                }
            }, danger = true, enabled = !busy && ticked.isNotEmpty())
            Btn("Keep them", onClose, enabled = !busy)
        }

        if (dead.aside > 0) {
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("${dead.aside} set aside earlier" +
                if (dead.shelved.isNotEmpty()) " · ${dead.shelved.joinToString(", ")}" else "",
                style = Type.small)
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
