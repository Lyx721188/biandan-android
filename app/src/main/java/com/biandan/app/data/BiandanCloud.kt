package com.biandan.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class CloudError(
    message: String,
    val status: Int? = null,
    val code: String? = null,
) : Exception(message)

class ReauthRequired(message: String) : Exception(message)

/** 需要访问令牌时回调；rejected 传上次被云端拒绝的令牌，用于强制刷新。 */
fun interface AccessTokenProvider {
    suspend fun accessToken(rejected: String? = null): String
}

/**
 * 便单华为同步传输层。忠实对应发布包 biandan_cloud.py 的 BiandanCloud。
 */
class BiandanCloud(
    private val tokenProvider: AccessTokenProvider,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    companion object {
        const val DATA_URL = "https://driveapis.cloud.huawei.com.cn/sync/v1/rongqi"
        val KINDS = listOf("Task", "showTask", "favorites", "tomato")
        const val RECORD_FIELDS = "kind,id,attributes,createdTime,editedTime,version"
        const val LIST_FIELDS = "nextCursor,records($RECORD_FIELDS)"
        private val JSON_MEDIA = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }

    private val writeLock = Mutex()

    private fun traceId(): String =
        "07200_02_${System.currentTimeMillis()}_${"%08d".format(Random.nextInt(100_000_000))}"

    private suspend fun request(
        method: String,
        path: String,
        params: Map<String, String>? = null,
        attributes: Attrs? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        // 401 时 doRequest 内部先刷新令牌，只重试被明确拒绝的那一次请求。
        doRequest(method, path, params, attributes, tokenProvider.accessToken())
    }

    private suspend fun doRequest(
        method: String,
        path: String,
        params: Map<String, String>?,
        attributes: Attrs?,
        token: String,
        retried: Boolean = false,
    ): JsonObject {
        val query = params?.entries?.joinToString("&", prefix = "?") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        } ?: ""
        val body = attributes?.let {
            buildJsonObject { put("attributes", it) }
                .toString().toRequestBody(JSON_MEDIA)
        }
        val request = Request.Builder()
            .url("$DATA_URL$path$query")
            .header("Accept", "application/json")
            .header("x-hw-sync-env", "dataSync2DP")
            .header("x-hw-app-version", "16.3.0.001")
            .header("x-hw-trace-id", traceId())
            .header("Authorization", "Bearer $token")
            .method(method, body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        if (raw.isBlank()) return@use JsonObject(emptyMap())
                        val data = json.parseToJsonElement(raw).jsonObject
                        if (data["errors"] != null || data["error"] != null) {
                            throw CloudError("Service returned an error or an unexpected response")
                        }
                        data
                    }
                    response.code == 401 -> {
                        val failure = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                        val reauth = failure?.get("error")?.jsonPrimitive?.content == "reauthorization_required"
                        if (!retried) {
                            // 刷新令牌后重试被拒绝的请求一次。
                            val fresh = tokenProvider.accessToken(rejected = token)
                            return doRequest(method, path, params, attributes, fresh, retried = true)
                        }
                        throw CloudError("$method failed with HTTP 401", 401, if (reauth) "reauthorization_required" else null)
                    }
                    else -> throw CloudError("$method failed with HTTP ${response.code}", response.code)
                }
            }
        } catch (e: CloudError) {
            throw e
        } catch (e: ReauthRequired) {
            throw e
        } catch (e: Exception) {
            throw CloudError("$method could not reach the service or timed out")
        }
    }

    private fun recordPath(kind: String, recordId: String? = null): String {
        require(kind in KINDS) { "Unsupported record kind" }
        val path = "/kinds/$kind/record"
        if (recordId == null) return path
        require(recordId.isNotEmpty() && recordId != "." && recordId != "..") { "A valid cloud record ID is required" }
        return "$path/${URLEncoder.encode(recordId, "UTF-8").replace("+", "%20")}"
    }

    suspend fun check(): JsonObject = request("GET", "/about")

    suspend fun listPage(kind: String, cursor: String? = null, pageSize: Int = 20): Pair<List<CloudRecord>, String?> {
        require(pageSize in 1..100) { "Page size must be between 1 and 100" }
        val params = mutableMapOf("fields" to LIST_FIELDS, "pageSize" to pageSize.toString())
        if (cursor != null) params["cursor"] = cursor
        val result = request("GET", recordPath(kind), params)
        val rows = (result["records"] ?: result["recordList"])?.let {
            it as? kotlinx.serialization.json.JsonArray
        } ?: throw CloudError("List response did not contain the expected records")
        val records = rows.map { row ->
            var record = normalizeRecord(row.jsonObject)
            if (record.id.isEmpty()) throw CloudError("List response contained a record without an ID")
            if (record.attributes.isEmpty()) record = get(kind, record.id)
            record
        }
        return records to result["nextCursor"]?.jsonPrimitive?.content
    }

    fun normalizeRecord(row: JsonObject): CloudRecord {
        val recordId = row["recordID"]?.jsonPrimitive?.content ?: row["id"]?.jsonPrimitive?.content ?: ""
        var attrs = row["attributes"] as? JsonObject
        if (attrs == null) {
            // 部分响应把字段包在 fields 下，每个字段再包一层 {"value": ...}
            val fields = row["fields"] as? JsonObject
            attrs = fields?.let {
                buildJsonObject {
                    for ((k, v) in it) {
                        val inner = (v as? JsonObject)?.get("value")
                        put(k, inner ?: v)
                    }
                }
            } ?: JsonObject(emptyMap())
        }
        return CloudRecord(recordId, row["version"]?.jsonPrimitive?.int ?: 0, attrs)
    }

    suspend fun get(kind: String, recordId: String): CloudRecord {
        val result = request("GET", recordPath(kind, recordId), mapOf("fields" to RECORD_FIELDS))
        var record = result["record"] as? JsonObject ?: result
        val records = result["records"] as? kotlinx.serialization.json.JsonArray
        if (record == result && records != null && records.size == 1) {
            record = records[0].jsonObject
        }
        val normalized = normalizeRecord(record)
        if (normalized.id != recordId || normalized.attributes.isEmpty()) {
            throw CloudError("Get response did not contain the expected ID, version and attributes")
        }
        return normalized
    }

    suspend fun create(kind: String, attributes: Attrs): JsonObject {
        require(attributes.isNotEmpty()) { "Create requires a nonempty attributes object" }
        return request("POST", recordPath(kind), mapOf("fields" to RECORD_FIELDS), attributes)
    }

    suspend fun update(kind: String, recordId: String, baseversion: Int, changes: Attrs, reparent: Boolean = false): JsonObject =
        writeLock.withLock {
            val immutable = if (reparent && kind == "showTask") setOf("id2") else setOf("id1", "id2")
            if (changes.isEmpty() || changes.keys.any { it in immutable }
                || (kind == "favorites" && "id3" in changes)
                || (kind == "tomato" && "time" in changes)) {
                throw CloudError("Patch must be a nonempty JSON object without record identity fields")
            }
            val current = get(kind, recordId)
            if (current.version != baseversion) {
                throw CloudError("Record version changed; re-read before updating", 409)
            }
            val merged = buildJsonObject {
                for ((k, v) in current.attributes) put(k, v)
                for ((k, v) in changes) put(k, v)
            }
            request("PATCH", recordPath(kind, recordId), mapOf("baseversion" to baseversion.toString()), merged)
        }

    suspend fun delete(kind: String, recordId: String, baseversion: Int) {
        writeLock.withLock {
            val current = get(kind, recordId)
            if (current.version != baseversion) {
                throw CloudError("Record version changed; re-read before deleting", 409)
            }
            if (kind == "favorites" && current.attributes.str("id3") == "1") {
                throw CloudError("The default list cannot be deleted")
            }
            request("DELETE", recordPath(kind, recordId), mapOf("baseversion" to baseversion.toString()))
        }
    }
}
