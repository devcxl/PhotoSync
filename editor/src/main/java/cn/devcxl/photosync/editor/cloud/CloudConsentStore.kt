package cn.devcxl.photosync.editor.cloud

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the one-time privacy and cost acknowledgement for cloud generative edits.
 *
 * The flag defaults to `false`, so a fresh install cannot reach a BYOK endpoint before the user has
 * seen the disclosure sheet. It is deliberately stored next to the provider catalogue rather than in
 * the encrypted credential blob: the acceptance itself is not a secret, only an audit of consent.
 * Resetting the cloud switch does not clear it; [reset] exists for an explicit "forget my choice"
 * action.
 *
 * Every method reads or writes [SharedPreferences] and must be called off the main thread.
 */
class CloudConsentStore internal constructor(private val preferences: SharedPreferences) {

    /** Creates a store backed by the app's private preferences. */
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    /** True only after the user explicitly accepted the cloud disclosure. */
    fun isAccepted(): Boolean = preferences.getBoolean(KEY_CONSENT_ACCEPTED, false)

    /** Records the explicit acknowledgement. */
    fun accept() {
        preferences.edit().putBoolean(KEY_CONSENT_ACCEPTED, true).apply()
    }

    /** Forgets the acknowledgement so the next request shows the disclosure again. */
    fun reset() {
        preferences.edit().remove(KEY_CONSENT_ACCEPTED).apply()
    }

    companion object {
        /** SharedPreferences file holding the cloud consent state. */
        const val PREFERENCES_NAME = "photosync_cloud_consent"

        /** Preference key required by the P4 privacy contract. */
        const val KEY_CONSENT_ACCEPTED = "cloud_consent_accepted"
    }
}
