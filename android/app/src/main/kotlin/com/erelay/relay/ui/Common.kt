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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.ui.theme.Mono
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
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
    /** How far a [sink] gives: a key's worth by default, less for a wide field. */
    sinkTo: Float = 0.965f,
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
    val scale by animateFloatAsState(if (pressed && enabled && sink) sinkTo else 1f,
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
                    Tone.wash.copy(alpha = (if (Tone.light) 0.06f else 0.09f) * lit))
            }
        }
        .clickable(source, indication = null, enabled = enabled) {
            if (buzz) feel?.let { haptics.performHapticFeedback(it) }
            onClick()
        }
}

/** The page behind everything: the theme's bg, flat. Nothing glows. */
@Composable
fun Backdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tone.bg)) { content() }
}

/**
 * A full-screen sheet: the title, a close button, and a back arrow when there
 * is somewhere to go back to.
 */
@Composable
fun Sheet(
    open: Boolean,
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
            // On a tablet the sheet stands in a column down the middle rather
            // than stretching a list of rows across the whole screen.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = CONTENT_MAX).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // A short screen gives the list under the title the height
                // the title's air would have taken.
                val short = shortScreen()
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.x5, end = Space.x4,
                        top = if (short) Space.x2 else Space.x4, bottom = if (short) Space.x2 else Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(Glyph.chevLeft, "Back", onBack, quiet = true)
                        Spacer(Modifier.width(Space.x2))
                    }
                    Text(title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    IconButton(Glyph.x, "Close", onClose)
                }
                val body = Modifier.fillMaxWidth().weight(1f)
                if (scroll) {
                    Column(body.verticalScroll(rememberScrollState()).padding(horizontal = Space.x4)
                        .padding(bottom = Space.x6), content = content)
                } else {
                    Column(body, content = content)
                }
            }
            }
        }
    }
}

/** As wide as a screen's content gets: a large phone's width, and a little more. */
val CONTENT_MAX = 600.dp

/** The window, in dp: what a screen has to fit into, known before anything is laid out. */
@Composable
fun windowDp(): androidx.compose.ui.unit.DpSize {
    val density = LocalDensity.current
    val px = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    return with(density) { androidx.compose.ui.unit.DpSize(px.width.toDp(), px.height.toDp()) }
}

/**
 * A short phone, or an ordinary one with a large font setting: what the
 * spacing and the art can shrink for. 700dp of height at the normal size is
 * where the main screen stopped fitting without a scroll.
 */
@Composable
fun shortScreen(): Boolean {
    val density = LocalDensity.current
    return windowDp().height.value / density.fontScale.coerceAtLeast(1f) < 700f
}

@Composable
fun IconButton(
    icon: ImageVector, label: String, onClick: () -> Unit,
    quiet: Boolean = false, ringed: Boolean = false, tint: Color = Tone.text2,
    size: Dp = 48.dp, enabled: Boolean = true,
    /** Null when the caller answers the touch itself, with a haptic that says more. */
    feel: HapticFeedbackType? = HapticFeedbackType.ContextClick,
) {
    Box(
        Modifier
            .size(size)
            .press(enabled, RoundedCornerShape(12.dp), sink = true, feel = feel, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .then(if (ringed) Modifier.border(1.dp, Tone.strokeStrong, RoundedCornerShape(12.dp))
                  else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(18.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

/**
 * A section of a screen: a subtitle on the bare background, an optional
 * sentence under it, then whatever the section is - a [Group] of rows, or
 * plain content. Not a card.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = Space.x6)) {
        Row(Modifier.padding(horizontal = Space.x1), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.subtitle, modifier = Modifier.weight(1f, fill = false))
            if (help != null) InfoTip(help)
        }
        if (note != null) {
            Text(note, style = Type.caption, modifier = Modifier.padding(horizontal = Space.x1, vertical = Space.x1))
        }
        Spacer(Modifier.height(Space.x2))
        content()
    }
}

/**
 * A joined list: one surface, an outer radius of 16, 1px separators between
 * rows ([Sep]) and nothing else between them.
 */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Shape.card)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Tone.surface).border(1.dp, Tone.stroke, shape),
        content = content,
    )
}

/** A group whose content is not rows - fields, a paragraph, a button - with the group's padding. */
@Composable
fun Block(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Group(modifier) {
        Column(Modifier.fillMaxWidth().padding(Space.x4), content = content)
    }
}

/** The 1px separator between two rows of a [Group]. */
@Composable
fun Sep() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.stroke))
}

@Composable
private fun rowColors() = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * A settings row: an optional leading icon, the title (body-strong) and its
 * description (caption), a control on the trailing edge. With [onClick] the
 * whole row is the target.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** Instead of [icon]: a flag, a mark. */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.ListItem(
        modifier = modifier
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        colors = rowColors(),
        leadingContent = leading ?: icon?.let { i -> { Icon(i, null, Modifier.size(20.dp), tint = Tone.text2) } },
        headlineContent = { Text(title, style = Type.strong) },
        supportingContent = desc?.let { d -> { Text(d, style = Type.caption) } },
        trailingContent = trailing,
    )
}

/** A row whose whole width is a switch. */
@Composable
fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val buzz = LocalFeel.current.haptics
    androidx.compose.material3.ListItem(
        modifier = modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch) {
                if (buzz) haptics.performHapticFeedback(
                    if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(it)
            },
        colors = rowColors(),
        headlineContent = { Text(title, style = Type.strong) },
        supportingContent = desc?.let { d -> { Text(d, style = Type.caption) } },
        trailingContent = {
            androidx.compose.material3.Switch(
                checked = checked, onCheckedChange = null, enabled = enabled,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = Tone.text, checkedThumbColor = Tone.bg, checkedBorderColor = Color.Transparent,
                    uncheckedTrackColor = Tone.surface2, uncheckedThumbColor = Tone.text2,
                    uncheckedBorderColor = Tone.strokeStrong,
                    disabledCheckedTrackColor = Tone.text.copy(alpha = 0.4f),
                    disabledUncheckedTrackColor = Tone.surface2.copy(alpha = 0.6f),
                ),
            )
        },
    )
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
    // The emphasised button is the page's own text colour, inverted: nothing
    // here is coloured for being clickable.
    val bg = when {
        strong -> Tone.text
        else -> Tone.surface2
    }
    val fg = when {
        strong -> Tone.bg
        danger -> Tone.fail
        else -> Tone.text
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .press(enabled, RoundedCornerShape(Shape.control), sink = true, onClick = onClick)
            .clip(RoundedCornerShape(Shape.control))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .border(1.dp, if (strong) Color.Transparent else Tone.strokeStrong, RoundedCornerShape(Shape.control))
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (enabled) fg else fg.copy(alpha = 0.5f))
            Spacer(Modifier.width(Space.x2))
        }
        // Two lines, broken between words, before it gets smaller: one line
        // with a large font setting cut "Windscribe" off at "Windsc".
        androidx.compose.foundation.text.BasicText(text, style = Type.strong.copy(
            color = if (enabled) fg else fg.copy(alpha = 0.5f), textAlign = TextAlign.Center),
            maxLines = 2, modifier = Modifier.weight(1f, fill = false),
            autoSize = FitText(14.sp, floor = 10.dp))
    }
}

/**
 * The desktop's segment: options in a dark track, a pill sliding under the chosen one.
 *
 * Every label is set in one size, the largest that fits them all - on one
 * line if it can, and then on two, broken between words, never inside one.
 * A narrow phone with a large font setting had "Your server" running into
 * "Server + exit", which ran off the end: a size floor in sp grows with the
 * font setting, so the floor here is in dp and the track grows a line instead.
 */
@Composable
fun <T> Segment(options: List<T>, chosen: T, label: (T) -> String, onPick: (T) -> Unit,
                modifier: Modifier = Modifier, enabled: Boolean = true) {
    val at by animateFloatAsState(options.indexOf(chosen).coerceAtLeast(0).toFloat(),
        tween(300, easing = Shape.swing), label = "seg")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Shape.control))
            .background(Tone.bg)
            .border(1.dp, Tone.stroke, RoundedCornerShape(Shape.control))
            .padding(Space.x1),
    ) {
        val inset = 5.dp
        val base = Type.strong.copy(textAlign = TextAlign.Center)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labels = options.map(label)
        val cell = with(density) { (maxWidth / options.size - inset * 2).roundToPx().coerceAtLeast(1) }
        val fit = remember(labels, cell, density) { fitLabels(measurer, labels, base, cell, density) }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .drawBehind {
                    val w = size.width / options.size
                    drawRoundRect(Tone.surface2, topLeft = androidx.compose.ui.geometry.Offset(w * at, 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((i, o) in options.withIndex()) {
                Box(
                    Modifier.weight(1f).heightIn(min = 42.dp)
                        .press(enabled, RoundedCornerShape(8.dp), feel = HapticFeedbackType.SegmentTick) {
                            if (o != chosen) onPick(o)
                        }
                        .padding(horizontal = inset, vertical = Space.x1),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(labels[i], style = base.copy(fontSize = fit.size, lineHeight = 1.15.em,
                        color = if (o == chosen) Tone.text else Tone.text3),
                        maxLines = fit.lines, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The size and line count a row of labels is set in: see [Segment]. */
private data class LabelFit(val size: TextUnit, val lines: Int)

private fun fitLabels(
    measurer: TextMeasurer, labels: List<String>, style: TextStyle, width: Int, density: Density,
): LabelFit {
    val max = style.fontSize.value
    // 9dp, whatever the font setting: below that a label is not read.
    val floor = minOf(max, 9f / density.fontScale)
    fun fits(size: Float, lines: Int) = labels.all { text ->
        val r = measurer.measure(text, style.copy(fontSize = size.sp, lineHeight = 1.15.em),
            maxLines = lines, constraints = Constraints(maxWidth = width), density = density)
        !r.hasVisualOverflow && !r.breaksWord(text)
    }
    // One line while the labels keep most of their size; past that two lines
    // read better than small print.
    var s = max
    while (s >= max * 0.8f) {
        if (fits(s, 1)) return LabelFit(s.sp, 1)
        s -= 0.5f
    }
    s = max
    while (s >= floor) {
        if (fits(s, 2)) return LabelFit(s.sp, 2)
        s -= 0.5f
    }
    return LabelFit(floor.sp, 2)
}

/** Whether a line of this layout ends inside a word, breaking it in two. */
private fun TextLayoutResult.breaksWord(text: CharSequence) = (0 until lineCount - 1).any { line ->
    val end = getLineEnd(line)
    text.getOrNull(end - 1)?.isLetterOrDigit() == true && text.getOrNull(end)?.isLetterOrDigit() == true
}

/**
 * Text that steps down from [max] until it fits its lines with no word broken
 * in two, but not below [floor]. The floor is in dp, not sp: one in sp grows
 * with the phone's font setting, and at twice the size it no longer fits
 * anything - the text was cut off at the floor instead of shrinking past it.
 */
data class FitText(val max: TextUnit, val floor: Dp, val step: TextUnit = 0.5.sp) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val least = minOf(max.value, floor.value / fontScale)
        var s = max.value
        while (s > least) {
            val r = performLayout(constraints, text, s.sp)
            if (!r.hasVisualOverflow && !r.breaksWord(text)) return s.sp
            s -= step.value
        }
        return least.sp
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
            .background(Tone.bg)
            .border(1.dp, Tone.strokeStrong, RoundedCornerShape(Shape.control))
            .padding(start = Space.x4, end = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = (if (mono) TextStyle(fontFamily = Mono) else Type.body).copy(color = Tone.text)
        Box(Modifier.weight(1f).padding(vertical = Space.x3)) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = Tone.text3))
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true, textStyle = style,
                cursorBrush = SolidColor(Tone.text),
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
            Spacer(Modifier.width(Space.x2))
        }
    }
}

/** A line said in answer to something: muted, or the fail colour when it is bad news. */
@Composable
fun SaidLine(text: String, bad: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(text, style = Type.caption.copy(color = if (bad) Tone.fail else Tone.text2),
        modifier = modifier.padding(top = Space.x2))
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.stroke))
}

/**
 * An (i) that opens the explanation of the thing beside it. The words used to
 * sit under every option as a paragraph; now they wait to be asked for, and
 * the screen shows the options.
 *
 * The card opens under the button, kept inside the window with a 16dp
 * margin whichever side of the screen the button is on, and goes when
 * anything outside it is touched.
 */
@Composable
fun InfoTip(
    text: String, modifier: Modifier = Modifier,
    /** Another mark for the button - an alert for an error's reason. */
    icon: ImageVector = Glyph.info, tint: Color? = null, label: String = "What is this?",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(icon, label, { open = !open }, size = 36.dp,
            tint = tint ?: if (open) Tone.text else Tone.text3)
        if (open) {
            val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
            // 320 wide, or the window less its margins on a phone narrower
            // than that: a 320dp phone had the card's right edge off-screen.
            val widest = minOf(320.dp, windowDp().width - 32.dp).coerceAtLeast(160.dp)
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember(margin) { BelowAnchor(margin) },
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
                    .apply { targetState = true }
                AnimatedVisibility(shown,
                    enter = fadeIn(tween(Shape.ENTER_MS, easing = Shape.ease)) +
                        androidx.compose.animation.scaleIn(tween(Shape.ENTER_MS, easing = Shape.ease),
                            initialScale = 0.94f)) {
                    Box(
                        Modifier
                            .widthIn(max = widest)
                            .shadow(12.dp, RoundedCornerShape(Shape.control),
                                ambientColor = Color.Black.copy(alpha = if (Tone.light) 0.12f else 0.32f),
                                spotColor = Color.Black.copy(alpha = if (Tone.light) 0.12f else 0.32f))
                            .clip(RoundedCornerShape(Shape.control))
                            .background(Tone.surface2)
                            .border(1.dp, Tone.strokeStrong, RoundedCornerShape(Shape.control))
                            .tap { open = false }
                            .padding(horizontal = Space.x4, vertical = Space.x3),
                    ) {
                        Text(text, style = Type.body.copy(color = Tone.text))
                    }
                }
            }
        }
    }
}

/** Under the anchor, nudged sideways to stay inside the window. */
private class BelowAnchor(private val margin: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val centred = anchorBounds.center.x - popupContentSize.width / 2
        val x = centred.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + margin / 4
        val y = if (below + popupContentSize.height <= windowSize.height - margin) below
            else (anchorBounds.top - popupContentSize.height - margin / 4).coerceAtLeast(margin)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.x3), horizontalArrangement = Arrangement.spacedBy(Space.x2),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A caption over a field: what to type, and a quiet word when it is optional. */
@Composable
fun FieldLabel(text: String, hint: String? = null) {
    Row(Modifier.padding(start = Space.x1, bottom = Space.x1), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.strong, modifier = Modifier.weight(1f, fill = false))
        if (hint != null) Text("  $hint", style = Type.caption.copy(color = Tone.text3))
    }
}

/** A row's trailing chevron: this opens something. */
@Composable
fun Chevron() = Icon(Glyph.chevRight, null, Modifier.size(16.dp), tint = Tone.text3)

/** A joined row that opens another screen. */
@Composable
fun NavRow(title: String, desc: String?, onClick: () -> Unit, icon: ImageVector? = null) =
    SettingRow(title, desc = desc, icon = icon, onClick = onClick) { Chevron() }

/** Checkbox colours: text-coloured when ticked, never the state colour. */
@Composable
fun checkColors() = androidx.compose.material3.CheckboxDefaults.colors(
    checkedColor = Tone.text, checkmarkColor = Tone.bg, uncheckedColor = Tone.strokeStrong,
    disabledCheckedColor = Tone.text.copy(alpha = 0.4f), disabledUncheckedColor = Tone.stroke,
)

/** A row whose whole width ticks a checkbox at its leading edge. */
@Composable
fun CheckRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.ListItem(
        modifier = modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox,
                onValueChange = onChange),
        colors = rowColors(),
        leadingContent = {
            androidx.compose.material3.Checkbox(checked, null, enabled = enabled, colors = checkColors())
        },
        headlineContent = { Text(title, style = Type.strong, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = desc?.let { d -> { Text(d, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = trailing,
    )
}
