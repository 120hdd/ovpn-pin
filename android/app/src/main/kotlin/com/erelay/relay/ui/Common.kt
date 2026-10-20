package com.erelay.relay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.ui.theme.Mono
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type

/** A click with no ripple: the desktop's controls light up on their own. */
/** The look-and-feel settings, for controls deep in the tree that answer to them. */
val LocalFeel = androidx.compose.runtime.compositionLocalOf { com.erelay.relay.data.Feel() }

fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    press(enabled = enabled, onClick = onClick)

/**
 * A touch that answers. While a finger is down the control lights up - a
 * white wash over its own [shape] - and with [sink] it gives a little under
 * the finger and springs back, the way a physical key does. A short haptic
 * tick confirms the press landed.
 *
 * Put it first in a button's modifier chain, so the sink scales the whole
 * control - background, border and all - rather than only what is inside it.
 */
fun Modifier.press(
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
    sink: Boolean = false,
    feel: HapticFeedbackType? = HapticFeedbackType.ContextClick,
    source: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = source ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val buzz = LocalFeel.current.haptics
    // In fast, out slow: the light should be there the instant the finger
    // is, and fade rather than blink off when it lifts.
    val lit by animateFloatAsState(if (pressed && enabled) 1f else 0f,
        tween(if (pressed) 40 else 280), label = "lit")
    val scale by animateFloatAsState(if (pressed && enabled && sink) 0.965f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "sink")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (lit > 0.01f) {
                drawOutline(shape.createOutline(size, layoutDirection, this),
                    Color.White.copy(alpha = 0.09f * lit))
            }
        }
        .clickable(source, indication = null, enabled = enabled) {
            if (buzz) feel?.let { haptics.performHapticFeedback(it) }
            onClick()
        }
}

/** The page behind everything: near-black with the faint violet light at the top. */
@Composable
fun Backdrop(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Tone.bg)
            .background(Brush.radialGradient(
                listOf(Tone.glow, Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(540f, 0f), radius = 1400f)),
    ) { content() }
}

/**
 * A full-screen sheet, the desktop's dialogs: rises 12dp while fading in,
 * sinks 10 on the way out. Kicker over title, a close button, and a back
 * arrow when there is somewhere to go back to.
 */
@Composable
fun Sheet(
    open: Boolean,
    kicker: String,
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(Shape.ENTER_MS, easing = Shape.ease)) +
            slideInVertically(tween(Shape.ENTER_MS, easing = Shape.ease)) { it / 60 },
        exit = fadeOut(tween(Shape.SINK_MS)) +
            slideOutVertically(tween(Shape.SINK_MS)) { it / 70 },
    ) {
        BackHandler { (onBack ?: onClose)() }
        Backdrop {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(Glyph.chevLeft, "Back", onBack, quiet = true)
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(kicker.uppercase(), style = Type.kicker)
                        Spacer(Modifier.height(2.dp))
                        Text(title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(Glyph.x, "Close", onClose, ringed = true)
                }
                val body = Modifier.fillMaxWidth().weight(1f)
                if (scroll) {
                    Column(body.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp), content = content)
                } else {
                    Column(body, content = content)
                }
            }
        }
    }
}

@Composable
fun IconButton(
    icon: ImageVector, label: String, onClick: () -> Unit,
    quiet: Boolean = false, ringed: Boolean = false, tint: Color = Tone.textMuted,
    size: Dp = 48.dp, enabled: Boolean = true,
) {
    Box(
        Modifier
            .size(size)
            .press(enabled, RoundedCornerShape(12.dp), sink = true, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .then(if (ringed) Modifier.border(1.5.dp, Tone.focus.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                  else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(18.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

/** A pane: the rounded, bordered block every settings screen is made of. */
@Composable
fun Pane(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    pill: String? = null,
    pillGood: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tone.surface.copy(alpha = 0.72f))
            .border(1.dp, Tone.divider, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Tone.control),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(16.dp), tint = Tone.textMuted) }
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = Type.strong, modifier = Modifier.weight(1f))
                if (pill != null) Pill(pill, good = pillGood)
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun Pill(text: String, good: Boolean = true, dot: Boolean = false) {
    val c = if (good) Tone.ok else Tone.textOff
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.22f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.eyebrow.copy(color = c, fontSize = 10.sp))
    }
}

/** The quiet button: a dark control with a hairline border. */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strong: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val bg = when {
        strong -> Tone.ok
        else -> Tone.control
    }
    val fg = when {
        strong -> Tone.ink
        danger -> Tone.fail
        else -> Tone.text
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .press(enabled, RoundedCornerShape(Shape.control), sink = true, onClick = onClick)
            .clip(RoundedCornerShape(Shape.control))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .border(1.dp, if (strong) Color.Transparent else Tone.border, RoundedCornerShape(Shape.control))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (enabled) fg else fg.copy(alpha = 0.5f))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.button.copy(fontSize = 14.sp, color = if (enabled) fg else fg.copy(alpha = 0.5f)),
            maxLines = 1)
    }
}

/** The desktop's segment: options in a dark track, a pill sliding under the chosen one. */
@Composable
fun <T> Segment(options: List<T>, chosen: T, label: (T) -> String, onPick: (T) -> Unit,
                modifier: Modifier = Modifier, enabled: Boolean = true) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tone.bg.copy(alpha = 0.6f))
            .border(1.dp, Tone.divider, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        val each = maxWidth / options.size
        val at by animateDpAsState(each * options.indexOf(chosen).coerceAtLeast(0),
            tween(300, easing = Shape.swing), label = "seg")
        Box(Modifier.offset(x = at).width(each).fillMaxHeight()
            .clip(RoundedCornerShape(9.dp)).background(Tone.controlActive))
        Row(Modifier.fillMaxSize()) {
            for (o in options) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .press(enabled, RoundedCornerShape(9.dp), feel = HapticFeedbackType.SegmentTick) {
                            if (o != chosen) onPick(o)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(o), style = Type.button.copy(
                        fontSize = 13.sp,
                        color = if (o == chosen) Tone.text else Tone.textOff), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, tween(200, easing = Shape.swing), label = "toggle")
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .press(enabled, CircleShape, sink = true,
                feel = if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn) { onChange(!on) }
            .clip(CircleShape)
            .background(if (on) Tone.ok else Tone.controlActive)
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = x).size(18.dp).clip(CircleShape)
            .background(if (on) Tone.ink else Tone.textMuted))
    }
}

/** A labelled row with a toggle at the end, and a line of explanation under it. */
@Composable
fun ToggleRow(title: String, note: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.strong, modifier = Modifier.weight(1f))
            Toggle(on, onChange)
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note, style = Type.small)
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    var shown by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(Shape.control))
            .background(Tone.bg.copy(alpha = 0.7f))
            .border(1.dp, Tone.border, RoundedCornerShape(Shape.control))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = (if (mono) TextStyle(fontFamily = Mono) else Type.body).copy(color = Tone.text)
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = Tone.textOff))
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true, textStyle = style,
                cursorBrush = SolidColor(Tone.ok),
                visualTransformation = if (secret && !shown) PasswordVisualTransformation()
                    else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType =
                    if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (secret) {
            IconButton(if (shown) Glyph.eyeOff else Glyph.eye, if (shown) "Hide" else "Show",
                { shown = !shown }, quiet = true, size = 36.dp)
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** A line said in answer to something: muted, or the fail colour when it is bad news. */
@Composable
fun SaidLine(text: String, bad: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(text, style = Type.small.copy(color = if (bad) Tone.fail else Tone.textMuted),
        modifier = modifier.padding(top = 8.dp))
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.divider))
}

/** The rows of the settings menu: a title, a line under it, a chevron. */
@Composable
fun MenuRow(title: String, note: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val nudge by androidx.compose.animation.core.animateDpAsState(if (pressed) 4.dp else 0.dp,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "nudge")
    val tint by androidx.compose.animation.animateColorAsState(
        if (pressed) Tone.ok else Tone.textOff, tween(if (pressed) 60 else 300), label = "chev")
    Row(
        Modifier
            .fillMaxWidth()
            .press(source = source, onClick = onClick)
            .background(Tone.ok.copy(alpha = if (pressed) 0.05f else 0f))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong)
            Spacer(Modifier.height(4.dp))
            Text(note, style = Type.small.copy(color = Tone.textMuted))
        }
        Icon(Glyph.chevRight, null, Modifier.offset(x = nudge).size(16.dp), tint = tint)
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}
