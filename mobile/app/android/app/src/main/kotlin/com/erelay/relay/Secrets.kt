package com.erelay.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Passwords, kept where the rest of the app cannot casually read them.
 *
 * The desktop seals these with the Windows account's own key and, where that
 * is unavailable, stores nothing at all and keeps the account without a
 * password - because a roster entry you have to retype a password into is
 * worth having and one that leaked a password is not. Same rule here.
 *
 * The store is separate from the ordinary preferences on purpose. `relay.xml`
 * is written by setup-phone.sh through `run-as` on a debug build and is a
 * reasonable place for a domain and a mode; it is not a reasonable place for
 * a password, and having two files means nothing has to remember which keys
 * were which.
 *
 * Nothing in here ever crosses the bridge. The page is told whether a
 * password is set, never what it is - a screenshot of a settings sheet should
 * not be a leak.
 */
object Secrets {

    private const val FILE = "relay.secrets"

    @Volatile private var store: SharedPreferences? = null

    /**
     * Opening this builds a keyset through the Android keystore, which is
     * slow enough to matter on the first call and has been known to throw on
     * devices whose keystore is in a bad state. A failure here is not fatal:
     * it means secrets are not stored, which is a thing the roster already
     * knows how to be.
     */
    @Suppress("DEPRECATION")
    private fun store(ctx: Context): SharedPreferences? {
        store?.let { return it }
        return try {
            // The older four-argument create, and MasterKeys rather than
            // MasterKey. Both are deprecated in later releases of this
            // library and both are what 1.0.0 has - which is the version
            // pinned, because it is the one that certainly exists and this
            // line cannot be relied on to tell us about the others.
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE, alias, ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { store = it }
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "no secret store: ${e.message}")
            null
        }
    }

    /** Store one, or store nothing and say so. */
    fun put(ctx: Context, key: String, value: String?): Boolean {
        val s = store(ctx) ?: return false
        return try {
            if (value.isNullOrEmpty()) s.edit().remove(key).commit()
            else s.edit().putString(key, value).commit()
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "secret not stored: ${e.message}")
            false
        }
    }

    fun get(ctx: Context, key: String): String? = try {
        store(ctx)?.getString(key, null)
    } catch (e: Exception) {
        null
    }

    fun drop(ctx: Context, key: String) {
        put(ctx, key, null)
    }

    /** Everything belonging to one account, when it is removed. */
    fun dropAll(ctx: Context, id: String) {
        for (kind in listOf("pw", "session", "proxyUser", "proxyPass")) {
            drop(ctx, "$kind:$id")
        }
    }
}
