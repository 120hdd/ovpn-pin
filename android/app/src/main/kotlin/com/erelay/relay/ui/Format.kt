package com.erelay.relay.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/*
 * The small sayings: sizes, rates, country names, flags. One place, so that
 * "1.23 MB" is written the same way on every screen.
 */

/** The core folds gb into uk; Android's tables and the emoji know it as GB. */
private fun iso(code: String): String {
    val c = code.lowercase().take(2)
    return if (c == "uk") "GB" else c.uppercase()
}

/** A country's name from its two letters, in English as the desktop has it. */
fun countryName(code: String): String {
    if (code.isBlank()) return ""
    val name = try {
        Locale.Builder().setRegion(iso(code)).build().getDisplayCountry(Locale.ENGLISH)
    } catch (e: Exception) {
        ""
    }
    return if (name.isBlank() || name.equals(iso(code), true)) code.uppercase() else name
}

/**
 * The flag as an emoji: two regional-indicator letters, which every phone
 * this runs on draws as the flag. The desktop ships SVGs because Windows
 * draws these as letters; Android does not.
 */
fun flagOf(code: String): String {
    val c = iso(code)
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) +
        String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

/** Base 1024, three significant figures - the desktop meter's numbers. */
fun bytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "${sig3(v)} ${units[i]}"
}

/** Below 512 B/s it is idle: a keepalive is not traffic anybody means. */
fun rate(bps: Long): String = if (bps < 512) "idle" else "${bytes(bps)}/s"

private fun sig3(v: Double): String {
    if (v == 0.0) return "0"
    val digits = (2 - log10(abs(v)).toInt()).coerceIn(0, 2)
    val p = 10.0.pow(digits)
    val r = Math.round(v * p) / p
    return if (digits == 0) r.toLong().toString() else "%.${digits}f".format(Locale.US, r)
}

/**
 * What a pick code says on the front of the app. Four shapes: auto, a
 * country, a city, and one file - with or without a provider after a colon.
 */
fun nameOfPick(code: String, countryNames: Map<String, String>): String {
    if (code == "auto") return "Fastest available"
    if (code.startsWith("file:")) {
        return code.removePrefix("file:").replace(Regex("^\\d+\\.\\d+s-"), "")
            .removeSuffix(".ovpn")
    }
    val (place, provider) = code.split(":").let { it[0] to it.getOrNull(1) }
    val cc = place.substringBefore("/")
    val city = place.substringAfter("/", "")
    val country = countryNames[cc] ?: countryName(cc)
    val base = if (city.isEmpty()) country else "${city.replaceFirstChar { it.uppercase() }}, $country"
    return if (provider.isNullOrEmpty()) base else "$base · ${providerName(provider)}"
}

fun providerName(key: String) = when (key) {
    "surfshark" -> "Surfshark"
    "windscribe" -> "Windscribe"
    else -> key.replaceFirstChar { it.uppercase() }
}

/** The country a pick code is in, for its flag. */
fun countryOfPick(code: String): String = when {
    code == "auto" -> ""
    code.startsWith("file:") ->
        code.removePrefix("file:").replace(Regex("^\\d+\\.\\d+s-"), "").take(2)
    else -> code.substringBefore(":").substringBefore("/")
}

fun minutes(since: Long): Int =
    if (since <= 0) 0 else ((System.currentTimeMillis() / 1000 - since) / 60).toInt()
