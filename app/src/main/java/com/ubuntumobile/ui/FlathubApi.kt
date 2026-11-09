package com.ubuntumobile.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** An app as Flathub's website lists it. */
data class FlathubHit(
    val id: String,
    val name: String,
    val summary: String,
    val icon: String?,
    val developer: String = "",
    /** The processors it is built for ("aarch64", "x86_64"); empty when Flathub did not say. */
    val arches: List<String> = emptyList(),
) {
    /** Whether it can run on a phone with [arch]; unknown counts as yes. */
    fun runsOn(arch: String?): Boolean = arches.isEmpty() || (arch != null && arch in arches)
}

/** What the store's detail page shows. */
data class FlathubDetails(
    val description: String,
    val screenshots: List<String>,
    val homepage: String?,
    val license: String?,
)

/**
 * Flathub's own catalogue, the one flathub.org shows: every app with its
 * icon, sorted by what people install. Flatpak's appstream data would need
 * a download of its own inside the container, so the store asks the
 * website's API directly instead.
 */
object FlathubApi {
    private const val BASE = "https://flathub.org/api/v2"

    /** Flathub's main categories, with the names the store shows. */
    val categories = listOf(
        "game" to "Games",
        "office" to "Office",
        "graphics" to "Graphics",
        "audiovideo" to "Audio & video",
        "development" to "Development",
        "network" to "Internet",
        "education" to "Education",
        "science" to "Science",
        "utility" to "Utilities",
        "system" to "System",
    )

    /** Flathub's name for this phone's processor, or null when it builds nothing for it (32-bit ARM). */
    val deviceArch: String? by lazy {
        when (Build.SUPPORTED_ABIS?.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

    /** "popular", "trending", "recently-added" or "recently-updated", only apps for this phone. */
    suspend fun collection(name: String, count: Int = 30): List<FlathubHit> =
        hits(get("$BASE/collection/$name?page=1&per_page=${count * 2}"))
            .filter { it.runsOn(deviceArch) }
            .take(count)

    /** A category's apps for this phone, most installed first. */
    suspend fun category(category: String, page: Int = 1): List<FlathubHit> =
        search("", category = category, onlyThisPhone = true, page = page)

    /**
     * Search the whole catalogue, best match first. With [onlyThisPhone]
     * false, apps built only for other processors come too, in their place,
     * so someone looking for Steam finds it and reads why it cannot run.
     */
    suspend fun search(
        query: String,
        category: String? = null,
        onlyThisPhone: Boolean = false,
        page: Int = 1,
    ): List<FlathubHit> {
        val body = buildJsonObject {
            put("query", query)
            put("hitsPerPage", 40)
            put("page", page)
            put(
                "filters",
                buildJsonArray {
                    if (category != null) add(filter("main_categories", category))
                    if (onlyThisPhone) deviceArch?.let { add(filter("arches", it)) }
                },
            )
        }
        return hits(post("$BASE/search", body.toString()))
    }

    suspend fun details(id: String): FlathubDetails {
        val app = Json.parseToJsonElement(get("$BASE/appstream/$id")).jsonObject
        val shots = (app["screenshots"] as? JsonArray).orEmpty().mapNotNull { shot ->
            val sizes = (shot as? JsonObject)?.get("sizes") as? JsonArray ?: return@mapNotNull null
            // The 752-pixel-wide one: big enough for a phone, a fraction of the original.
            val picked = sizes.mapNotNull { it as? JsonObject }
                .minByOrNull { kotlin.math.abs((it.str("width")?.toIntOrNull() ?: 0) - 752) }
            picked?.str("src")
        }
        val urls = app["urls"] as? JsonObject
        return FlathubDetails(
            description = plainText(app.str("description").orEmpty()),
            screenshots = shots.take(6),
            homepage = urls?.str("homepage"),
            license = app.str("project_license"),
        )
    }

    private fun filter(type: String, value: String) = buildJsonObject {
        put("filterType", type)
        put("value", value)
    }

    private fun hits(json: String): List<FlathubHit> {
        val root = Json.parseToJsonElement(json).jsonObject
        return (root["hits"] as? JsonArray).orEmpty().mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            val id = hit.str("app_id") ?: return@mapNotNull null
            FlathubHit(
                id = id,
                name = hit.str("name") ?: id,
                summary = hit.str("summary").orEmpty(),
                icon = hit.str("icon"),
                developer = hit.str("developer_name").orEmpty(),
                arches = (hit["arches"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /** Appstream descriptions are a little HTML: paragraphs and lists. */
    internal fun plainText(html: String): String = html
        // Line breaks in the source mean nothing; the tags do.
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*<li>\\s*"), "\n• ")
        .replace(Regex("\\s*</(p|ul|ol)>\\s*"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private suspend fun get(url: String): String = request(url, null)

    private suspend fun post(url: String, body: String): String = request(url, body)

    private suspend fun request(url: String, body: String?): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) error("Flathub answered ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Icons and screenshots from Flathub, kept while the app runs. */
object WebImages {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String, maxWidth: Int = 256): ImageBitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                val bytes = try {
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
                    ?.also { cache.put(url, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}
