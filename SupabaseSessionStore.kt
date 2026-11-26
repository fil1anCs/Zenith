package com.zenith.player.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores auth tokens encrypted with an Android Keystore AES-GCM key. */
class SupabaseSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zenith_secure_session", Context.MODE_PRIVATE)
    private val alias = "zenith_supabase_session_key"

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val email: String?,
        val expiresAt: Long
    )

    @Synchronized fun save(session: Session) {
        val json = JSONObject().apply {
            put("access", session.accessToken); put("refresh", session.refreshToken)
            put("user", session.userId); put("email", session.email ?: JSONObject.NULL)
            put("expires", session.expiresAt)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(json)
        val packed = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        prefs.edit().putString("session", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun read(): Session? = try {
        val packed = Base64.decode(prefs.getString("session", null), Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(packed)
        val ivSize = buffer.int
        require(ivSize in 12..16)
        val iv = ByteArray(ivSize).also { buffer.get(it) }
        val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        Session(json.getString("access"), json.getString("refresh"), json.getString("user"),
            json.optString("email").takeIf { it.isNotBlank() && it != "null" }, json.optLong("expires"))
    } catch (_: Exception) { null }

    fun clear() { prefs.edit().remove("session").apply() }

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
