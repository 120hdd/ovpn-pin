package com.erelay.relay

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * More than one account, and one place to keep them.
 *
 * app/accounts.py, on a phone. The roster is a thing on top: activating an
 * account rewrites the credential file that was already there - `auth` for
 * Surfshark, `auth-windscribe` for Windscribe - and nothing underneath learns
 * what an account is. The service still reads two files and does not know it
 * is being switched.
 *
 * Two things differ from the desktop, and both are about where a phone keeps
 * things. Passwords go to [Secrets] rather than to DPAPI. And `auth` keeps
 * its name and its place, because setup-phone.sh pushes it there and a phone
 * already set up that way must keep working: the first run adopts whatever
 * is in that file as an account, exactly as the desktop adopts an existing
 * .ovpn-auth.
 */
object Accounts {

    const val SURFSHARK = "surfshark"
    const val WINDSCRIBE = "windscribe"

    private val LABELS = mapOf(SURFSHARK to "Surfshark", WINDSCRIBE to "Windscribe")

    private fun store(ctx: Context) = File(AppFiles.state(ctx), "accounts.json")

    // -- the file --------------------------------------------------------------

    fun load(ctx: Context): JSONObject {
        val got = try {
            JSONObject(store(ctx).readText())
        } catch (e: Exception) {
            JSONObject()
        }
        if (!got.has("accounts")) got.put("accounts", JSONArray())
        if (!got.has("active")) got.put("active", JSONObject())
        return got
    }

    fun save(ctx: Context, state: JSONObject) {
        try {
            AppFiles.state(ctx).mkdirs()
            val tmp = File(AppFiles.state(ctx), "accounts.json.tmp")
            tmp.writeText(state.toString())
            if (!tmp.renameTo(store(ctx))) {
                store(ctx).writeText(state.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "roster not saved: ${e.message}")
        }
    }

    private fun rows(state: JSONObject): JSONArray = state.optJSONArray("accounts") ?: JSONArray()

    private fun row(state: JSONObject, id: String): JSONObject? {
        val all = rows(state)
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("id") == id) return a
        }
        return null
    }

    /** The active account for a provider, or nothing. */
    fun active(ctx: Context, provider: String): JSONObject? {
        val state = load(ctx)
        val id = state.optJSONObject("active")?.optString(provider).orEmpty()
        return if (id.isEmpty()) null else row(state, id)
    }

    // -- adopting what was already here ----------------------------------------

    /**
     * Turn a pre-existing sign-in into an account, once.
     *
     * A phone set up with `adb push .ovpn-auth` has a working credential and
     * no roster. Showing it an empty account list and a "not set up" screen
     * would be wrong about a phone that connects perfectly well, so the file
     * is read and believed - without a password, because the file holds a
     * service credential rather than the sign-in that produced it.
     */
    fun adopt(ctx: Context) {
        val state = load(ctx)
        if (state.optBoolean("adopted")) return
        state.put("adopted", true)

        Bridge.credentials(AppFiles.auth(ctx))?.let { (user, _) ->
            if (!has(state, SURFSHARK, user)) {
                val id = fresh()
                rows(state).put(JSONObject().apply {
                    put("id", id)
                    put("provider", SURFSHARK)
                    put("label", user)
                    put("username", user)
                    put("added", System.currentTimeMillis() / 1000)
                    put("hasPassword", false)
                })
                state.optJSONObject("active")?.put(SURFSHARK, id)
            }
        }
        Bridge.credentials(AppFiles.windscribeAuth(ctx))?.let { (user, _) ->
            if (!has(state, WINDSCRIBE, user)) {
                val id = fresh()
                rows(state).put(JSONObject().apply {
                    put("id", id)
                    put("provider", WINDSCRIBE)
                    put("label", user)
                    put("username", user)
                    put("added", System.currentTimeMillis() / 1000)
                    put("hasPassword", false)
                })
                state.optJSONObject("active")?.put(WINDSCRIBE, id)
            }
        }
        save(ctx, state)
    }

    private fun has(state: JSONObject, provider: String, username: String): Boolean {
        val all = rows(state)
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("provider") == provider &&
                a.optString("username").equals(username, ignoreCase = true)
            ) return true
        }
        return false
    }

    private fun fresh() = UUID.randomUUID().toString().replace("-", "").take(8)

    // -- what the page draws ---------------------------------------------------

    /**
     * The roster, without any of the secrets in it.
     *
     * `signedIn` is not `hasPassword`. An adopted Surfshark account has no
     * stored password and works: the credential file it was adopted from is
     * still the credential in use. What it cannot do is be switched away from
     * and back again, which is what `hasPassword` says.
     */
    fun listing(ctx: Context): JSONArray {
        adopt(ctx)
        val state = load(ctx)
        val activeIds = state.optJSONObject("active") ?: JSONObject()
        val all = rows(state)
        val out = ArrayList<JSONObject>()

        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            val id = a.optString("id")
            val provider = a.optString("provider")
            val isActive = activeIds.optString(provider) == id
            val hasPassword = a.optBoolean("hasPassword") &&
                Secrets.get(ctx, "pw:$id") != null
            val signedIn = when (provider) {
                WINDSCRIBE -> Secrets.get(ctx, "proxyUser:$id") != null ||
                    (isActive && AppFiles.windscribeAuth(ctx).isFile)
                else -> hasPassword || (isActive && AppFiles.auth(ctx).isFile)
            }
            out.add(JSONObject().apply {
                put("id", id)
                put("provider", provider)
                put("providerName", LABELS[provider] ?: provider)
                put("label", a.optString("label"))
                put("username", a.optString("username"))
                put("active", isActive)
                put("hasPassword", hasPassword)
                put("signedIn", signedIn)
                put("added", a.optLong("added"))
            })
        }
        out.sortWith(compareBy({ it.optString("provider") },
            { it.optString("label").lowercase() }))

        val array = JSONArray()
        for (a in out) array.put(a)
        return array
    }

    // -- changing it -----------------------------------------------------------

    /**
     * Add or update one, and make it the active one for its provider.
     *
     * Matched on the username within a provider rather than on an id, because
     * somebody typing the same account in twice means to correct it, not to
     * have two of it.
     */
    fun put(
        ctx: Context, provider: String, label: String, username: String,
        password: String? = null, session: String? = null,
        proxyUser: String? = null, proxyPass: String? = null,
    ): JSONObject {
        val state = load(ctx)
        val all = rows(state)
        var found: JSONObject? = null
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("provider") == provider &&
                a.optString("username").equals(username, ignoreCase = true)
            ) {
                found = a
                break
            }
        }
        if (found == null) {
            found = JSONObject().apply {
                put("id", fresh())
                put("provider", provider)
                put("added", System.currentTimeMillis() / 1000)
            }
            all.put(found)
        }
        val id = found.optString("id")
        found.put("label", label.ifEmpty { username })
        found.put("username", username)

        if (!password.isNullOrEmpty()) {
            found.put("hasPassword", Secrets.put(ctx, "pw:$id", password))
        }
        if (!session.isNullOrEmpty()) Secrets.put(ctx, "session:$id", session)
        if (!proxyUser.isNullOrEmpty()) Secrets.put(ctx, "proxyUser:$id", proxyUser)
        if (!proxyPass.isNullOrEmpty()) Secrets.put(ctx, "proxyPass:$id", proxyPass)

        state.optJSONObject("active")?.put(provider, id)
        state.put("adopted", true)
        save(ctx, state)
        writeActive(ctx, provider)
        return found
    }

    /** Make an existing account the one in use. */
    fun activate(ctx: Context, id: String): JSONObject? {
        val state = load(ctx)
        val a = row(state, id) ?: return null
        state.optJSONObject("active")?.put(a.optString("provider"), id)
        save(ctx, state)
        writeActive(ctx, a.optString("provider"))
        return a
    }

    /**
     * Remove one, and hand its place to whatever is left.
     *
     * The credential file goes only when nothing of that provider remains -
     * removing one of two accounts should leave the other one connecting.
     */
    fun remove(ctx: Context, id: String): JSONObject? {
        val state = load(ctx)
        val gone = row(state, id) ?: return null
        val provider = gone.optString("provider")

        val kept = JSONArray()
        val all = rows(state)
        var heir: String? = null
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("id") == id) continue
            kept.put(a)
            if (heir == null && a.optString("provider") == provider) heir = a.optString("id")
        }
        state.put("accounts", kept)
        val activeIds = state.optJSONObject("active") ?: JSONObject()
        if (heir != null) activeIds.put(provider, heir) else activeIds.remove(provider)
        save(ctx, state)
        Secrets.dropAll(ctx, id)

        if (heir != null) writeActive(ctx, provider) else credentialFile(ctx, provider).delete()
        return gone
    }

    // -- where the rest of the app reads it ------------------------------------

    private fun credentialFile(ctx: Context, provider: String): File =
        if (provider == WINDSCRIBE) AppFiles.windscribeAuth(ctx) else AppFiles.auth(ctx)

    /**
     * Write the active account's credential to the file that provider is read
     * from. Two lines, the way OpenVPN would have been given them - the same
     * shape setup-phone.sh pushes, so both routes produce one kind of file.
     *
     * An account with nothing stored leaves the file alone rather than
     * emptying it: that is the adopted case, where the file is the only copy
     * of the credential there has ever been.
     */
    fun writeActive(ctx: Context, provider: String) {
        val a = active(ctx, provider) ?: return
        val id = a.optString("id")
        val pair = if (provider == WINDSCRIBE) {
            val user = Secrets.get(ctx, "proxyUser:$id")
            val pass = Secrets.get(ctx, "proxyPass:$id")
            if (user.isNullOrEmpty() || pass.isNullOrEmpty()) null else user to pass
        } else {
            val pass = Secrets.get(ctx, "pw:$id")
            val user = a.optString("username")
            if (user.isEmpty() || pass.isNullOrEmpty()) null else user to pass
        } ?: return

        try {
            credentialFile(ctx, provider).writeText("${pair.first}\n${pair.second}\n")
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "credential not written: ${e.message}")
        }
    }

    /** The credential in use for a provider, read from the file it lives in. */
    fun credentials(ctx: Context, provider: String): Pair<String, String>? =
        Bridge.credentials(credentialFile(ctx, provider))
}
