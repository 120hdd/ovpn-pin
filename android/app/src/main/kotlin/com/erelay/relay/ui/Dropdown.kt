package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A choice of a few, as one field that opens into a menu: what the three-way
 * segment was, for a phone too narrow to set three labels side by side.
 *
 * The field says what is chosen under a caption, with a chevron. Pressed it
 * gives a little - less than a button, it is wider - and the chevron turns
 * over on a spring. The menu grows out of the field rather than appearing
 * over it: scaled from the edge it touches, so it reads as the field opening
 * rather than as a card dropped on the screen. Its options follow one after
 * another, the chosen one with its check. Choosing lets the check land
 * before the menu folds back into the field.
 *
 * A popup rather than a panel opening inside the card. Opened in place, the
 * card grew, the main screen gave the orb less room, and the orb shrank under
 * the menu - the whole screen moved for a choice that should move nothing.
 * Floating over it, the menu cannot be cut off by the card's rounded clip
 * either, and it goes above the field when the field is near the bottom of
 * the screen, which on the main screen it usually is.
 */
@Composable
fun <T> Dropdown(
    options: List<T>,
    chosen: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    caption: String,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
    note: (T) -> String? = { null },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    // Something else took the choice away - a connect starting - and the
    // menu has nothing to offer meanwhile.
    LaunchedEffect(enabled) { if (!enabled) open = false }

    val shape = RoundedCornerShape(Shape.control)
    val turn by animateFloatAsState(if (open) 180f else 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "turn")
    val edge by animateColorAsState(if (open) Tone.text2 else Tone.strokeStrong,
        tween(if (open) 120 else 260), label = "edge")
    val tint by animateColorAsState(if (open) Tone.text else Tone.text3,
        tween(if (open) 120 else 260), label = "chevTint")

    Box(modifier.onSizeChanged { width = it.width }) {
        Row(
            Modifier
                .fillMaxWidth()
                // A 48dp target at the least, and taller when a large font
                // needs it rather than a clipped label.
                .heightIn(min = 48.dp)
                .press(enabled, shape, sink = true, sinkTo = 0.98f, feel = null) {
                    // The light tick is for opening; closing from the field
                    // is quiet, as the outside tap that also closes it is.
                    if (!open) haptics.tick()
                    open = !open
                }
                .semantics {
                    role = Role.DropdownList
                    stateDescription = if (open) "Open" else "Closed"
                }
                .clip(shape)
                .background(Tone.surface2.copy(alpha = if (enabled) 1f else 0.5f))
                .border(1.dp, edge, shape)
                .padding(start = Space.x3, end = Space.x3, top = Space.x2, bottom = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dim = if (enabled) 1f else 0.5f
            icon(chosen)?.let {
                Icon(it, null, Modifier.size(16.dp), tint = Tone.text2.copy(alpha = dim))
                Spacer(Modifier.width(Space.x3))
            }
            Column(Modifier.weight(1f)) {
                Text(caption, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(1.dp))
                // The new choice slides its word in as the menu folds away.
                AnimatedContent(label(chosen), transitionSpec = {
                    fadeIn(tween(Shape.ENTER_MS, delayMillis = 60)) togetherWith fadeOut(tween(Shape.FADE_MS))
                }, label = "chosen") { word ->
                    Text(word, style = Type.strong.copy(color = Tone.text.copy(alpha = dim)),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(Space.x2))
            Icon(Glyph.chevDown, null, Modifier.size(16.dp).graphicsLayer { rotationZ = turn },
                tint = tint.copy(alpha = tint.alpha * dim))
        }

        // Kept in the tree until the fold back into the field has finished,
        // not removed the moment it is asked to close.
        val shown = remember { MutableTransitionState(false) }
        shown.targetState = open
        if (shown.currentState || shown.targetState) {
            val density = LocalDensity.current
            val gap = with(density) { 6.dp.roundToPx() }
            val margin = with(density) { 12.dp.roundToPx() }
            var above by remember { mutableStateOf(false) }
            var settling by remember { mutableStateOf(false) }
            Popup(
                popupPositionProvider = remember(gap, margin) { MenuPlacement(gap, margin) { above = it } },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val t = rememberTransition(shown, label = "menu")
                // A spring on the way out of the field, which overshoots a
                // hair and settles; a short ease on the way back in.
                val grow by t.animateFloat(transitionSpec = {
                    if (false isTransitioningTo true) spring(dampingRatio = 0.74f, stiffness = 520f)
                    else tween(150, easing = FastOutLinearInEasing)
                }, label = "grow") { if (it) 1f else 0f }
                val fade by t.animateFloat(transitionSpec = {
                    if (false isTransitioningTo true) tween(110) else tween(130, delayMillis = 20)
                }, label = "fade") { if (it) 1f else 0f }
                val menuShape = RoundedCornerShape(Shape.control)
                Column(
                    Modifier
                        .then(if (width > 0) Modifier.width(with(density) { width.toDp() })
                              else Modifier.widthIn(min = 220.dp))
                        .graphicsLayer {
                            // From the edge that meets the field: the top when
                            // it opens below it, the bottom when above.
                            transformOrigin = TransformOrigin(0.5f, if (above) 1f else 0f)
                            scaleX = 0.94f + 0.06f * grow
                            scaleY = 0.8f + 0.2f * grow
                            alpha = fade
                        }
                        .shadow(18.dp, menuShape)
                        .clip(menuShape)
                        .background(Tone.surface2)
                        .border(1.dp, Tone.strokeStrong, menuShape)
                        .semantics { paneTitle = caption }
                        .padding(Space.x1),
                ) {
                    options.forEachIndexed { i, o ->
                        key(o) {
                            // One after another, ~30ms apart, each a short way
                            // out of the field's side; all at once going back.
                            val each by t.animateFloat(transitionSpec = {
                                if (false isTransitioningTo true)
                                    tween(240, delayMillis = 50 + i * 30, easing = Shape.swing)
                                else tween(90)
                            }, label = "option") { if (it) 1f else 0f }
                            MenuOption(
                                icon = icon(o), text = label(o), note = note(o), on = o == chosen,
                                shown = { each }, from = if (above) 1f else -1f,
                            ) {
                                if (settling) return@MenuOption
                                settling = true
                                if (o != chosen) {
                                    haptics.confirm()
                                    onPick(o)
                                } else {
                                    haptics.select()
                                }
                                // Long enough to see the check land on the
                                // new choice, short enough not to wait for.
                                scope.launch {
                                    delay(if (o != chosen) 180L else 60L)
                                    open = false
                                    settling = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One option: its mark, its name and a line under it, and the check when it is the one. */
@Composable
private fun MenuOption(
    icon: ImageVector?, text: String, note: String?, on: Boolean,
    shown: () -> Float, from: Float, onClick: () -> Unit,
) {
    val itemShape = RoundedCornerShape(Shape.control)
    // The check springs in with a little overshoot, and out without one.
    val pop by animateFloatAsState(if (on) 1f else 0f,
        if (on) spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium) else tween(120),
        label = "check")
    val wash by animateColorAsState(if (on) Tone.text.copy(alpha = 0.08f) else Color.Transparent,
        tween(200), label = "wash")
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = shown()
                alpha = s.coerceIn(0f, 1f)
                translationY = (1f - s) * from * 8.dp.toPx()
            }
            .heightIn(min = 48.dp)
            .press(shape = itemShape, feel = null, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                selected = on
            }
            .clip(itemShape)
            .background(wash)
            .padding(horizontal = Space.x3, vertical = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (on) Tone.text else Tone.text2)
            Spacer(Modifier.width(Space.x3))
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = Type.strong.copy(color = if (on) Tone.text else Tone.text2),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note != null) {
                Text(note, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Space.x2))
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (pop > 0.01f) Icon(Glyph.check, null,
                Modifier.size(16.dp).graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                    alpha = pop.coerceIn(0f, 1f)
                }, tint = Tone.text)
        }
    }
}

/**
 * Under the field when the menu fits there, over it when it does not, and
 * along whichever side has more room when neither does. Says which, so the
 * menu can grow from the edge that touches the field.
 */
private class MenuPlacement(
    private val gap: Int,
    private val margin: Int,
    private val onSide: (above: Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(margin,
            (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val over = anchorBounds.top - gap - popupContentSize.height
        val roomBelow = windowSize.height - margin - anchorBounds.bottom
        val roomAbove = anchorBounds.top - margin
        val up = when {
            below + popupContentSize.height <= windowSize.height - margin -> false
            over >= margin -> true
            else -> roomAbove > roomBelow
        }
        onSide(up)
        val y = if (up) over.coerceAtLeast(margin)
            else below.coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        return IntOffset(x, y)
    }
}
