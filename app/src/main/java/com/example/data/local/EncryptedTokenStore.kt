package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts Azure DevOps Personal Access Tokens (PATs) at rest using AES-256-GCM.
 *
 * Uses the hardware-backed `AndroidKeyStore`. A software AES-256-GCM key stored in
 * SharedPreferences is used ONLY when [allowSoftwareFallback] is set, which by default is true
 * only under Robolectric unit tests (where the `AndroidKeyStore` provider is not registered).
 * On a real device a missing Keystore fails closed: nothing is encrypted with a key that sits
 * unprotected next to the data.
 *
 * Also supports transparent migration of legacy plaintext tokens: if a stored value does not
 * carry the [ENCRYPTED_PREFIX] header, [decryptToken] returns it as-is so callers can re-save
 * it in encrypted form without forcing users to re-authenticate.
 */
class EncryptedTokenStore(
    context: Context,
    private val allowSoftwareFallback: Boolean = isRobolectricEnvironment()
) {

    private val appContext = context.applicationContext ?: context
    private val keyPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    fun isEncrypted(storedValue: String?): Boolean {
        return !storedValue.isNullOrBlank() && storedValue.startsWith(ENCRYPTED_PREFIX)
    }

    /**
     * Encrypts [plainToken] using AES-256-GCM and returns a prefixed Base64 payload:
     * `enc:v1:<Base64(IV[12] + Ciphertext + AuthTag)>`, or `null` if hardware Keystore
     * key generation or encryption fails on an Android device.
     */
    fun encryptToken(plainToken: String): String? {
        val trimmed = plainToken.trim()
        if (trimmed.isEmpty()) return ""
        if (isEncrypted(trimmed)) return trimmed

        return try {
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)

            ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt token with SecretKey", e)
            null
        }
    }

    /**
     * Decrypts a token previously encrypted by [encryptToken].
     * If [storedValue] does not start with [ENCRYPTED_PREFIX], treats it as a legacy plaintext
     * token and returns it directly for seamless in-place migration.
     */
    fun decryptToken(storedValue: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith(ENCRYPTED_PREFIX)) {
            return trimmed
        }

        val payloadBase64 = trimmed.removePrefix(ENCRYPTED_PREFIX)
        return try {
            val combined = Base64.decode(payloadBase64, Base64.NO_WRAP)
            if (combined.size < 2) return ""
            val ivSize = combined[0].toInt() and 0xFF
            if (ivSize <= 0 || combined.size <= 1 + ivSize) return ""

            val iv = combined.copyOfRange(1, 1 + ivSize)
            val cipherBytes = combined.copyOfRange(1 + ivSize, combined.size)

            try {
                val secretKey = getOrCreateSecretKey(allowCreateFallback = false)
                    ?: throw IllegalStateException("Secret key unavailable")
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (_: Exception) {
                if (!allowSoftwareFallback || !keyPrefs.contains(KEY_FALLBACK_SECRET)) {
                    return ""
                }
                val fallbackKey = getOrCreateSoftwareFallbackKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, fallbackKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Returns the process-wide AES-256 data key used to encrypt the on-device work item cache
     * (see [LocalCacheCipher]), creating it on first use.
     *
     * The data key is random, wrapped (encrypted) with the same AndroidKeyStore master key that
     * protects PATs, and stored only in its wrapped form in the backup-excluded key prefs file.
     * Envelope encryption keeps bulk cache encryption fast (no Keystore IPC per chunk) while the
     * key material at rest stays bound to the device's Keystore.
     *
     * Returns `null` if the master key is unavailable; callers must then fail closed (not write
     * plaintext to disk).
     */
    fun getOrCreateCacheDataKey(): SecretKey? {
        cachedCacheDataKey?.let { return it }
        synchronized(CACHE_KEY_LOCK) {
            cachedCacheDataKey?.let { return it }

            val wrapped = keyPrefs.getString(KEY_WRAPPED_CACHE_DATA_KEY, null)
            if (!wrapped.isNullOrBlank()) {
                val unwrapped = decryptToken(wrapped)
                val keyBytes = runCatching { Base64.decode(unwrapped, Base64.NO_WRAP) }.getOrNull()
                if (keyBytes != null && keyBytes.size == AES_KEY_SIZE_BITS / 8) {
                    return SecretKeySpec(keyBytes, "AES").also { cachedCacheDataKey = it }
                }
                // The wrapped key can no longer be unwrapped (e.g. the Keystore key was lost).
                // Any cache files encrypted with it are unreadable, so start over with a new key.
                Log.w(TAG, "Cache data key could not be unwrapped; generating a new one")
            }

            val keyBytes = ByteArray(AES_KEY_SIZE_BITS / 8)
            SecureRandom().nextBytes(keyBytes)
            val newWrapped = encryptToken(Base64.encodeToString(keyBytes, Base64.NO_WRAP))
            if (newWrapped.isNullOrBlank() || !isEncrypted(newWrapped)) {
                return null
            }
            if (!keyPrefs.edit().putString(KEY_WRAPPED_CACHE_DATA_KEY, newWrapped).commit()) {
                return null
            }
            return SecretKeySpec(keyBytes, "AES").also { cachedCacheDataKey = it }
        }
    }

    fun clearFallbackKey() {
        // Lock order must match getOrCreateCacheDataKey(): CACHE_KEY_LOCK first, then this.
        synchronized(CACHE_KEY_LOCK) {
            synchronized(this) {
                cachedCacheDataKey = null
                cachedSecretKey = null
                keyPrefs.edit().clear().commit()
            }
        }
    }

    /**
     * Destroys all key material: the wrapped cache data key, any software fallback key, and the
     * AndroidKeyStore master key. Anything encrypted earlier becomes permanently unreadable.
     * Used when the user signs out of every organization.
     */
    fun destroyAllKeys() {
        clearFallbackKey()
        synchronized(this) {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
            cachedSecretKey = null
        }
    }

    @Synchronized
    private fun getOrCreateSecretKey(allowCreateFallback: Boolean = true): SecretKey? {
        cachedSecretKey?.let { return it }

        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE)
        } catch (e: KeyStoreException) {
            // AndroidKeyStore provider is not available (e.g., local JVM / Robolectric unit tests)
            if (!allowSoftwareFallback) {
                Log.e(TAG, "AndroidKeyStore unavailable; refusing to use a software key", e)
                return null
            }
            if (!allowCreateFallback && !keyPrefs.contains(KEY_FALLBACK_SECRET)) {
                return null
            }
            val fallback = getOrCreateSoftwareFallbackKey()
            cachedSecretKey = fallback
            return fallback
        }

        return try {
            keyStore.load(null)
            val existingKey = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val resolved = if (existingKey != null) {
                existingKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            cachedSecretKey = resolved
            resolved
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get or create AndroidKeyStore secret key", e)
            null
        }
    }

    @Synchronized
    private fun getOrCreateSoftwareFallbackKey(): SecretKey {
        val existingBase64 = keyPrefs.getString(KEY_FALLBACK_SECRET, null)
        if (!existingBase64.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(existingBase64, Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size == AES_KEY_SIZE_BITS / 8) {
                return SecretKeySpec(decoded, "AES")
            }
        }
        val keyBytes = ByteArray(AES_KEY_SIZE_BITS / 8)
        SecureRandom().nextBytes(keyBytes)
        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        keyPrefs.edit().putString(KEY_FALLBACK_SECRET, encoded).commit()
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        private val CACHE_KEY_LOCK = Any()

        /** Robolectric reports this build fingerprint; real devices never do. */
        fun isRobolectricEnvironment(): Boolean = Build.FINGERPRINT == "robolectric"

        /** Shared across instances so every store in the process encrypts with the same data key. */
        @Volatile
        private var cachedCacheDataKey: SecretKey? = null

        private const val TAG = "EncryptedTokenStore"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "azure_boards_pat_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFS_FILE_NAME = "azure_boards_keystore_fallback_prefs"
        private const val KEY_FALLBACK_SECRET = "pref_fallback_aes_256_key"
        private const val KEY_WRAPPED_CACHE_DATA_KEY = "pref_wrapped_cache_data_key_v1"
    }
}
