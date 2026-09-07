package com.erelay.relay

import java.io.File
import java.util.concurrent.Executors

/**
 * What `app/ui` asks for, answered on a phone.
 *
 * The page calls fifty-odd methods. Most of them mean something here; the
 * rest are the desktop asking about things a phone does not have - a folder
 * picker onto a filesystem, the Windows system proxy, a title bar to
 * minimise. Those are not silently ignored. Each returns [notHere] with a
 * sentence naming what is missing, because the page shows the reason it is
 * given, and "chooseFolder is not a function" is not a reason anyone can act
 * on.
 *
 * Kept apart from [MainActivity] because it is a list rather than a
 * lifecycle: one `when` that grows a line per method as each is wired to the
 * core, and nothing else.
 */
object Bridge {

    class NotHere(message: String) : Exception(message)

    private fun notHere(what: String): Nothing = throw NotHere(what)

    /**
     * An answer that is not ready yet.
     *
     * Half of what the page asks for is a network round trip - test this
     * server, scan for a way in, fetch a list of exits - and the page asks
     * for them the same way it asks the time: `const r = await api.x()`, then
     * it reads `r.edges[0]`.
     *
     * The first version of this bridge answered those immediately with
     * `{ok:true}` and pushed the real answer later as an event. The page has
     * no handler for that event, so the result was dropped in silence and the
     * `{ok:true}` went straight into a TypeError one line further on. So an
     * answer can now be deferred: the MethodChannel result is held, the work
     * runs off the main thread, and the promise settles when there is
     * something true to settle it with.
     *
     * [start] is called on the main thread and must eventually call exactly
     * one of its two callbacks, from any thread.
     */
    class Later(val start: (answer: (String?) -> Unit, fail: (Throwable) -> Unit) -> Unit)

    /**
     * Threads for the deferred work. Cached rather than fixed: these are all
     * blocked on a network for seconds at a time and there are never many at
     * once, so a pool sized for CPUs would serialise a scan behind a fetch.
     */
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "relay-bridge").apply { isDaemon = true }
    }

    /**
     * Run something slow and answer with what it returns. The one rule the
     * caller has to keep is that [block] must not touch a View.
     */
    fun work(block: () -> String?): Later = Later { answer, fail ->
        pool.execute {
            try {
                answer(block())
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /**
     * @param name the method the page called
     * @param args its arguments, already decoded from JSON
     * @return JSON text, which Dart splices into the page's promise without
     *         re-encoding it; null for a method that answers nothing; or a
     *         [Later] for one whose answer has to be waited for
     */
    fun call(ctx: MainActivity, name: String, args: List<Any?>): Any? = when (name) {

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
        "installCommand" -> ctx.installCommand(
            args.getOrNull(0) as? String,
            args.getOrNull(1) as? String,
            args.getOrNull(2) as? String)
        "traffic" -> ctx.traffic()
        "hosts" -> ctx.hosts()

        // The page's own clipboard needs a secure context and this page is
        // loaded out of the APK, so it never has one - it catches that and
        // asks here instead. Answering null, as this did, is a Copy button
        // that marks itself done and puts nothing anywhere.
        "copy" -> ctx.copy(args.getOrNull(0) as? String)

        // -- the connection ---------------------------------------------------

        "connect" -> ctx.beginConnect(args.getOrNull(0) as? String ?: "auto")
        "disconnect" -> ctx.stopTunnel()
        "cancel" -> ctx.cancelRace()

        // -- the list ---------------------------------------------------------

        "exitsIn" -> ctx.exitsIn(
            args.getOrNull(0) as? String ?: "",
            args.getOrNull(1) as? String)
        "favourites" -> ctx.favourites()
        "toggleFavourite" -> ctx.toggleFavourite(args.getOrNull(0) as? String ?: "")
        "setSort" -> ctx.setSort(args.getOrNull(0) as? String)
        "remember" -> ctx.remember(args.getOrNull(0) as? String ?: "auto")

        // -- which pool to connect out of --------------------------------------

        "sources" -> ctx.sources()
        "setSource" -> ctx.setSource(args.getOrNull(0) as? String)
        "resetFolder" -> ctx.resetFolder()

        // -- the ones that stopped answering -----------------------------------

        "deadExits" -> ctx.deadExits()

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
        "startSweep", "cancelSweep", "sweepPlan", "setSweepScope", "setSweepRoute",
        "saveSites", "useSiteFolder", "lookUpOwners" ->
            notHere("Sweeping is a desktop job. The phone connects; the desktop " +
                "works out what to connect to.")
        "startPin", "cancelPin", "pinPlan", "setPinRoute", "usePinnedFolder",
        "pinForProvider" ->
            notHere("The desktop pins. The phone carries what was pinned.")
        "testReach", "cancelReach" ->
            notHere("The reachability sweep is a desktop job.")
        "dropExits", "restoreDropped" ->
            notHere("Nothing has been set aside on this phone.")
        "windscribeFinish", "windscribeRefresh", "windscribeServers",
        "surfsharkServers", "accountAdd", "accountUse", "accountRemove" ->
            notHere("Signing in from the phone is not built yet.")
        "setProviders", "setKeepOnClose" -> null

        else -> throw NotHere("the page asked for '$name', which nothing here answers")
    }

    /** Two lines: a username and a password, as OpenVPN would have been given. */
    fun credentials(file: File): Pair<String, String>? {
        if (!file.isFile) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size >= 2) lines[0] to lines[1] else null
    }
}
