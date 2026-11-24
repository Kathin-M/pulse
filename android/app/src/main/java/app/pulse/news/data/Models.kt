package app.pulse.news.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Card(
    val id: Int,
    val type: String = "story",
    val title: String = "",
    val summary: String? = null,
    val url: String = "",
    @SerialName("article_id") val articleId: Int = 0,
    val publisher: String = "",
    @SerialName("source_count") val sourceCount: Int = 1,
    @SerialName("first_seen") val firstSeen: String = "",
    @SerialName("last_updated") val lastUpdated: String = "",
    val topics: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val tier: String? = null,
    val image: String? = null,
    @SerialName("image_w") val imageW: Int? = null,
    @SerialName("image_h") val imageH: Int? = null,
    val lqip: String? = null,
    val layout: String = "text",
    @SerialName("thread_id") val threadId: Int? = null,
    val liked: Boolean = false,
    val saved: Boolean = false,
    val followed: Boolean = false,
    val badge: String? = null,
    val why: String = "",
    @SerialName("update_title") val updateTitle: String? = null,
    @SerialName("update_publisher") val updatePublisher: String? = null,
    @SerialName("thread_title") val threadTitle: String? = null,
    @SerialName("thread_count") val threadCount: Int? = null,
    @SerialName("story_ids") val storyIds: List<Int>? = null,
    val headlines: List<String>? = null,
    val `when`: String? = null,
    val status: String? = null,
) {
    /** Story id to open in detail (thread cards open their first story). */
    val storyIdForDetail: Int get() = if (id > 0) id else storyIds?.firstOrNull() ?: id
    val displayTitle: String get() = if (type == "thread") threadTitle ?: title else title
}

@Serializable
data class Ingest(val at: String? = null, val new: Int = 0, val seconds: Int = 0)

@Serializable
data class FeedResponse(
    val cards: List<Card> = emptyList(),
    val total: Int = 0,
    val mode: String = "foryou",
    val onboarded: Boolean = true,
    @SerialName("last_ingest") val lastIngest: Ingest? = null,
)

@Serializable
data class TimelineItem(val time: String, val title: String, val publisher: String = "")

@Serializable
data class SourceItem(
    @SerialName("article_id") val articleId: Int,
    val publisher: String,
    val title: String,
    val url: String,
    val time: String,
    val note: String? = null,
    @SerialName("has_body") val hasBody: Boolean = false,
    val kind: String? = null,
)

@Serializable
data class ThreadRef(val id: Int, val title: String, val count: Int)

@Serializable
data class StoryDetail(
    val card: Card? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val sources: List<SourceItem> = emptyList(),
    @SerialName("more_sources") val moreSources: List<SourceItem> = emptyList(),
    val explainers: List<SourceItem> = emptyList(),
    val thread: ThreadRef? = null,
    val followed: Boolean = false,
    val liked: Boolean = false,
    val saved: Boolean = false,
    @SerialName("article_count") val articleCount: Int = 0,
)

@Serializable
data class ReaderArticle(
    val title: String,
    val publisher: String,
    val url: String,
    val image: String? = null,
    @SerialName("published_at") val publishedAt: String,
    val paragraphs: List<String> = emptyList(),
    val summary: String? = null,
    @SerialName("has_body") val hasBody: Boolean = false,
)

@Serializable
data class ThreadResponse(val title: String, val cards: List<Card> = emptyList())

@Serializable
data class SeenResponse(
    val cards: List<Card> = emptyList(),
    val topics: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class CardsResponse(val cards: List<Card> = emptyList())

@Serializable
data class Prefs(
    val onboarded: Boolean = false,
    val city: String = "Bengaluru",
    val state: String = "Karnataka",
    val tiers: Map<String, Float> = emptyMap(),
    val discovery: Float = 0.1f,
    @SerialName("paused_topics") val pausedTopics: List<String> = emptyList(),
)

@Serializable
data class Rule(val id: Int, val label: String, val kind: String, val mode: String, val weight: Float = 1f)

@Serializable
data class Source(
    val id: Int,
    val name: String,
    val url: String,
    val kind: String,
    val trust: Float = 0.6f,
    val enabled: Boolean = true,
    val error: String? = null,
    val dead: Boolean = false,
    @SerialName("articles_3d") val articles3d: Int = 0,
)

@Serializable
data class City(val name: String, val state: String)

@Serializable
data class Controls(
    val prefs: Prefs = Prefs(),
    val rules: List<Rule> = emptyList(),
    val sources: List<Source> = emptyList(),
    val topics: List<String> = emptyList(),
    val cities: List<City> = emptyList(),
    @SerialName("last_ingest") val lastIngest: Ingest? = null,
    val username: String? = null,
)

@Serializable
data class AppLinks(val releases: String? = null)

@Serializable
data class Cluster(
    val id: Int,
    val label: String,
    val polarity: Int,
    val weight: Float,
    val strength: Float,
    val signals: Int = 1,
)

@Serializable
data class Insights(
    val clusters: List<Cluster> = emptyList(),
    @SerialName("paused_topics") val pausedTopics: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val interactions: Int = 0,
)

@Serializable
data class InteractResponse(val ok: Boolean = true, @SerialName("interaction_id") val interactionId: Long = 0, val weight: Float = 0f)

@Serializable
data class LoginResponse(val ok: Boolean = true, val token: String? = null, val username: String? = null)

@Serializable
data class SessionSummary(val note: String? = null, val interactions: Int = 0)

@Serializable
data class OkResponse(val ok: Boolean = true, val removed: Int = 0, val replayed: Int = 0)
