package com.zenith.player.cloud

import com.zenith.player.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SupabaseClient(
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val isConfigured: Boolean get() = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    data class AuthResult(val accessToken: String, val refreshToken: String, val userId: String,
                          val email: String?, val expiresIn: Long)

    suspend fun signUp(email: String, password: String): AuthResult = auth("/auth/v1/signup", JSONObject()
        .put("email", email.trim()).put("password", password))

    suspend fun signIn(email: String, password: String): AuthResult = auth("/auth/v1/token?grant_type=password", JSONObject()
        .put("email", email.trim()).put("password", password))

    suspend fun refresh(refreshToken: String): AuthResult = auth("/auth/v1/token?grant_type=refresh_token", JSONObject()
        .put("refresh_token", refreshToken))

    suspend fun signOut(accessToken: String) {
        request("POST", "/auth/v1/logout", "{}", accessToken)
    }

    suspend fun upsertProfile(accessToken: String, userId: String, displayName: String) {
        val body = JSONArray().put(JSONObject().put("id", userId).put("display_name", displayName))
        request("POST", "/rest/v1/user_profiles?on_conflict=id", body.toString(), accessToken,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
    }

    suspend fun getProfile(accessToken: String): JSONObject? {
        val session = request("GET", "/auth/v1/user", null, accessToken) as? JSONObject ?: return null
        val userId = session.optString("id")
        if (userId.isBlank()) return null
        val rows = request("GET", "/rest/v1/user_profiles?id=eq.$userId&select=id,display_name,avatar_url,bio,created_at", null, accessToken)
        return if (rows is JSONArray && rows.length() > 0) rows.getJSONObject(0) else null
    }

    suspend fun upsert(table: String, body: JSONObject, accessToken: String, conflict: String? = null) {
        require(table.matches(Regex("[a-z_]{2,40}")))
        val suffix = conflict?.let { "?on_conflict=$it" } ?: ""
        request("POST", "/rest/v1/$table$suffix", JSONArray().put(body).toString(), accessToken,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
    }

    suspend fun delete(table: String, filter: String, accessToken: String) {
        require(table.matches(Regex("[a-z_]{2,40}")))
        require(filter.matches(Regex("[a-z_]{1,40}=eq\\.[A-Za-z0-9_@.\\-]{1,200}")))
        request("DELETE", "/rest/v1/$table?$filter", null, accessToken)
    }

    private suspend fun auth(path: String, body: JSONObject): AuthResult {
        val json = request("POST", path, body.toString(), null) as? JSONObject
            ?: throw IOException("Некорректный ответ Supabase Auth.")
        val user = json.optJSONObject("user") ?: json
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val id = user.optString("id")
        if (access.isBlank() || refresh.isBlank() || id.isBlank()) {
            throw IOException("Supabase не вернул сессию. Возможно, включено подтверждение почты.")
        }
        return AuthResult(access, refresh, id, user.optString("email").takeIf { it.isNotBlank() }, json.optLong("expires_in", 3600))
    }

    private suspend fun request(method: String, path: String, body: String?, accessToken: String?, extra: Map<String, String> = emptyMap()): Any {
        if (!isConfigured) throw IOException("Supabase не настроен. Заполни supabase.url и supabase.anonKey в local.properties.")
        val builder = Request.Builder().url(baseUrl + path)
            .header("apikey", anonKey).header("Accept", "application/json")
        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        extra.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toRequestBody(jsonType)
        builder.method(method, when (method) { "GET", "DELETE" -> null; else -> requestBody ?: "{}".toRequestBody(jsonType) })
        val response = http.newCall(builder.build()).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = try { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } } catch (_: Exception) { text }
                throw IOException("Supabase (${it.code}): ${detail.ifBlank { it.message }}")
            }
            if (text.isBlank()) return JSONObject()
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }
}
