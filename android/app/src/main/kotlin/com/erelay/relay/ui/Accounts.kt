package com.erelay.relay.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Captcha
import com.erelay.relay.data.Said
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Who(val key: String, val label: String) {
    SURFSHARK("surfshark", "Surfshark"), WINDSCRIBE("windscribe", "Windscribe")
}

/**
 * Adding an account. Surfshark is a service username and password, checked
 * by the exits themselves, so it is two writes. Windscribe is a login behind
 * a puzzle: this fetches the puzzle and [Captcha] finishes it.
 */
@Composable
fun AddAccount(
    open: Boolean,
    provider: String,
    onClose: () -> Unit,
    addSurfshark: suspend (String, String, String) -> Said,
    beginWindscribe: suspend (String, String, String) -> Pair<Said, Pair<String, Captcha>?>,
    finishWindscribe: suspend (String, String, List<Int>, List<Int>, String) -> Said,
    onDone: (String) -> Unit,
) {
    var who by remember { mutableStateOf(Who.SURFSHARK) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFa by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }
    var puzzle by remember { mutableStateOf<Pair<String, Captcha>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(open, provider) {
        if (open) who = if (provider == Who.WINDSCRIBE.key) Who.WINDSCRIBE else Who.SURFSHARK
    }
    LaunchedEffect(open) {
        if (!open) {
            user = ""; password = ""; twoFa = ""; label = ""; said = null; busy = false; puzzle = null
        }
    }

    Sheet(open, "Sign-in", "Add an account", onClose) {
        Spacer(Modifier.height(6.dp))
        Segment(Who.entries, who, { it.label }, { who = it; said = null }, enabled = !busy)
        Spacer(Modifier.height(14.dp))
        Text(if (who == Who.SURFSHARK)
            "The service username and password from Surfshark's manual-setup page - not the " +
                "email you log in with."
        else "Your Windscribe username and password. Windscribe will ask you to solve a puzzle; " +
            "it is tried once, never again behind your back.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        Field(user, { user = it }, if (who == Who.SURFSHARK) "Service username" else "Username")
        Spacer(Modifier.height(8.dp))
        Field(password, { password = it }, "Password", secret = true)
        if (who == Who.WINDSCRIBE) {
            Spacer(Modifier.height(8.dp))
            Field(twoFa, { twoFa = it }, "2FA code, if it is on", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        }
        Spacer(Modifier.height(8.dp))
        Field(label, { label = it }, "A name for it (optional)")
        ButtonRow {
            Btn(if (busy) "Working…" else if (who == Who.SURFSHARK) "Save" else "Sign in", {
                busy = true
                said = null
                scope.launch {
                    if (who == Who.SURFSHARK) {
                        val r = addSurfshark(label, user, password)
                        busy = false
                        if (r.ok) onDone("Surfshark account saved. Now get its servers.") else said = r
                    } else {
                        val (r, got) = beginWindscribe(label, user, password)
                        if (!r.ok || got == null) {
                            busy = false
                            said = r
                        } else {
                            puzzle = got
                        }
                    }
                }
            }, strong = true, enabled = !busy, modifier = Modifier.weight(1f))
            Btn("Cancel", onClose, enabled = !busy)
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }

    CaptchaSheet(
        puzzle = puzzle,
        onClose = {
            puzzle = null
            busy = false
            said = Said(false, "Sign-in stopped. Nothing was sent.")
        },
        submit = { solution, xs, ys ->
            val token = puzzle?.first.orEmpty()
            val r = finishWindscribe(token, solution, xs, ys, twoFa.trim())
            puzzle = null
            busy = false
            if (r.ok) onDone("Signed in to Windscribe. Now get its servers.") else said = r
        },
    )
}

private const val TRAIL_MAX = 50

private fun decode(b64: String): ImageBitmap? = try {
    val raw = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
} catch (e: Exception) {
    null
}

/**
 * Windscribe's slider puzzle: drag the piece into the gap and let go.
 *
 * What is sent is the desktop's: x is the *piece's* position, clamped, in
 * drawn units; y is measured from the top of the picture; the answer is the
 * piece's position converted back into the picture's own pixels, which is
 * the space the puzzle was cut in. Releasing sends it, once.
 */
@Composable
private fun CaptchaSheet(
    puzzle: Pair<String, Captcha>?,
    onClose: () -> Unit,
    submit: suspend (String, List<Int>, List<Int>) -> Unit,
) {
    val captcha = puzzle?.second
    var sending by remember(puzzle) { mutableStateOf(false) }
    var answer by remember(puzzle) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Sheet(captcha != null, "Windscribe", "Prove you are there", onClose) {
        if (captcha == null) return@Sheet
        Spacer(Modifier.height(6.dp))
        if (captcha.kind == "ascii") {
            Text("Type what the drawing says.", style = Type.small)
            Spacer(Modifier.height(10.dp))
            Text(captcha.art, style = Type.mono.copy(fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                lineHeight = 8.sp, color = Tone.text),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(12.dp)).background(Tone.bg).padding(10.dp))
            Spacer(Modifier.height(10.dp))
            Field(answer, { answer = it }, "Answer")
            ButtonRow {
                Btn(if (sending) "Checking…" else "Send", {
                    sending = true
                    scope.launch { submit(answer.trim(), emptyList(), emptyList()) }
                }, strong = true, enabled = !sending && answer.isNotBlank())
            }
            return@Sheet
        }

        Text(if (sending) "Checking…" else "Drag the piece into the gap, then let go.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        val bg = remember(captcha) { decode(captcha.background) }
        val piece = remember(captcha) { decode(captcha.slider) }
        if (bg == null || piece == null) {
            SaidLine("The puzzle did not come through. Close this and try again.", bad = true)
            return@Sheet
        }

        val density = LocalDensity.current
        var left by remember(puzzle) { mutableFloatStateOf(0f) }
        val xs = remember(puzzle) { mutableStateListOf<Int>() }
        val ys = remember(puzzle) { mutableStateListOf<Int>() }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val drawn = with(density) { maxWidth.toPx() }
            val scale = drawn / bg.width
            val pieceW = piece.width * scale
            val span = (drawn - pieceW).coerceAtLeast(0f)
            val stageH = with(density) { (bg.height * scale).toDp() }

            fun drag(change: androidx.compose.ui.geometry.Offset, dx: Float) {
                left = (left + dx).coerceIn(0f, span)
                xs += with(density) { left.toDp().value.roundToInt() }
                ys += with(density) { change.y.toDp().value.roundToInt() }
                while (xs.size > TRAIL_MAX) { xs.removeAt(0); ys.removeAt(0) }
            }
            fun release() {
                if (sending) return
                sending = true
                val solution = (left / scale).roundToInt().toString()
                scope.launch { submit(solution, xs.toList(), ys.toList()) }
            }

            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(stageH)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(puzzle) {
                            detectDragGestures(
                                onDragStart = { xs.clear(); ys.clear() },
                                onDragEnd = { release() },
                                onDragCancel = { release() },
                            ) { change, amount -> if (!sending) drag(change.position, amount.x) }
                        },
                ) {
                    Image(bg, null, Modifier.fillMaxWidth().height(stageH), contentScale = ContentScale.FillWidth)
                    Image(piece, null,
                        Modifier
                            .offset { IntOffset(left.roundToInt(), (captcha.top * scale).roundToInt()) }
                            .width(with(density) { pieceW.toDp() })
                            .height(with(density) { (piece.height * scale).toDp() }),
                        contentScale = ContentScale.FillBounds)
                }
                Spacer(Modifier.height(14.dp))
                // The rail: a knob that reads as draggable, moving the same piece.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(Tone.control)
                        .border(1.dp, Tone.border, CircleShape)
                        .pointerInput(puzzle) {
                            detectDragGestures(
                                onDragStart = { xs.clear(); ys.clear() },
                                onDragEnd = { release() },
                                onDragCancel = { release() },
                            ) { change, amount ->
                                if (!sending) drag(change.position.copy(y = (captcha.top * scale)), amount.x)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset { IntOffset(left.roundToInt(), 0) }
                            .width(with(density) { pieceW.toDp() })
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(Tone.ok.copy(alpha = 0.85f)),
                    )
                }
            }
        }
        Text("One try. If it is refused, nothing is retried - close this and sign in again.",
            style = Type.small, modifier = Modifier.padding(top = 12.dp))
    }
}
