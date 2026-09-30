package io.github.munzzyy.tern.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.munzzyy.tern.log.TernLog
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class InvalidHostException(host: String) : IllegalArgumentException("Not a host name: ${host.take(80)}")

/**
 * Tokens per exact host, sealed with AES-256-GCM under a Keystore key that cannot be exported. The
 * host is bound in as associated data, so a ciphertext copied under another host does not open.
 */
class TokenVault(context: Context, name: String = DEFAULT_NAME, private val alias: String = DEFAULT_ALIAS) {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Synchronized
    fun put(host: String, token: String?) {
        val key = normalizeHost(host)
        if (token.isNullOrBlank()) {
            prefs.edit().remove(key).commit()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val sealed = cipher.iv + cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun tokenFor(host: String): String? {
        val key = runCatching { normalizeHost(host) }.getOrNull() ?: return null
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            if (sealed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            TernLog.w(TAG, "A stored token for $key could not be opened (${e.javaClass.simpleName})")
            null
        } catch (e: IllegalArgumentException) {
            TernLog.w(TAG, "A stored token for $key is malformed")
            null
        }
    }

    fun hosts(): List<String> = prefs.all.keys.sorted()

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun secretKey(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_NAME = "tokens"
        const val DEFAULT_ALIAS = "tern-tokens"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG = "TernVault"
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*$")

        fun normalizeHost(host: String): String {
            val h = host.trim().lowercase().removeSuffix(".")
            if (h.length > 253 || !HOST.matches(h)) throw InvalidHostException(host)
            return h
        }
    }
}
