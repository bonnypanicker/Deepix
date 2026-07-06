package com.devomind.gallerysearch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore key that gates the Safe password behind biometrics.
 *
 * The key requires user authentication ([KeyGenParameterSpec.Builder.setUserAuthenticationRequired]),
 * so a [Cipher] built from it can only be finalized once a [androidx.biometric.BiometricPrompt]
 * has authenticated the user via its `CryptoObject`. We use it to encrypt the vault password at
 * enrollment and decrypt it on unlock — giving "fingerprint in, no typing" while the password
 * itself stays the only recovery root (Keystore keys are destroyed on uninstall).
 */
object SafeKeystore {
    private const val AndroidKeyStore = "AndroidKeyStore"
    private const val KeyAlias = "photo_safe_biometric_key"
    private const val Transformation =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
    const val GcmTagBits = 128

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(AndroidKeyStore).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KeyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // A fresh fingerprint enrollment invalidates the key → we fall back to the password
            // and re-enroll. This is the safe default for a locker.
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** Cipher for encrypting the password at enrollment. Its `iv` is read after the biometric doFinal. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(Transformation).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    /** Cipher for recovering the password on unlock, bound to the stored [iv]. */
    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(Transformation).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GcmTagBits, iv))
        }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(KeyAlias) }
    }
}
