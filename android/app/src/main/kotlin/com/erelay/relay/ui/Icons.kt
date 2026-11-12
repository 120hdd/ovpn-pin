package com.erelay.relay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/*
 * The desktop's icon sprite (Lucide, ISC licence - app/ui/LUCIDE-LICENSE.txt),
 * as vectors. The path data is copied out of index.html's <symbol>s unchanged,
 * so a glyph changed there can be found here by its id.
 */

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
    "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}" +
        "a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}" +
        "v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

/** A Lucide glyph: 24-unit box, 2-unit round stroke, no fill. */
private fun stroke(name: String, vararg paths: String, box: Float = 24f) =
    ImageVector.Builder(name, 24.dp, 24.dp, box, box).apply {
        for (d in paths) {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f * box / 24f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun filled(name: String, box: Float, vararg paths: Pair<String, Float>) =
    ImageVector.Builder(name, 24.dp, 24.dp, box, box).apply {
        for ((d, alpha) in paths) {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.Black),
                fillAlpha = alpha,
                pathFillType = PathFillType.EvenOdd,
            )
        }
    }.build()

object Glyph {
    val settings = stroke("settings", "M14 17H5", "M19 7h-9", circle(17f, 17f, 3f), circle(7f, 7f, 3f))
    val key = stroke("key",
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
        circle(16.5f, 7.5f, .5f))
    val route = stroke("route", circle(6f, 19f, 3f),
        "M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15", circle(18f, 5f, 3f))
    val folder = stroke("folder",
        "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2")
    val gauge = stroke("gauge", "m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0")
    val info = stroke("info", circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
    val alert = stroke("alert", circle(12f, 12f, 10f), "M12 8v4", "M12 16h.01")
    val eye = stroke("eye",
        "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
        circle(12f, 12f, 3f))
    val eyeOff = stroke("eyeOff",
        "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
        "M14.084 14.158a3 3 0 0 1-4.242-4.242",
        "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
        "m2 2 20 20")
    val search = stroke("search", "m21 21-4.34-4.34", circle(11f, 11f, 8f))
    val tower = stroke("tower", "M4.9 16.1C1 12.2 1 5.8 4.9 1.9", "M7.8 4.7a6.14 6.14 0 0 0-.8 7.5",
        circle(12f, 9f, 2f), "M16.2 4.8c2 2 2.26 5.11.8 7.47", "M19.1 1.9a9.96 9.96 0 0 1 0 14.1",
        "M9.5 18h5", "m8 22 4-11 4 11")
    val check = stroke("check", "M20 6 9 17l-5-5")
    val x = stroke("x", "M18 6 6 18", "m6 6 12 12")
    val chevDown = stroke("chevDown", "m6 9 6 6 6-6")
    val chevRight = stroke("chevRight", "m9 6 6 6-6 6")
    val chevLeft = stroke("chevLeft", "m15 6-6 6 6 6")
    val globe = stroke("globe", circle(12f, 12f, 10f),
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val pin = stroke("pin", "M12 17v5",
        "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")
    val activity = stroke("activity",
        "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2")
    val refresh = stroke("refresh", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")
    val bolt = stroke("bolt",
        "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    val star = stroke("star",
        "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z")
    val starFilled = filled("starFilled", 24f,
        "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z" to 1f)
    val arrowDown = stroke("arrowDown", "M6 1.8v8.4", "M2.6 6.8 6 10.2l3.4-3.4", box = 12f)
    val az = stroke("az", "m3 16 4 4 4-4", "M7 4v16", "M15 4h5l-5 6h5", "M15 20v-3.5a2.5 2.5 0 0 1 5 0V20", "M20 18h-5")
    val archive = stroke("archive", rect(2f, 3f, 20f, 5f, 1f),
        "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8", "M10 12h4")
    val layers = stroke("layers",
        "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z",
        "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12",
        "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17")
    val plus = stroke("plus", "M5 12h14", "M12 5v14")
    val minus = stroke("minus", "M5 12h14")
    val copy = stroke("copy", rect(8f, 8f, 14f, 14f, 2f), "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val sparkle = stroke("sparkle",
        "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z")
    val wide = stroke("wide", "M1 7h26M22.5 2.5 27 7l-4.5 4.5", box = 28f)

    /** Surfshark's mark, from the same sprite. */
    val surfshark = filled("surfshark", 24f,
        "M11.47 0C7.815.2 6.3 2.293 5.872 3.43c-1.615 4.866-3.127 14.325-3.33 15.662-.201 1.31-.228 2.119-.228 2.119 0 .328.026.705.102 1.059.454 1.286 1.792 2.37 4.768 1.287a192.353 192.353 0 0 0 9.533-4.44c1.387-.807 3.227-2.32 4.236-4.312.404-.807.682-1.716.733-2.65v-.452c-.026-2.295-.052-4.692-.204-7.013 0 0-.125-1.488-.2-2.017-.076-.53-.177-.733-.177-.733C20.626.906 19.693.38 18.71.126 18.23.026 17.7.024 17.095 0Zm4.692 4.44h.252c.277 0 .48.2.48.452V6.53c0 .252-.203.455-.48.455h-.252c-1.589 0-2.875 1.26-2.875 2.8v2.498c0 2.976-2.472 5.37-5.498 5.37h-.254c-.277 0-.478-.2-.478-.452v-1.64c0-.253.226-.454.478-.454h.254c1.589 0 2.875-1.262 2.875-2.8V9.81c0-2.977 2.472-5.373 5.498-5.373z" to 1f)

    /** Windscribe's mark, from the same sprite. */
    val windscribe = filled("windscribe", 40f,
        "M5 20L9.58333 9.58333L20 5L30.4167 9.58333L35 20H30V11.6667H25.8333L23.3333 14.1667V20H16.6667V14.1667L14.1667 11.6667H10V20H5Z" to .4f,
        "M10 11.6666V29.1666H14.5833L20 23.3333L25.4167 29.1666H30V11.6666H25.8333V24.1666L20 17.9166L14.1667 24.1666V11.6666H10Z" to 1f,
        "M32.0833 7.91677L20 2.91677L7.91666 7.91677L2.91666 20.0001L7.91666 32.0834L20 37.0834L32.0833 32.0834L37.0833 20.0001L32.0833 7.91677ZM34.1667 34.1667L20 40L5.83333 34.1667L0 20L5.83333 5.83333L20 0L34.1667 5.83333L40 20L34.1667 34.1667Z" to 1f)
}
