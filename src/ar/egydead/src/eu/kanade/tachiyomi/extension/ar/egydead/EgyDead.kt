package eu.kanade.tachiyomi.extension.ar.egydead

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EgyDead : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EgyDead"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)!!
            .trim()
            .trimEnd('/')

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeSelector() = itemSelector()

    override fun popularAnimeFromElement(element: Element) = animeFromElement(element)

    override fun popularAnimeNextPageSelector() = nextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = itemSelector()

    override fun latestUpdatesFromElement(element: Element) = animeFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = itemSelector()

    override fun searchAnimeFromElement(element: Element) = animeFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1, .title, .post-title, .entry-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".poster img, .image img, img")?.absUrl("src")
            description = document.selectFirst(".story, .description, .desc, .content, .entry-content")?.text()?.trim()
            genre = document.select(".genre a, .genres a, a[href*=genre], a[href*=category]")
                .joinToString { it.text().trim() }
        }
    }

    override fun episodeListSelector() =
        "a[href*=watch], a[href*=episode], a[href*=حلقة], .episodes a, .episode a, .watch-btn, .btn-watch"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val href = element.absUrl("href")
            setUrlWithoutDomain(href.ifBlank { element.attr("href") })
            name = element.text().trim().ifBlank { "Watch" }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url.fullUrl(), headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        document.select("video source[src], video[src], source[src]").forEach { element ->
            val url = element.absUrl("src")
            if (url.isVideoUrl()) videos += Video(url, qualityFromUrl(url), url, headers)
        }

        document.select("iframe[src]").forEach { element ->
            val iframeUrl = element.absUrl("src")
            if (iframeUrl.isNotBlank()) {
                videos += Video(iframeUrl, "External server", iframeUrl, headers)
            }
        }

        VIDEO_REGEX.findAll(document.html()).forEach { match ->
            val url = match.value.replace("\\/", "/")
            if (url.isVideoUrl()) videos += Video(url, qualityFromUrl(url), url, headers)
        }

        return videos.distinctBy { it.videoUrl }
    }

    override fun List<Video>.sort(): List<Video> = sortedByDescending { it.quality }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "Base URL"
            summary = "Current: %s"
            dialogTitle = "EgyDead Base URL"
            dialogMessage = "Example: https://tv10.egydead.live/h3/"
            setDefaultValue(DEFAULT_BASE_URL)
        }.also(screen::addPreference)
    }

    private fun itemSelector() =
        ".movie, .post, .item, .block, article, .content-box, .GridItem, a[href]"

    private fun animeFromElement(element: Element): SAnime {
        val link = if (element.`is`("a[href]")) element else element.selectFirst("a[href]") ?: element
        val image = element.selectFirst("img")

        return SAnime.create().apply {
            title = image?.attr("alt")?.trim()
                ?.ifBlank { null }
                ?: link.attr("title").trim().ifBlank { link.text().trim() }
            thumbnail_url = image?.absUrl("data-src")?.ifBlank { image.absUrl("src") }
            setUrlWithoutDomain(link.absUrl("href"))
        }
    }

    private fun nextPageSelector() =
        "a[rel=next], .pagination a.next, .page-numbers.next"

    private fun String.fullUrl(): String =
        when {
            startsWith("http") -> this
            startsWith("/") -> baseUrl.toHttpUrl().newBuilder().encodedPath(this).build().toString()
            else -> "$baseUrl/$this"
        }

    private fun String.isVideoUrl(): Boolean =
        contains(".m3u8") || contains(".mp4") || contains(".mkv")

    private fun qualityFromUrl(url: String): String {
        return QUALITY_REGEX.find(url)?.value ?: "Video"
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://tv10.egydead.live/h3/"
        private const val PREF_BASE_URL = "base_url"
        private val VIDEO_REGEX = Regex("""https?:[^"'\\\s]+(?:m3u8|mp4|mkv)[^"'\\\s]*""")
        private val QUALITY_REGEX = Regex("""\d{3,4}p""")
    }
}
