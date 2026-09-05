package com.erelay.relay

import java.io.File

/**
 * What `app/ui` asks for, answered on a phone.
 *
 * The page calls forty-three methods. About a dozen of them mean something
 * here; the rest are the desktop asking about things a phone does not have -
 * a folder picker, the Windows system proxy, a title bar to minimise. Those
 * are not silently ignored. Each returns [notHere] with a sentence naming what
 * is missing, because the page shows the reason it is given, and "chooseFolder
 * is not a function" is not a reason anyone can act on.
 *
 * Kept apart from [MainActivity] because it is a list rather than a lifecycle:
 * one `when` that will grow a line per method as each is wired to the core,
 * and nothing else.
 */
object Bridge {

    class NotHere(message: String) : Exception(message)

    private fun notHere(what: String): Nothing = throw NotHere(what)

    /**
     * @param name the method the page called
     * @param args its arguments, already decoded from JSON
     * @return JSON text, which Dart splices into the page's promise
     *         without re-encoding it, or null for a method that answers nothing
     */
    fun call(ctx: MainActivity, name: String, args: List<Any?>): String? = when (name) {

        // -- what the window reads on the way up ------------------------------

        "boot", "info" -> ctx.describe()
        "status" -> ctx.statusMap()
        "whoami" -> ctx.whoami()
        "seenAs" -> ctx.seenAs()

        // -- the user's own server ---------------------------------------------
        //
        // Same names and same shapes as app/main.py, because the page calling
        // them is the same page. A phone that answered setMode to a method
        // called setProvider would be a page that silently never switched.

        "tunnelPlan" -> ctx.tunnelPlan()
        "saveTunnel" -> ctx.saveTunnel(
            args.getOrNull(0) as? String,
            args.getOrNull(1) as? String,
            args.getOrNull(2) as? String)
        "setMode" -> ctx.setMode(args.getOrNull(0) as? String ?: "surfshark")
        "rescanEdges" -> ctx.rescanEdges()
        "setAllowLan" -> ctx.setAllowLan(args.getOrNull(0) as? Boolean ?: true)
        "testTunnel" -> ctx.testTunnel()
        "traffic" -> ctx.traffic()
        "hosts" -> ctx.hosts()

        // -- the connection ---------------------------------------------------

        "connect" -> ctx.beginConnect(args.getOrNull(0) as? String ?: "auto")
        "disconnect" -> ctx.stopTunnel()
        "cancel" -> ctx.cancelRace()

        // -- the list ---------------------------------------------------------

        "exitsIn" -> ctx.exitsIn(args.getOrNull(0) as? String ?: "")
        "favourites" -> ctx.favourites()
        "toggleFavourite" -> ctx.toggleFavourite(args.getOrNull(0) as? String ?: "")
        "setSort" -> ctx.setSort(args.getOrNull(0) as? String)
        "remember" -> ctx.remember(args.getOrNull(0) as? String ?: "auto")

        // -- accounts ---------------------------------------------------------

        "accountsList" -> ctx.accountsList()

        // -- everything the desktop can do and a phone cannot ------------------
        //
        // Named one by one rather than caught by an else, so that adding a
        // method to the page and forgetting it here is a distinguishable
        // failure ("unknown") rather than a confident wrong answer.

        "chooseFolder", "chooseSweepFolder", "choosePinFolder", "choosePinOut" ->
            notHere("There is no folder picker on a phone. Configs are read from " +
                "the app's own folder.")
        "setSystemProxy" -> notHere("There is no system proxy on a phone - the " +
            "tunnel carries everything.")
        "minimise" -> notHere("There is no window to minimise.")
        "setPort" -> notHere("There is no local port to choose - nothing listens.")
        "startSweep", "cancelSweep", "sweepPlan", "setSweepScope", "saveSites",
        "useSiteFolder", "lookUpOwners" ->
            notHere("Sweeping is a desktop job. The phone connects; the desktop " +
                "works out what to connect to.")
        "startPin", "cancelPin", "pinPlan", "setPinRoute", "usePinnedFolder" ->
            notHere("The desktop pins. The phone carries what was pinned.")
        "testReach", "cancelReach" ->
            notHere("The reachability sweep is a desktop job.")
        "windscribeFinish", "windscribeRefresh", "windscribeServers",
        "surfsharkServers", "accountAdd", "accountUse", "accountRemove" ->
            notHere("Signing in from the phone is not built yet.")
        "setProviders", "resetFolder", "setKeepOnClose", "copy" -> null

        else -> throw NotHere("the page asked for '$name', which nothing here answers")
    }

    /** Two lines: a username and a password, as OpenVPN would have been given. */
    fun credentials(file: File): Pair<String, String>? {
        if (!file.isFile) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size >= 2) lines[0] to lines[1] else null
    }
}
