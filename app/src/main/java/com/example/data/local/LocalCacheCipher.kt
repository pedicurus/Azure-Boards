package com.example.data.local

import java.io.File
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the on-device work item cache files (snapshots, metadata, notifications) with
 * AES-256-GCM, so the organization's board content, assignee names and emails are never stored
 * in plaintext.
 *
 * File layout: `MAGIC(4) | VERSION(1) | IV(12) | Ciphertext + AuthTag(16)`.
 *
 * The data key comes from [EncryptedTokenStore.getOrCreateCacheDataKey], which wraps it with the
 * AndroidKeyStore master key. Each file is authenticated together with its [aad] (the logical
 * file name), so a file cannot be swapped in for another organization's or another kind of
 * cache without failing decryption.
 */
class LocalCacheCipher(private val tokenStore: EncryptedTokenStore) {

    private val secureRandom = SecureRandom()

    /**
     * Writes the header to [out] and returns a stream that encrypts everything written to it.
     * Closing the returned stream writes the GCM tag and closes [out].
     *
     * Returns `null` (and writes nothing) when no data key is available: callers must then skip
     * persisting rather than fall back to plaintext.
     */
    fun encryptingStream(out: OutputStream, aad: String): OutputStream? {
        val key = tokenStore.getOrCreateCacheDataKey() ?: return null
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        out.write(MAGIC)
        out.write(FORMAT_VERSION.toInt())
        out.write(iv)
        return CipherOutputStream(out, cipher)
    }

    /** Whether [file] starts with the encrypted cache header. */
    fun isEncryptedFile(file: File): Boolean {
        if (file.length() < HEADER_SIZE) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(MAGIC.size + 1)
                input.read(header) == header.size &&
                    header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) &&
                    header[MAGIC.size] == FORMAT_VERSION
            }
        }.getOrDefault(false)
    }

    /**
     * Decrypts and authenticates [file], returning the plaintext bytes, or `null` if the file is
     * not in the encrypted format, was tampered with, belongs to a different [aad], or the data
     * key is unavailable.
     */
    fun decryptFile(file: File, aad: String): ByteArray? {
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE + GCM_TAG_LENGTH_BITS / 8) return null
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            if (bytes[MAGIC.size] != FORMAT_VERSION) return null

            val key = tokenStore.getOrCreateCacheDataKey() ?: return null
            val ivStart = MAGIC.size + 1
            val iv = bytes.copyOfRange(ivStart, ivStart + GCM_IV_LENGTH_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        }.getOrNull()
    }

    companion object {
        private val MAGIC = byteArrayOf('A'.code.toByte(), 'Z'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte())
        private const val FORMAT_VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val HEADER_SIZE = 4 + 1 + GCM_IV_LENGTH_BYTES
    }
}
