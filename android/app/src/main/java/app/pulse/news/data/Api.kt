package app.pulse.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

/** Talks to the Pulse API (the same one the web app uses), authenticated with a bearer token. */
object Api {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val JSON = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(ResilientDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Called when the server says the session is gone (password changed, logged out). */
    @Volatile var onAuthLost: (() -> Unit)? = null

    suspend fun raw(method: String, path: String, body: JsonObject? = null, server: String = Store.server): String =
        withContext(Dispatchers.IO) {
            val base = server.trimEnd('/')
            val req = Request.Builder()
                .url(base + path)
                .header("X-Pulse-Client", "android")
                .apply { if (Store.token.isNotBlank()) header("Authorization", "Bearer ${Store.token}") }
                .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code == 401 && !path.endsWith("/login") && !path.endsWith("/password")) {
                    onAuthLost?.invoke()
                    throw ApiException(401, "Please log in again")
                }
                if (!res.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
                    throw ApiException(res.code, detail ?: "Server error ${res.code}")
                }
                text
            }
        }

    suspend inline fun <reified T> get(path: String): T = json.decodeFromString(raw("GET", path))
    suspend inline fun <reified T> post(path: String, body: JsonObject = JsonObject(emptyMap())): T = json.decodeFromString(raw("POST", path, body))
    suspend inline fun <reified T> put(path: String, body: JsonObject): T = json.decodeFromString(raw("PUT", path, body))
    suspend inline fun <reified T> patch(path: String, body: JsonObject): T = json.decodeFromString(raw("PATCH", path, body))
    suspend fun delete(path: String) { raw("DELETE", path) }

    fun q(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- typed helpers -------------------------------------------------------------------

    /** Sign in (or create an account when [create] is true). Returns the session token. */
    suspend fun login(server: String, username: String, password: String, create: Boolean = false): LoginResponse =
        json.decodeFromString(raw("POST", if (create) "/api/signup" else "/api/login",
            buildJsonObject { put("username", username); put("password", password) }, server))

    suspend fun feed(mode: String, exclude: Collection<Int>): Pair<FeedResponse, String> {
        val text = raw("GET", "/api/feed?mode=$mode&exclude=${exclude.joinToString(",")}")
        return json.decodeFromString<FeedResponse>(text) to text
    }

    suspend fun interact(
        storyId: Int,
        action: String,
        dwellMs: Long? = null,
        articleId: Int? = null,
        storyIds: List<Int>? = null,
    ): InteractResponse = post("/api/interact", buildJsonObject {
        put("story_id", storyId)
        put("action", action)
        if (dwellMs != null) put("dwell_ms", dwellMs)
        if (articleId != null) put("article_id", articleId)
        if (storyIds != null) putJsonArray("story_ids") { storyIds.forEach { add(it) } }
    })

    suspend fun undo(interactionId: Long): OkResponse = post("/api/undo", buildJsonObject { put("interaction_id", interactionId) })

    suspend fun addRule(label: String, kind: String, mode: String): OkResponse =
        post("/api/rules", buildJsonObject { put("label", label); put("kind", kind); put("mode", mode) })
}

/**
 * Some mobile networks fail to resolve certain hosts (e.g. *.vercel.app) at the DNS level, and the
 * phone's resolver can hang for many seconds before giving up. So: ask the phone's DNS, but if it
 * hasn't answered within 1.5 s (or fails), use Cloudflare / Google DNS-over-HTTPS. Hosts that needed
 * the fallback are remembered, so later lookups and launches go straight to DNS-over-HTTPS.
 */
object ResilientDns : okhttp3.Dns {
    private val bootstrap = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "dns").apply { isDaemon = true } }

    private val cloudflare by lazy {
        okhttp3.dnsoverhttps.DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(java.net.InetAddress.getByName("1.1.1.1"), java.net.InetAddress.getByName("1.0.0.1"))
            .build()
    }
    private val google by lazy {
        okhttp3.dnsoverhttps.DnsOverHttps.Builder().client(bootstrap)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(java.net.InetAddress.getByName("8.8.8.8"), java.net.InetAddress.getByName("8.8.4.4"))
            .build()
    }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<java.net.InetAddress>, Long>>()

    private fun viaDoh(hostname: String): List<java.net.InetAddress>? {
        for (resolver in listOf(cloudflare, google)) {
            val addrs = runCatching { resolver.lookup(hostname) }.getOrNull()
            if (!addrs.isNullOrEmpty()) {
                cache[hostname] = addrs to System.currentTimeMillis()
                return addrs
            }
        }
        return null
    }

    override fun lookup(hostname: String): List<java.net.InetAddress> {
        cache[hostname]?.let { (addrs, at) -> if (System.currentTimeMillis() - at < 30 * 60_000) return addrs }
        // this network is known to block the host: skip the slow system lookup
        if (hostname in Store.dohHosts) viaDoh(hostname)?.let { return it }

        val system = pool.submit<List<java.net.InetAddress>> { okhttp3.Dns.SYSTEM.lookup(hostname) }
        val fast = runCatching { system.get(1500, TimeUnit.MILLISECONDS) }.getOrNull()
        if (!fast.isNullOrEmpty()) return fast

        viaDoh(hostname)?.let { addrs ->
            Store.rememberDohHost(hostname)
            return addrs
        }
        // DoH failed too (offline?): give the system lookup a little longer
        runCatching { system.get(4, TimeUnit.SECONDS) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        throw java.net.UnknownHostException("Couldn't reach $hostname. Check your internet connection.")
    }
}
