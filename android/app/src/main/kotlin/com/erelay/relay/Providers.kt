package com.erelay.relay

import android.content.Context
import org.json.JSONObject
import relay.Relay

/**
 * Signing in and fetching what a provider publishes.
 *
 * The window has always had these buttons. On a phone they answered "not
 * built yet", which meant the only way to get exits onto one was to pin them
 * on a desktop and push the files across.
 *
 * The HTTP is all in the Go core, because the three ways of reaching an API
 * from a filtered line live there - through whatever is carrying traffic,
 * straight at it, or at an address found over DNS-over-HTTPS with the name
 * still on the handshake. What is here is the part that belongs to the phone:
 * which folder the answer lands in, and which account it belongs to.
 */
object Providers {

    /** Long enough for a fetch that may go around a filter twice. */
    private const val PATIENCE = 45000L

    /**
     * Surfshark publishes its fleet to anybody, so this is a fetch and some
     * writes. The configs land unpinned in their own inbox, where a pin run
     * will find them.
     */
    fun surfshark(): JSONObject = JSONObject(Relay.surfsharkServersJSON(PATIENCE))

    /**
     * Windscribe's list is public too - it is the sign-in that is not. The
     * inbox is a different folder, because the two providers' unpinned
     * configs have to be countable apart for the cards to say what is waiting.
     */
    fun windscribe(freeOnly: Boolean): JSONObject =
        JSONObject(Relay.windscribeServersJSON(freeOnly, PATIENCE))

    /** Half one of a Windscribe sign-in: a token, and the puzzle with it. */
    fun begin(username: String, password: String): JSONObject =
        JSONObject(Relay.windscribeBeginJSON(username, password, PATIENCE))

    /**
     * Half two, and the only attempt there is.
     *
     * A token is spent whether or not the answer was right, so a retry with
     * the same one is how an account gets rate-limited for a mistake it has
     * already made. The page knows this and closes its dialog either way.
     */
    fun finish(
        username: String, password: String, token: String, solution: String,
        trailX: String, trailY: String, code2fa: String,
    ): JSONObject = JSONObject(Relay.windscribeFinishJSON(
        username, password, token, solution, trailX, trailY, code2fa, PATIENCE))

    /** A session turned into the credential the proxy takes. */
    fun credentials(session: String): JSONObject =
        JSONObject(Relay.windscribeCredentialsJSON(session, PATIENCE))

    /**
     * Sign in, and keep what came of it.
     *
     * One place rather than two, because the three things a successful login
     * produces - a session, a proxy credential, and a roster entry - are only
     * ever useful together. A login that stored the session and failed to
     * fetch the credential would be an account that looks signed in and
     * cannot connect.
     */
    fun signIn(
        ctx: Context, username: String, password: String, label: String,
        token: String, solution: String, trailX: String, trailY: String,
        code2fa: String,
    ): JSONObject {
        val session = finish(username, password, token, solution, trailX, trailY, code2fa)
        if (!session.optBoolean("ok")) return session

        val hash = session.optString("session")
        val creds = credentials(hash)
        if (!creds.optBoolean("ok")) return creds

        Accounts.put(
            ctx, Accounts.WINDSCRIBE, label, session.optString("username", username),
            password = password, session = hash,
            proxyUser = creds.optString("user"), proxyPass = creds.optString("password"),
        )

        return JSONObject().apply {
            put("ok", true)
            put("username", session.optString("username", username))
            put("credentials", true)
            put("remembered", true)
            put("error", "")
            put("premium", session.optBoolean("premium"))
        }
    }

    /**
     * A fresh proxy credential from the session already held.
     *
     * The one call the app makes on its own, forever after the one login -
     * which is what makes the session worth keeping rather than the password.
     */
    fun refresh(ctx: Context): JSONObject {
        val account = Accounts.active(ctx, Accounts.WINDSCRIBE)
            ?: return JSONObject().put("ok", false)
                .put("error", "No Windscribe account is signed in.")
        val hash = Secrets.get(ctx, "session:" + account.optString("id"))
        if (hash.isNullOrEmpty()) {
            return JSONObject().put("ok", false).put("error",
                "That account was adopted from a credential file, so there is " +
                    "no session to refresh. Sign in again to get one.")
        }

        val creds = credentials(hash)
        if (!creds.optBoolean("ok")) return creds

        Accounts.put(
            ctx, Accounts.WINDSCRIBE, account.optString("label"),
            account.optString("username"), session = hash,
            proxyUser = creds.optString("user"), proxyPass = creds.optString("password"),
        )
        return JSONObject().put("ok", true).put("proxyUser", creds.optString("user"))
    }

    /**
     * The trail the puzzle piece was dragged along, as the core wants it.
     *
     * gomobile carries no arrays, so it crosses as text. The values arrive
     * from JavaScript as doubles even when they are whole numbers.
     */
    fun trail(values: List<*>?): String =
        (values ?: emptyList<Any?>())
            .mapNotNull { (it as? Number)?.toInt() }
            .joinToString(",")
}
