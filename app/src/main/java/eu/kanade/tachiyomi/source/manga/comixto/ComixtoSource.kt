package eu.kanade.tachiyomi.source.manga.comixto

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class ComixtoSource : HttpSource() {

    override val name = "Comix.to"
    override val baseUrl = "https://comix.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        add("Referer", baseUrl)
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    }

    // ============================================================================================
    // POPULAR
    // ============================================================================================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/browser?sort=total_views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseBrowsePage(response.body.string())

    // ============================================================================================
    // LATEST
    // ============================================================================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browser?sort=updated_at&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseBrowsePage(response.body.string())

    // ============================================================================================
    // SEARCH
    // ============================================================================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/browser?q=${query.trim()}&page=$page"
        } else {
            "$baseUrl/browser?sort=updated_at&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseBrowsePage(response.body.string())

    // ============================================================================================
    // BROWSE PAGE PARSER (shared by popular / latest / search)
    // ============================================================================================

    private fun parseBrowsePage(body: String): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seen = mutableSetOf<String>()

        // The site embeds manga data in Next.js RSC payloads as JSON-like fragments.
        // Match the repeating manga object pattern: hash_id, title, slug, thumbnail.
        val pattern = Regex(
            """"hash_id":"([^"]+)","title":"([^"]+)"(?:,"alt_titles":\[[^\]]*\])?,"synopsis":"[^"]*","slug":"([^"]+)"[^}]{0,400}?"poster":\{"small":"([^"]+)"""",
        )

        for (match in pattern.findAll(body)) {
            val hashId = match.groupValues[1]
            if (!seen.add(hashId)) continue

            mangas += SManga.create().apply {
                url = "/$hashId-${match.groupValues[3]}"
                title = unescapeJson(match.groupValues[2])
                thumbnail_url = match.groupValues[4]
            }
        }

        // Assume next page exists if we found a full page worth of results
        return MangasPage(mangas, hasNextPage = mangas.size >= 20)
    }

    // ============================================================================================
    // MANGA DETAILS
    // ============================================================================================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        return SManga.create().apply {
            title = jsonField(body, "title") ?: ""
            description = jsonField(body, "synopsis")?.unescapeNewlines()

            author = Regex(""""authors":\["([^"]+)"""").find(body)?.groupValues?.get(1)
            artist = Regex(""""artists":\["([^"]+)"""").find(body)?.groupValues?.get(1)

            val genres = Regex(""""genres":\[([^\]]+)\]""").find(body)
                ?.groupValues?.get(1)?.parseStringArray() ?: emptyList()
            val themes = Regex(""""themes":\[([^\]]+)\]""").find(body)
                ?.groupValues?.get(1)?.parseStringArray() ?: emptyList()
            val demographic = jsonField(body, "demographic")
            genre = (genres + themes + listOfNotNull(demographic))
                .filter { it.isNotBlank() }
                .joinToString(", ")

            status = when (jsonField(body, "status")?.lowercase()) {
                "releasing" -> SManga.ONGOING
                "finished" -> SManga.COMPLETED
                "on hiatus" -> SManga.ON_HIATUS
                "discontinued" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            thumbnail_url = Regex(""""large":"(https://static\.comix\.to/[^"]+)"""").find(body)
                ?.groupValues?.get(1)

            initialized = true
        }
    }

    // ============================================================================================
    // CHAPTER LIST  — uses the clean JSON API
    // ============================================================================================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        // manga.url = "/{hashId}-{slug}"  e.g. "/87dld-dumped-by-girlfriend..."
        val hashId = manga.url.removePrefix("/").substringBefore("-")
        val mangaPath = manga.url.removePrefix("/") // "hashId-slug"

        val response = client.newCall(
            GET("$baseUrl/api/v2/manga/$hashId/chapters?limit=500&page=1&order[number]=desc", headers),
        ).execute()

        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val result = root["result"]?.jsonArray ?: return emptyList()

        return result.mapNotNull { el ->
            val ch = el.jsonObject
            val chapterId = ch["chapter_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val number = ch["number"]?.jsonPrimitive?.intOrNull ?: 0
            val chName = ch["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val createdAt = ch["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
            val group = ch["scanlation_group"]?.jsonObject
                ?.get("name")?.jsonPrimitive?.content

            // Build URL: /{mangaPath}/{chapterId}-chapter-{number}
            val chapterSlug = buildString {
                append("chapter-$number")
                if (!chName.isNullOrBlank()) {
                    append("-")
                    append(chName.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
                }
            }

            SChapter.create().apply {
                url = "/$mangaPath/$chapterId-$chapterSlug"
                name = if (!chName.isNullOrBlank()) "Chapter $number: $chName" else "Chapter $number"
                chapter_number = number.toFloat()
                date_upload = createdAt * 1000L
                scanlator = group
            }
        }
    }

    // Required by abstract class — not called since we override getChapterList directly
    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    override fun chapterPageParse(response: Response): SChapter =
        throw UnsupportedOperationException("Not used")

    // ============================================================================================
    // PAGE LIST  — parse image URLs from the chapter's RSC response
    // ============================================================================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        // Chapter images are served from external CDNs under /ii/{token}/{num}.webp
        // Example: https://j24n.wowpic3.store/ii/bEqPbYfoNT0/01.webp
        val imagePattern = Regex(
            """https://[a-z0-9]+\.[a-z0-9]+\.[a-z]{2,6}/ii/[A-Za-z0-9_\-]+/(\d+)\.(webp|jpg|jpeg|png)""",
        )

        return imagePattern.findAll(body)
            .map { it.value }
            .distinct()
            .sortedBy { url ->
                url.substringAfterLast("/").substringBefore(".").toIntOrNull() ?: 0
            }
            .mapIndexed { index, url -> Page(index, "", url) }
            .toList()
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("imageUrl is set directly in pageListParse")

    // ============================================================================================
    // HELPERS
    // ============================================================================================

    /** Extracts a simple string value for a JSON key from raw page body text. */
    private fun jsonField(body: String, key: String): String? {
        return Regex(""""${Regex.escape(key)}":"([^"]*)"""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.let { unescapeJson(it) }
    }

    private fun unescapeJson(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\\\", "\\")

    private fun String.unescapeNewlines(): String = this
        .replace("\\n", "\n")
        .replace("\\r", "")

    /** Parse a JSON string array like ["Action","Comedy"] into a list of strings. */
    private fun String.parseStringArray(): List<String> =
        Regex(""""([^"]+)"""").findAll(this).map { it.groupValues[1] }.toList()
}
