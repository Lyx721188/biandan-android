package com.biandan.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * 华为授权 + 便单续期服务。对应 biandan_cloud.py 的 OAuth 流程和
 * biandan_renewal.py 的 RenewingAuthorization（device_gate 模式）。
 *
 * 授权链路：Custom Tab 打开华为授权页 → 官网中转 → 回跳本机
 * http://127.0.0.1:42681/biandan/callback → code 换 token → 续期服务
 * 换 device_grant 与访问令牌。
 */
class AuthManager(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) : AccessTokenProvider {

    companion object {
        const val OAUTH_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3"
        const val WEB_CLIENT_ID = "5765880207852987515"
        const val WEB_REDIRECT_URI = "https://www.biandangroup.com/#/biandan_online"
        const val RELAY_CALLBACK = "http://127.0.0.1:42681/biandan/callback"
        const val RELAY_PORT = 42681
        const val RELAY_STATE_PREFIX = "biandan-local-v1."
        const val RENEWAL_URL = "https://auth.biandangroup.com/v1/refresh"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val random = SecureRandom()
    }

    private val storeFile = File(context.filesDir, "biandan-auth.json")
    private val lock = Mutex()

    private var refreshToken: String? = null
    private var deviceGrant: String? = null
    private var token: String? = null
    private var expiresAtMs: Long = 0

    init {
        loadStore()
    }

    val isSignedIn: Boolean get() = refreshToken != null || loadStore()

    // ---------- 凭据存取（应用私有目录，对应参考实现的本机状态文件） ----------

    @Synchronized
    private fun loadStore(): Boolean {
        if (refreshToken != null) return true
        val data = runCatching {
            BiandanCloud.json.parseToJsonElement(storeFile.readText()).jsonObject
        }.getOrNull() ?: return false
        refreshToken = data["refresh_token"]?.jsonPrimitive?.content
        deviceGrant = data["device_grant"]?.jsonPrimitive?.content
        return refreshToken != null
    }

    @Synchronized
    private fun saveStore() {
        val body = buildJsonObject {
            put("refresh_token", refreshToken)
            put("device_grant", deviceGrant)
        }.toString()
        val tmp = File(storeFile.parent, ".auth-tmp")
        tmp.writeText(body)
        tmp.renameTo(storeFile)
    }

    @Synchronized
    private fun clearStore() {
        refreshToken = null
        deviceGrant = null
        token = null
        expiresAtMs = 0
        storeFile.delete()
    }

    fun signOut() = clearStore()

    // ---------- OAuth ----------

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return android.util.Base64.encodeToString(
            buf, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(
            digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun authorizeUrl(state: String, challenge: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to WEB_CLIENT_ID,
            "redirect_uri" to WEB_REDIRECT_URI,
            "scope" to "openid profile",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
        )
        return "$OAUTH_URL/authorize?" + params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    /** 本机回环监听，等官网中转把 code+state 送回来。对应 receive_loopback_code。 */
    private class LoopbackCallback(private val expectedState: String) {
        private var server: ServerSocket? = null
        var code: String? = null
        var error: String? = null

        suspend fun waitForCode(timeoutMs: Long = 180_000): String = withContext(Dispatchers.IO) {
            val socket = ServerSocket(RELAY_PORT, 8, InetAddress.getByName("127.0.0.1"))
            server = socket
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (code == null && error == null) {
                    if (System.currentTimeMillis() >= deadline) throw CloudError("等待华为授权超时")
                    if (!coroutineContext.isActive) throw CancellationException()
                    socket.soTimeout = 3000
                    val conn = try { socket.accept() } catch (e: java.net.SocketTimeoutException) { continue }
                    conn.use { c ->
                        c.soTimeout = 3000
                        val input = c.getInputStream().bufferedReader()
                        val requestLine = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            val i = line.indexOf(':')
                            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                        }
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        val host = headers["host"] ?: ""
                        val response = try {
                            handle(path, host)
                        } catch (e: AuthorizationDenied) {
                            error = e.message; "HTTP/1.1 400 Bad Request"
                        } catch (e: Exception) {
                            error = e.message; "HTTP/1.1 400 Bad Request"
                        }
                        val body = if (response.startsWith("HTTP/1.1 200"))
                            "Biandan received the authorization callback. You can close this page."
                        else "Authorization failed. You can close this page and retry."
                        val out = c.getOutputStream()
                        val payload = body.toByteArray(Charsets.UTF_8)
                        out.write(
                            ("$response\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                                "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray()
                        )
                        out.write(payload)
                        out.flush()
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
            error?.let { throw CloudError(it) }
            code ?: throw CloudError("没有收到授权回调")
        }

        private fun handle(path: String, host: String): String {
            val uri = java.net.URI("http://$host$path")
            if (host != "127.0.0.1:$RELAY_PORT" || uri.path != "/biandan/callback") {
                throw CloudError("OAuth callback does not match the registered callback")
            }
            // state/code 可能在 query，也可能由官网经 fragment query 转发
            val params = mutableMapOf<String, MutableList<String>>()
            fun collect(rawQuery: String?) {
                rawQuery?.split("&")?.forEach { pair ->
                    val i = pair.indexOf('=')
                    if (i > 0) {
                        val k = URLDecoder.decode(pair.substring(0, i), "UTF-8")
                        val v = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                        params.getOrPut(k) { mutableListOf() }.add(v)
                    }
                }
            }
            collect(uri.rawQuery)
            uri.rawFragment?.let { frag ->
                collect(frag.substringAfter('?', "").ifBlank { null })
            }
            val states = params["state"].orEmpty()
            if (states.size != 1 || !MessageDigest.isEqual(
                    states[0].toByteArray(), expectedState.toByteArray())) {
                throw CloudError("OAuth state did not match this login")
            }
            if (params.containsKey("error")) {
                throw AuthorizationDenied("Huawei did not grant authorization")
            }
            val codes = params["code"].orEmpty()
            if (codes.size != 1 || codes[0].isEmpty()) {
                throw CloudError("Authorization code missing from OAuth callback")
            }
            code = codes[0]
            return "HTTP/1.1 200 OK"
        }

        fun close() = runCatching { server?.close() }
    }

    private class AuthorizationDenied(message: String) : Exception(message)

    /** 交互式登录：打开系统浏览器/自定义标签完成华为授权。 */
    suspend fun signIn(activityContext: Context): Unit = coroutineScope {
        val verifier = randomUrlSafe(48)
        val state = RELAY_STATE_PREFIX + randomUrlSafe(32)
        val challenge = pkceChallenge(verifier)
        val url = authorizeUrl(state, challenge)

        val callback = LoopbackCallback(state)
        val waitJob = async { callback.waitForCode() }

        try {
            CustomTabsIntent.Builder().build().launchUrl(activityContext, Uri.parse(url))
        } catch (e: Exception) {
            try {
                activityContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                callback.close()
                throw CloudError("无法打开浏览器完成华为授权")
            }
        }

        val code = try {
            waitJob.await()
        } catch (e: CancellationException) {
            callback.close()
            throw e
        } catch (e: Exception) {
            callback.close()
            throw e
        }

        // code 换令牌：redirect_uri 仍填官网登记地址
        val reply = postForm(
            "$OAUTH_URL/token",
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", WEB_CLIENT_ID)
                .add("redirect_uri", WEB_REDIRECT_URI)
                .add("code", code)
                .add("code_verifier", verifier)
                .build()
        )
        val newRefresh = reply["refresh_token"]?.jsonPrimitive?.content
            ?: throw CloudError("Huawei returned no refresh token")
        val access = reply["access_token"]?.jsonPrimitive?.content
            ?: throw CloudError("Huawei returned no access token")
        val expires = reply["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        lock.withLock {
            refreshToken = newRefresh
            token = access
            expiresAtMs = System.currentTimeMillis() + expires * 1000 - minOf(60_000, expires * 100)
            saveStore()
        }
        // 首次登记设备凭据
        ensureDeviceGrant()
    }

    private suspend fun postForm(url: String, body: FormBody): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body).build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw CloudError("Token exchange failed with HTTP ${response.code}", response.code)
            BiandanCloud.json.parseToJsonElement(raw).jsonObject
        }
    }

    private suspend fun postJson(url: String, body: JsonObject, bearer: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            http.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) {
                    val failure = runCatching { BiandanCloud.json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    val code = failure?.get("error")?.jsonPrimitive?.content
                    throw CloudError("POST failed with HTTP 401", 401, code)
                }
                if (!response.isSuccessful) throw CloudError("POST failed with HTTP ${response.code}", response.code)
                BiandanCloud.json.parseToJsonElement(raw).jsonObject
            }
        }

    private fun acceptRenewal(reply: JsonObject) {
        val access = reply["access_token"]?.jsonPrimitive?.content
        val expires = reply["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: reply["expires_in"]?.jsonPrimitive?.let { it.content.toLongOrNull() }
        if (access.isNullOrEmpty() || expires == null || expires <= 0) {
            throw CloudError("The Biandan renewal service returned unusable tokens")
        }
        val grant = reply["device_grant"]?.jsonPrimitive?.content
        if (grant.isNullOrEmpty()) {
            throw CloudError("The Biandan renewal service did not issue a device credential")
        }
        reply["refresh_token"]?.jsonPrimitive?.content?.let { refreshToken = it }
        deviceGrant = grant
        token = access
        expiresAtMs = System.currentTimeMillis() + expires * 1000 - minOf(60_000, expires * 100)
        saveStore()
    }

    private suspend fun ensureDeviceGrant() {
        val rt = refreshToken ?: throw CloudError("No refresh token")
        val reply = postJson(
            RENEWAL_URL.removeSuffix("/v1/refresh") + "/v1/device",
            buildJsonObject { put("refresh_token", rt) }
        )
        lock.withLock { acceptRenewal(reply) }
    }

    /**
     * AccessTokenProvider 实现：返回有效访问令牌，必要时经续期服务刷新。
     * rejected = 刚被同步接口拒绝的令牌，强制轮换。
     */
    override suspend fun accessToken(rejected: String?): String = lock.withLock {
        loadStore()
        val current = token
        if (current != null && current != rejected && System.currentTimeMillis() < expiresAtMs) {
            return current
        }
        val rt = refreshToken ?: throw ReauthRequired("需要先完成华为授权")
        val grant = deviceGrant
        val endpoint = if (grant != null) RENEWAL_URL else RENEWAL_URL.removeSuffix("/v1/refresh") + "/v1/device"
        val reply = try {
            postJson(endpoint, buildJsonObject { put("refresh_token", rt) }, bearer = grant)
        } catch (e: CloudError) {
            if (e.status == 401 && e.code == "reauthorization_required") {
                clearStore()
                throw ReauthRequired("华为授权已过期或被撤销，请重新授权")
            }
            throw CloudError("The Biandan renewal service is temporarily unavailable. Retry later.")
        }
        acceptRenewal(reply)
        return token ?: throw CloudError("Renewal did not return an access token")
    }
}
