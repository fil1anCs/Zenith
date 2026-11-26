package com.zenith.player.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SupabaseAuthRepository(context: Context) {
    private val client = SupabaseClient()
    private val store = SupabaseSessionStore(context)

    val configured: Boolean get() = client.isConfigured
    fun currentSession(): SupabaseSessionStore.Session? = store.read()

    suspend fun signIn(email: String, password: String): SupabaseSessionStore.Session = withContext(Dispatchers.IO) {
        persist(client.signIn(email, password))
    }

    suspend fun signUp(email: String, password: String, displayName: String): SupabaseSessionStore.Session = withContext(Dispatchers.IO) {
        val auth = client.signUp(email, password)
        val session = persist(auth)
        client.upsertProfile(session.accessToken, session.userId, displayName.trim().ifBlank { email.substringBefore('@') })
        session
    }

    suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val current = store.read() ?: return@withContext null
        if (current.expiresAt > System.currentTimeMillis() / 1000 + 90) return@withContext current.accessToken
        try {
            val refreshed = client.refresh(current.refreshToken)
            persist(refreshed).accessToken
        } catch (_: Exception) {
            store.clear(); null
        }
    }

    suspend fun profileName(): String? = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: return@withContext null
        try { client.getProfile(token)?.optString("display_name")?.takeIf { it.isNotBlank() } }
        catch (_: Exception) { null }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val current = store.read()
        try { if (current != null) client.signOut(current.accessToken) } finally { store.clear() }
    }

    private fun persist(auth: SupabaseClient.AuthResult): SupabaseSessionStore.Session {
        val session = SupabaseSessionStore.Session(auth.accessToken, auth.refreshToken, auth.userId,
            auth.email, System.currentTimeMillis() / 1000 + auth.expiresIn)
        store.save(session)
        return session
    }
}
