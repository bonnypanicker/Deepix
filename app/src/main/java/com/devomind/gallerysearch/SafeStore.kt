package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest

/**
 * Small SharedPreferences facade for the encrypted photo locker ("Safe").
 *
 * What lives here is only enough to (a) validate a typed password and (b) let biometrics
 * hand back the password. None of the actual photos or their key are stored here — the photos
 * are standard AES-256 zips in the user-picked vault folder (see [SafeCrypto]/[SafeManager]),
 * so the vault is recoverable from the password alone, even after an uninstall.
 *
 * Stored:
 *  - salt + PBKDF2 verifier   → validate a typed password without touching a zip
 *  - vault tree uri (SAF)     → the public folder that survives uninstall
 *  - biometric blob + iv      → the password encrypted by a Keystore key (see [SafeKeystore])
 */
object SafeStore {
    private const val PrefName = "photo_safe"
    private const val KeyConfigured = "configured"
    private const val KeySalt = "pw_salt"
    private const val KeyVerifier = "pw_verifier"
    private const val KeyIterations = "pw_iterations"
    private const val KeyTreeUri = "vault_tree_uri"
    private const val KeyBiometricEnabled = "biometric_enabled"
    private const val KeyBiometricBlob = "biometric_pw_blob"
    private const val KeyBiometricIv = "biometric_pw_iv"

    const val PbkdfIterations = 120_000
    const val SaltBytes = 16

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean =
        prefs(context).getBoolean(KeyConfigured, false)

    /** Persists the password verifier (salt + PBKDF2 hash). Does not store the password itself. */
    fun savePasswordVerifier(context: Context, password: String) {
        val salt = SafeCrypto.randomBytes(SaltBytes)
        val verifier = SafeCrypto.pbkdf2(password, salt, PbkdfIterations, 256)
        prefs(context).edit()
            .putString(KeySalt, b64(salt))
            .putString(KeyVerifier, b64(verifier))
            .putInt(KeyIterations, PbkdfIterations)
            .putBoolean(KeyConfigured, true)
            .apply()
    }

    /** True when [password] matches the stored verifier (constant-time compare). */
    fun verifyPassword(context: Context, password: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(KeySalt, null)?.let(::unb64) ?: return false
        val expected = p.getString(KeyVerifier, null)?.let(::unb64) ?: return false
        val iterations = p.getInt(KeyIterations, PbkdfIterations)
        val actual = SafeCrypto.pbkdf2(password, salt, iterations, expected.size * 8)
        return MessageDigest.isEqual(expected, actual)
    }

    /** Salt used to derive the thumbnail-cache key; falls back if not yet configured. */
    fun saltOrNull(context: Context): ByteArray? =
        prefs(context).getString(KeySalt, null)?.let(::unb64)

    fun getTreeUri(context: Context): Uri? =
        prefs(context).getString(KeyTreeUri, null)?.let(Uri::parse)

    fun setTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KeyTreeUri) else putString(KeyTreeUri, uri.toString())
        }.apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KeyBiometricEnabled, false) &&
            prefs(context).contains(KeyBiometricBlob)

    /** Stores the password encrypted by the biometric-gated Keystore key. */
    fun saveBiometricPassword(context: Context, blob: ByteArray, iv: ByteArray) {
        prefs(context).edit()
            .putString(KeyBiometricBlob, b64(blob))
            .putString(KeyBiometricIv, b64(iv))
            .putBoolean(KeyBiometricEnabled, true)
            .apply()
    }

    fun getBiometricBlob(context: Context): ByteArray? =
        prefs(context).getString(KeyBiometricBlob, null)?.let(::unb64)

    fun getBiometricIv(context: Context): ByteArray? =
        prefs(context).getString(KeyBiometricIv, null)?.let(::unb64)

    /** Forgets the biometric password (e.g. key invalidated by new fingerprint enrollment). */
    fun clearBiometric(context: Context) {
        prefs(context).edit()
            .remove(KeyBiometricBlob)
            .remove(KeyBiometricIv)
            .putBoolean(KeyBiometricEnabled, false)
            .apply()
        SafeKeystore.deleteKey()
    }

    /** Wipes all Safe configuration from this device (does not touch the vault files). */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        SafeKeystore.deleteKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
