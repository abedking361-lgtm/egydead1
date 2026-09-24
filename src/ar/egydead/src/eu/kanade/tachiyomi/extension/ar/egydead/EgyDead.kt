package eu.kanade.tachiyomi.animeextension.ar.egydead

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EgyDead :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Egy Dead"

    private val preferences by getPreferencesLazy()

    private val configuredUrl: String
        get() = preferences.getString(PREF_BASE_URL, DEFAULT_SITE_URL)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: DEFAULT_SITE_URL

    private val homeUrl: String
        get() = configuredUrl

    override val baseUrl: String
        get() = ORIGIN_REGEX.find(configuredUrl)?.groupValues?.get(1) ?: DEFAULT_ORIGIN

    override val lang = "ar"

    override val supportsLatest = true

    override fun popularAnimeSelector(): String = "div.pin-posts-list li.movieItem"

    override fun popularAnimeNextPageSelector(): String = "div.whatever"

    override fun popularAnimeRequest(page: Int): Request = GET(homeUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.attr("href"))
            episode.name = element.attr("title")
            return episode
        }

        fun addEpisodes(res: Response, final: Boolean = false) {
            val document = res.useAsJsoup()
            val url = res.request.url.toString()

            if (final) {
                document.select(episodeListSelector()).forEach {
                    val episode = episodeFromElement(it)
                    val season = document.select("div.infoBox div.singleTitle").text()
                    val seasonTxt = season.substringAfter("الموسم ").substringBefore(" ")

                    episode.name =
                        if (season.contains("موسم")) {
                            "الموسم $seasonTxt ${episode.name}"
                        } else {
                            episode.name
                        }

                    episodes.add(episode)
                }
            } else if (url.contains("assembly")) {
                document.select("div.salery-list li.movieItem a").forEach {
                    episodes.add(episodeExtract(it))
                }
            } else if (url.contains("serie") || url.contains("season")) {
                if (document.select("div.seasons-list li.movieItem a").isEmpty()) {
                    document.select(episodeListSelector()).forEach {
                        episodes.add(episodeFromElement(it))
                    }
                } else {
                    document.select("div.seasons-list li.movieItem a").forEach {
                        addEpisodes(
                            client.newCall(
                                GET(it.attr("href"), headers),
                            ).execute(),
                            true,
                        )
                    }
                }
            } else if (url.contains("episode")) {
                document.selectFirst("#breadcrumbs li a[itemprop=url]")?.let {
                    addEpisodes(
                        client.newCall(
                            GET(it.attr("href"), headers),
                        ).execute(),
                    )
                }
            } else {
                val episode = SEpisode.create()
                episode.name = "مشاهدة"
                episode.setUrlWithoutDomain(url)
                episodes.add(episode)
            }
        }

        addEpisodes(response)

        return episodes
    }

    override fun episodeListSelector() = "div.EpsList li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()

        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.select("a").text()

        episode.episode_number =
            element.select("a")
                .text()
                .filter { it.isDigit() }
                .toFloatOrNull()
                ?: 0f

        return episode
    }

    private val streamWishExtractor by lazy {
        StreamWishExtractor(client, headers)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val requestBody =
            FormBody.Builder()
                .add("View", "1")
                .build()

        val episodeUrl = episode.url.toAbsoluteUrl()

        val document =
            client.newCall(
                POST(
                    episodeUrl,
                    headers,
                    requestBody,
                ),
            )
                .await()
                .useAsJsoup()

        return document
            .select(videoListSelector())
            .parallelCatchingFlatMap {
                val url =
                    it.attr("data-link").ifBlank {
                        it.selectFirst("a[href]")
                            ?.attr("href")
                            .orEmpty()
                    }

                if (url.isBlank()) {
                    emptyList()
                } else {
                    extractVideos(url)
                }
            }
            .distinctBy { it.videoUrl }
    }

    private suspend fun extractVideos(url: String): List<Video> =
        when {
            url.contains(".m3u8", true) || url.contains(".mp4", true) -> {
                listOf(
                    Video(
                        url,
                        "Direct",
                        url,
                    ),
                )
            }

            DOOD_REGEX.containsMatchIn(url) -> {
                DoodExtractor(client)
                    .videoFromUrl(
                        url,
                        "Dood mirror",
                    )
                    ?.let(::listOf)
            }

            url.contains("mdbekjwqa") || url.contains("mixdrop", true) -> {
                MixDropExtractor(client)
                    .videoFromUrl(url)
            }

            url.contains("ahvsh") -> {
                extractSourcesScript(
                    url,
                    "StreamHide",
                )
            }

            STREAMWISH_REGEX.containsMatchIn(url) ||
                url.contains("streamwish", true) -> {
                streamWishExtractor.videosFromUrl(url)
            }

            url.contains("fanakishtuna") -> {
                extractSourcesScript(
                    url,
                    "Mirror",
                )
            }

            url.contains("uqload", true) -> {
                val newUrl =
                    url.replace(
                        "https://uqload.co/",
                        "https://www.uqload.co/",
                    )

                val request =
                    client.newCall(
                        GET(newUrl, headers),
                    )
                        .awaitSuccess()
                        .useAsJsoup()

                val data =
                    request
                        .selectFirst("script:containsData(sources)")
                        ?.data()
                        .orEmpty()

                val streamLink =
                    data
                        .substringAfter("sources: [\"")
                        .substringBefore("\"]")

                if (streamLink.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        Video(
                            streamLink,
                            "Uqload: Mirror",
                            streamLink,
                        ),
                    )
                }
            }

            else -> {
                extractGeneric(url)
            }
        } ?: emptyList()

    private suspend fun extractSourcesScript(
        url: String,
        label: String,
    ): List<Video> {
        val request =
            client.newCall(
                GET(url, headers),
            )
                .awaitSuccess()
                .useAsJsoup()

        val script =
            request
                .selectFirst("script:containsData(sources)")
                ?.data()
                .orEmpty()

        val streamLink =
            SOURCES_REGEX
                .find(script)
                ?.groupValues
                ?.get(1)
                .orEmpty()

        return if (streamLink.isBlank()) {
            emptyList()
        } else {
            listOf(
                Video(
                    streamLink,
                    "$label: High Quality",
                    streamLink,
                ),
            )
        }
    }

    private suspend fun extractGeneric(url: String): List<Video> {
        try {
            val streamWish =
                streamWishExtractor.videosFromUrl(url)

            if (streamWish.isNotEmpty()) {
                return streamWish
            }
        } catch (_: Throwable) {
        }

        return try {
            val response =
                client.newCall(
                    GET(url, headers),
                ).awaitSuccess()

            val html =
                response.body
                    .string()
                    .replace("\\/", "/")

            VIDEO_URL_REGEX
                .findAll(html)
                .map { it.value }
                .distinct()
                .map {
                    Video(
                        it,
                        "Mirror",
                        it,
                    )
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun videoListSelector() =
        "ul.serversList li, .serversList li, li[data-link]"

    override fun videoFromElement(element: Element) =
        throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality =
            preferences.getString(
                PREF_QUALITY,
                "1080",
            )

        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0

            for (video in this) {
                if (video.videoTitle.contains(quality, true)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }

            return newList
        }

        return this
    }

    override fun searchAnimeNextPageSelector(): String =
        "div.pagination-two a:contains(›), a.next"

    override fun searchAnimeSelector(): String =
        "div.catHolder li.movieItem, li.movieItem"

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url =
            if (query.isNotBlank()) {
                "$baseUrl/page/$page/?s=$query"
            } else {
                val url = homeUrl

                (if (filters.isEmpty()) getFilterList() else filters)
                    .forEach { filter ->
                        when (filter) {
                            is CategoryList -> {
                                if (filter.state > 0) {
                                    val catQ =
                                        getCategoryList()[filter.state].query

                                    val catUrl =
                                        "$baseUrl/$catQ/?page=$page/"

                                    return GET(
                                        catUrl,
                                        headers,
                                    )
                                }
                            }

                            else -> {}
                        }
                    }

                return GET(
                    url,
                    headers,
                )
            }

        return GET(
            url,
            headers,
        )
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(
            element.select("a").attr("href"),
        )

        anime.title =
            element.select("h1.BottomTitle").text()

        anime.thumbnail_url =
            element.select("a img").attr("src")

        return anime
    }

    override fun getFilterList() =
        AnimeFilterList(
            CategoryList(categoriesName),
        )

    private class CategoryList(
        categories: Array<String>,
    ) : AnimeFilter.Select<String>(
            "الأقسام",
            categories,
        )

    private data class CatUnit(
        val name: String,
        val query: String,
    )

    private val categoriesName =
        getCategoryList()
            .map { it.name }
            .toTypedArray()

    private fun getCategoryList() =
        listOf(
            CatUnit("اختر القسم", ""),
            CatUnit("افلام اجنبى", "category/افلام-اجنبي"),
            CatUnit("افلام اسلام الجيزاوى", "category/ترجمات-اسلام-الجيزاوي"),
            CatUnit("افلام انمى", "category/افلام-كرتون"),
            CatUnit("افلام تركيه", "category/افلام-تركية"),
            CatUnit("افلام اسيويه", "category/افلام-اسيوية"),
            CatUnit("افلام مدبلجة", "category/افلام-اجنبية-مدبلجة"),
            CatUnit("سلاسل افلام", "assembly"),
            CatUnit("مسلسلات اجنبية", "series-category/مسلسلات-اجنبي"),
            CatUnit("مسلسلات انمى", "series-category/مسلسلات-انمي"),
            CatUnit("مسلسلات تركية", "series-category/مسلسلات-تركية"),
            CatUnit("مسلسلات اسيوية", "series-category/مسلسلات-اسيوية"),
            CatUnit("مسلسلات لاتينية", "series-category/مسلسلات-لاتينية"),
            CatUnit("المسلسلات الكاملة", "serie"),
            CatUnit("المواسم الكاملة", "season"),
        )

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.thumbnail_url =
            document
                .select("div.single-thumbnail img")
                .attr("src")

        anime.title =
            document
                .select("div.infoBox div.singleTitle")
                .text()

        anime.author =
            document
                .select("div.LeftBox li:contains(البلد) a")
                .text()

        anime.artist =
            document
                .select("div.LeftBox li:contains(القسم) a")
                .text()

        anime.genre =
            document
                .select(
                    "div.LeftBox li:contains(النوع) a, " +
                        "div.LeftBox li:contains(اللغه) a, " +
                        "div.LeftBox li:contains(السنه) a",
                )
                .joinToString(", ") {
                    it.text()
                }

        anime.description =
            document
                .select("div.infoBox div.extra-content p")
                .text()

        anime.status =
            if (
                anime.title.contains("كامل") ||
                anime.title.contains("فيلم")
            ) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }

        return anime
    }

    override fun latestUpdatesSelector(): String =
        "section.main-section li.movieItem, li.movieItem"

    override fun latestUpdatesNextPageSelector(): String =
        "div.pagination ul.page-numbers li a.next, a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET(if (page <= 1) homeUrl else "$baseUrl/?page=$page", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(
            element.select("a").attr("href"),
        )

        anime.title =
            element.select("h1.BottomTitle").text()

        anime.thumbnail_url =
            element.select("a img").attr("src")

        return anime
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref =
            EditTextPreference(screen.context).apply {
                key = PREF_BASE_URL
                title = "رابط موقع EgyDead"
                summary = "الرابط الحالي: %s"
                dialogTitle = "تغيير رابط الموقع"
                dialogMessage =
                    "الصق رابط الموقع الحالي كاملاً. مثال: https://tv10.egydead.live/h3/"
                setDefaultValue(DEFAULT_SITE_URL)
            }

        screen.addPreference(baseUrlPref)

        val videoQualityPref =
            ListPreference(screen.context).apply {
                key = PREF_QUALITY
                title = "الجودة المفضلة"

                entries =
                    arrayOf(
                        "1080p",
                        "720p",
                        "480p",
                        "360p",
                        "240p",
                        "DoodStream",
                        "Uqload",
                    )

                entryValues =
                    arrayOf(
                        "1080",
                        "720",
                        "480",
                        "360",
                        "240",
                        "Dood",
                        "Uqload",
                    )

                setDefaultValue("1080")
                summary = "%s"
            }

        screen.addPreference(videoQualityPref)
    }

    private fun String.toAbsoluteUrl(): String =
        when {
            startsWith("http://") ||
                startsWith("https://") -> {
                this
            }

            startsWith("/") -> {
                baseUrl + this
            }

            else -> {
                "$baseUrl/$this"
            }
        }

    companion object {
        private const val DEFAULT_SITE_URL =
            "https://tv10.egydead.live/h3/"

        private const val DEFAULT_ORIGIN =
            "https://tv10.egydead.live"

        private const val PREF_BASE_URL =
            "base_url"

        private const val PREF_QUALITY =
            "preferred_quality"

        private val ORIGIN_REGEX =
            Regex(
                "^(https?://[^/]+)",
                RegexOption.IGNORE_CASE,
            )

        private val DOOD_REGEX =
            Regex(
                "(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)|ds2play",
            )

        private val STREAMWISH_REGEX =
            Regex(
                "ajmidyad|alhayabambi|atabknh[ks]|https://.*\\.(?:sbs|top)/e/",
            )

        private val SOURCES_REGEX =
            Regex(
                "sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)",
            )

        private val VIDEO_URL_REGEX =
            Regex(
                "https?:[^\"'\\\\\\s]+(?:m3u8|mp4)[^\"'\\\\\\s]*",
                RegexOption.IGNORE_CASE,
            )
    }
}
