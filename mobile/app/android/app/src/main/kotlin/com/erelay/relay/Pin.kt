package com.erelay.relay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import relay.PinOptions
import relay.Relay
import java.io.File

/**
 * Turning names into addresses on the phone itself.
 *
 * The thing the whole repo is named after, and until now the one step that
 * still needed a desktop: a config saying `remote fr-030.totallyacdn.com`
 * asks this line to resolve a name it is lying about, and a pinned copy says
 * `remote 146.70.253.194` with the name kept in a comment above it, so the
 * certificate can still be proved and nothing is looked up at connect time.
 *
 * The run is in Go. What is here is which folder it reads, which options are
 * remembered, and the fact that a phone has one output folder rather than a
 * choice of them.
 */
object Pin {

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /**
     * Which inbox to read.
     *
     * Two providers, two piles, because their unpinned configs have to be
     * countable apart for the cards to say what is waiting. Remembered, so
     * that fetching a Windscribe list and then pressing Pin does the thing
     * the cards just offered.
     */
    private fun inbox(ctx: Context): File {
        val saved = prefs(ctx).getString("pinFolder", "").orEmpty()
        if (saved.isNotEmpty()) return File(saved)
        return AppFiles.configs(ctx)
    }

    private fun options(ctx: Context): PinOptions {
        val p = prefs(ctx)
        val o = Relay.newPinOptions()
        o.inbox = inbox(ctx).absolutePath
        o.out = AppFiles.pinned(ctx).absolutePath
        // Directly by default: a phone with nothing pinned has no tunnel, and
        // this is the run that gives it one.
        o.route = p.getString("pinRoute", "direct") ?: "direct"
        o.maxIPs = p.getInt("pinMaxIps", 4).toLong()
        o.test = p.getBoolean("pinTest", true)
        return o
    }

    /** What a run would cost, and what would stop it. */
    fun plan(ctx: Context): JSONObject = JSONObject(Relay.pinPlanJSON(options(ctx), false))

    /**
     * Change one option and answer with the plan it makes.
     *
     * Each argument left out is left alone. The page sends one at a time -
     * the route from a pair of chips, the count from a stepper, the test
     * switch from a toggle - and a call that reset the others would make
     * every control quietly undo the last one.
     */
    fun setRoute(ctx: Context, route: String?, maxIPs: Int?, test: Boolean?): JSONObject {
        prefs(ctx).edit().apply {
            route?.let { putString("pinRoute", if (it == "proxy") "proxy" else "direct") }
            maxIPs?.let { putInt("pinMaxIps", it.coerceIn(1, 32)) }
            test?.let { putBoolean("pinTest", it) }
        }.apply()
        return plan(ctx)
    }

    /**
     * Point the inbox at one provider's pile, and say what is in it.
     *
     * Does not start the run: the page moves to the Servers screen and lets
     * somebody look at the options first, which is right for a thing that
     * takes minutes and writes hundreds of files.
     */
    fun forProvider(ctx: Context, provider: String): JSONObject {
        val folder = if (provider == Accounts.WINDSCRIBE) AppFiles.windscribe(ctx)
        else AppFiles.configs(ctx)
        if (AppFiles.count(folder) == 0) {
            return JSONObject().put("ok", false)
                .put("error", "Nothing waiting in ${folder.absolutePath}.")
        }
        prefs(ctx).edit().putString("pinFolder", folder.absolutePath).apply()

        val out = plan(ctx)
        out.put("ok", true)
        return out
    }

    /** Remember which inbox a fetch just filled, so Pin reads the right one. */
    fun rememberInbox(ctx: Context, folder: File) {
        prefs(ctx).edit().putString("pinFolder", folder.absolutePath).apply()
    }

    fun start(ctx: Context, onEvent: (String) -> Unit): JSONObject =
        JSONObject(Relay.startPin(options(ctx), object : relay.PinProgress {
            override fun onPin(payload: String) = onEvent(payload)
        }))

    fun cancel(): JSONObject = JSONObject(Relay.cancelPin())

    /**
     * The desktop narrows onto the folder a run just filled. A phone reads
     * one folder and the run landed in it, so there is nothing to narrow -
     * but the page asks, and an honest count is a better answer than a
     * refusal.
     */
    fun usePinned(ctx: Context, count: Int): JSONObject = JSONObject().apply {
        put("ok", true)
        put("folder", AppFiles.pinned(ctx).absolutePath)
        put("source", "pinned")
        put("narrowed", false)
        put("count", count)
    }
}
