package com.zenith.player.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.zenith.player.cloud.SupabaseAuthRepository
import com.zenith.player.cloud.SupabaseClient
import com.zenith.player.data.ZenithDatabase
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface CloudSyncGateway {
    suspend fun pushChanges(serializedChanges: List<String>): SyncResult
    suspend fun pullChanges(cursor: String?): PullResult
}

data class SyncResult(val acceptedIds: List<Long>)
data class PullResult(val cursor: String?, val changes: List<String>)

/** Sends explicitly queued mutations to Supabase. RLS remains the authority for ownership. */
class SupabaseCloudSyncGateway(private val context: Context) : CloudSyncGateway {
    private val auth = SupabaseAuthRepository(context)
    private val client = SupabaseClient()

    override suspend fun pushChanges(serializedChanges: List<String>): SyncResult {
        val token = auth.validAccessToken() ?: throw IOException("Войди в аккаунт ZENITH для синхронизации.")
        val accepted = mutableListOf<Long>()
        for (raw in serializedChanges) {
            val item = JSONObject(raw)
            val queueId = item.optLong("_queueId", -1L)
            val table = item.getString("table")
            when (item.optString("action", "upsert")) {
                "delete" -> client.delete(table, item.getString("filter"), token)
                else -> client.upsert(table, item.getJSONObject("body"), token, item.optString("conflict").takeIf { it.isNotBlank() })
            }
            if (queueId > 0) accepted += queueId
        }
        return SyncResult(accepted)
    }

    // Realtime change-feed download is a separate milestone; current sync is a safe push-only queue.
    override suspend fun pullChanges(cursor: String?): PullResult = PullResult(cursor, emptyList())
}

class ZenithSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = ZenithDatabase.get(applicationContext).dao()
        val pending = dao.pendingSync()
        if (pending.isEmpty()) return Result.success()
        if (SupabaseAuthRepository(applicationContext).currentSession() == null) return Result.success()
        return try {
            val payloads = pending.map { row ->
                val payload = JSONObject(row.payload).put("_queueId", row.id)
                payload.toString()
            }
            val result = SupabaseCloudSyncGateway(applicationContext).pushChanges(payloads)
            if (result.acceptedIds.isNotEmpty()) dao.removeSynced(result.acceptedIds)
            if (result.acceptedIds.size == pending.size) Result.success() else Result.retry()
        } catch (_: IOException) { Result.retry() }
        catch (_: Exception) { Result.failure() }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ZenithSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("zenith-supabase-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Helpers for feature repositories to persist cloud mutations while offline. */
object CloudMutationQueue {
    suspend fun enqueueUpsert(
        context: Context,
        dao: com.zenith.player.data.ZenithDao,
        table: String,
        entityId: String,
        body: JSONObject,
        conflict: String? = null
    ) {
        require(table.matches(Regex("[a-z_]{2,40}")))
        val payload = JSONObject().put("table", table).put("action", "upsert")
            .put("body", body).put("conflict", conflict ?: "").toString()
        dao.enqueueSync(com.zenith.player.data.PendingSyncEntity(
            entityType = table, entityId = entityId, operation = "upsert", payload = payload
        ))
        ZenithSyncWorker.enqueue(context)
    }

    suspend fun enqueueDelete(
        context: Context,
        dao: com.zenith.player.data.ZenithDao,
        table: String,
        entityId: String,
        filter: String
    ) {
        require(table.matches(Regex("[a-z_]{2,40}")))
        val payload = JSONObject().put("table", table).put("action", "delete").put("filter", filter).toString()
        dao.enqueueSync(com.zenith.player.data.PendingSyncEntity(
            entityType = table, entityId = entityId, operation = "delete", payload = payload
        ))
        ZenithSyncWorker.enqueue(context)
    }
}
