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
                if (res.code == 401 && !path.endsWith("/login")) {
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

    suspend fun login(server: String, password: String): LoginResponse =
        json.decodeFromString(raw("POST", "/api/login", buildJsonObject { put("password", password) }, server))

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
