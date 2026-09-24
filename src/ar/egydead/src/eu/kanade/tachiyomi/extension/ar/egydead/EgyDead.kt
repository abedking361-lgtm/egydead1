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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
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
            ?.takeIf {
                it.startsWith("https://") ||
                    it.startsWith("http://")
            }
            ?: DEFAULT_SITE_URL

    private val homeUrl: String
        get() = configuredUrl

    override val baseUrl: String
        get() = ORIGIN_REGEX
            .find(configuredUrl)
            ?.groupValues
            ?.get(1)
            ?: DEFAULT_ORIGIN

    override val lang = "ar"

    override val supportsLatest = true

    // =========================
    // Popular
    // =========================

    override fun popularAnimeSelector(): String =
        "div.pin-posts-list li.movieItem"

    override fun popularAnimeNextPageSelector(): String =
        "div.whatever"

    override fun popularAnimeRequest(page: Int): Request =
        GET(homeUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
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

    // =========================
    // Episodes
    // =========================

    override fun episodeListParse(
        response: Response,
    ): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        fun episodeExtract(
            element: Element,
        ): SEpisode {
            val episode = SEpisode.create()

            episode.setUrlWithoutDomain(
                element.attr("href"),
            )

            episode.name =
                element.attr("title")
                    .ifBlank {
                        element.text()
                    }

            return episode
        }

        fun addEpisodes(
            res: Response,
            final: Boolean = false,
        ) {
            val document =
                res.useAsJsoup()

            val url =
                res.request.url.toString()

            if (final) {
                document
                    .select(
                        episodeListSelector(),
                    )
                    .forEach {
                        val episode =
                            episodeFromElement(it)

                        val season =
                            document
                                .select(
                                    "div.infoBox div.singleTitle",
                                )
                                .text()

                        val seasonTxt =
                            season
                                .substringAfter(
                                    "الموسم ",
                                )
                                .substringBefore(
                                    " ",
                                )

                        episode.name =
                            if (
                                season.contains(
                                    "موسم",
                                )
                            ) {
                                "الموسم $seasonTxt ${episode.name}"
                            } else {
                                episode.name
                            }

                        episodes.add(
                            episode,
                        )
                    }
            } else if (
                url.contains(
                    "assembly",
                )
            ) {
                document
                    .select(
                        "div.salery-list li.movieItem a",
                    )
                    .forEach {
                        episodes.add(
                            episodeExtract(
                                it,
                            ),
                        )
                    }
            } else if (
                url.contains(
                    "serie",
                ) ||
                url.contains(
                    "season",
                )
            ) {
                val seasons =
                    document.select(
                        "div.seasons-list li.movieItem a",
                    )

                if (
                    seasons.isEmpty()
                ) {
                    document
                        .select(
                            episodeListSelector(),
                        )
                        .forEach {
                            episodes.add(
                                episodeFromElement(
                                    it,
                                ),
                            )
                        }
                } else {
                    seasons.forEach {
                        runCatching {
                            addEpisodes(
                                client.newCall(
                                    GET(
                                        it.attr(
                                            "href",
                                        ),
                                        headers,
                                    ),
                                ).execute(),
                                true,
                            )
                        }
                    }
                }
            } else if (
                url.contains(
                    "episode",
                )
            ) {
                document
                    .selectFirst(
                        "#breadcrumbs li a[itemprop=url]",
                    )
                    ?.let {
                        runCatching {
                            addEpisodes(
                                client.newCall(
                                    GET(
                                        it.attr(
                                            "href",
                                        ),
                                        headers,
                                    ),
                                ).execute(),
                            )
                        }
                    }
            } else {
                val episode =
                    SEpisode.create()

                episode.name =
                    "مشاهدة"

                episode.setUrlWithoutDomain(
                    url,
                )

                episodes.add(
                    episode,
                )
            }
        }

        addEpisodes(
            response,
        )

        return episodes
    }

    override fun episodeListSelector(): String =
        "div.EpsList li a"

    override fun episodeFromElement(
        element: Element,
    ): SEpisode {
        val episode =
            SEpisode.create()

        episode.setUrlWithoutDomain(
            element.attr(
                "href",
            ),
        )

        episode.name =
            element
                .text()
                .ifBlank {
                    element.attr(
                        "title",
                    )
                }

        episode.episode_number =
            element
                .text()
                .filter {
                    it.isDigit()
                }
                .toFloatOrNull()
                ?: 0f

        return episode
    }

    // =========================
    // Video
    // =========================

    private val streamWishExtractor by lazy {
        StreamWishExtractor(
            client,
            headers,
        )
    }

    override suspend fun getVideoList(
        episode: SEpisode,
    ): List<Video> {
        val episodeUrl =
            episode.url
                .toAbsoluteUrl()

        val documents =
            mutableListOf<Document>()

        // الطريقة الجديدة:
        // الصفحة نفسها قد تحتوي iframe
        runCatching {
            val document =
                client.newCall(
                    GET(
                        episodeUrl,
                        headers,
                    ),
                )
                    .awaitSuccess()
                    .useAsJsoup()

            documents.add(
                document,
            )
        }

        // الطريقة القديمة:
        // بعض صفحات EgyDead تحتاج View=1
        runCatching {
            val body =
                FormBody.Builder()
                    .add(
                        "View",
                        "1",
                    )
                    .build()

            val document =
                client.newCall(
                    POST(
                        episodeUrl,
                        headers,
                        body,
                    ),
                )
                    .awaitSuccess()
                    .useAsJsoup()

            documents.add(
                document,
            )
        }

        val serverUrls =
            mutableListOf<String>()

        documents.forEach { document ->

            // السيرفرات القديمة
            document.select(
                "ul.serversList li[data-link], " +
                    ".serversList li[data-link], " +
                    "li[data-link]",
            ).forEach {
                val url =
                    it.attr(
                        "data-link",
                    )
                        .trim()

                if (
                    url.isNotBlank()
                ) {
                    serverUrls.add(
                        url,
                    )
                }
            }

            // بعض النسخ تخزن الرابط داخل a
            document.select(
                ".serversList a[href], " +
                    "ul.serversList a[href]",
            ).forEach {
                val url =
                    it.absUrl(
                        "href",
                    )
                        .ifBlank {
                            it.attr(
                                "href",
                            )
                        }
                        .trim()

                if (
                    url.isNotBlank()
                ) {
                    serverUrls.add(
                        url,
                    )
                }
            }

            // النظام الجديد
            document.select(
                "iframe[src]",
            ).forEach {
                val url =
                    it.absUrl(
                        "src",
                    )
                        .ifBlank {
                            it.attr(
                                "src",
                            )
                        }
                        .trim()

                if (
                    url.isNotBlank()
                ) {
                    serverUrls.add(
                        url,
                    )
                }
            }

            // فيديو مباشر
            document.select(
                "video[src], " +
                    "video source[src], " +
                    "source[src]",
            ).forEach {
                val url =
                    it.absUrl(
                        "src",
                    )
                        .ifBlank {
                            it.attr(
                                "src",
                            )
                        }
                        .trim()

                if (
                    url.isNotBlank()
                ) {
                    serverUrls.add(
                        url,
                    )
                }
            }

            // البحث داخل HTML عن روابط مباشرة
            val html =
                document
                    .html()
                    .replace(
                        "\\/",
                        "/",
                    )

            VIDEO_URL_REGEX
                .findAll(
                    html,
                )
                .forEach {
                    serverUrls.add(
                        it.value,
                    )
                }
        }

        val videos =
            mutableListOf<Video>()

        serverUrls
            .distinct()
            .forEach { serverUrl ->

                runCatching {
                    extractVideos(
                        serverUrl,
                    )
                }
                    .getOrDefault(
                        emptyList(),
                    )
                    .let {
                        videos.addAll(
                            it,
                        )
                    }
            }

        return videos
            .distinctBy {
                it.videoUrl
            }
    }

    private suspend fun extractVideos(
        url: String,
    ): List<Video> {
        val fixedUrl =
            url
                .trim()
                .replace(
                    "\\/",
                    "/",
                )

        if (
            fixedUrl.isBlank()
        ) {
            return emptyList()
        }

        if (
            fixedUrl.contains(
                ".m3u8",
                true,
            ) ||
            fixedUrl.contains(
                ".mp4",
                true,
            )
        ) {
            return listOf(
                Video(
                    fixedUrl,
                    "Direct",
                    fixedUrl,
                ),
            )
        }

        if (
            DOOD_REGEX.containsMatchIn(
                fixedUrl,
            )
        ) {
            return DoodExtractor(
                client,
            )
                .videoFromUrl(
                    fixedUrl,
                    "Dood mirror",
                )
                ?.let(
                    ::listOf,
                )
                ?: emptyList()
        }

        if (
            fixedUrl.contains(
                "mdbekjwqa",
            ) ||
            fixedUrl.contains(
                "mixdrop",
                true,
            )
        ) {
            return MixDropExtractor(
                client,
            )
                .videoFromUrl(
                    fixedUrl,
                )
                ?: emptyList()
        }

        if (
            fixedUrl.contains(
                "uqload",
                true,
            )
        ) {
            return extractUqload(
                fixedUrl,
            )
        }

        if (
            fixedUrl.contains(
                "ahvsh",
            )
        ) {
            return extractSourcesScript(
                fixedUrl,
                "StreamHide",
            )
        }

        if (
            fixedUrl.contains(
                "fanakishtuna",
            )
        ) {
            return extractSourcesScript(
                fixedUrl,
                "Mirror",
            )
        }

        // نجرب StreamWish لأي دومين /e/ أو /f/
        // لأن دومينات السيرفر تتغير باستمرار
        if (
            fixedUrl.contains(
                "/e/",
            ) ||
            fixedUrl.contains(
                "/f/",
            ) ||
            STREAMWISH_REGEX.containsMatchIn(
                fixedUrl,
            ) ||
            fixedUrl.contains(
                "streamwish",
                true,
            )
        ) {
            runCatching {
                streamWishExtractor
                    .videosFromUrl(
                        fixedUrl,
                    )
            }
                .getOrNull()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.let {
                    return it
                }
        }

        return extractGeneric(
            fixedUrl,
        )
    }

    private suspend fun extractUqload(
        url: String,
    ): List<Video> {
        val fixedUrl =
            url.replace(
                "https://uqload.co/",
                "https://www.uqload.co/",
            )

        return runCatching {
            val document =
                client.newCall(
                    GET(
                        fixedUrl,
                        headers,
                    ),
                )
                    .awaitSuccess()
                    .useAsJsoup()

            val data =
                document
                    .selectFirst(
                        "script:containsData(sources)",
                    )
                    ?.data()
                    .orEmpty()

            val streamLink =
                data
                    .substringAfter(
                        "sources: [\"",
                    )
                    .substringBefore(
                        "\"]",
                    )

            if (
                streamLink.isBlank()
            ) {
                emptyList()
            } else {
                listOf(
                    Video(
                        streamLink,
                        "Uqload",
                        streamLink,
                    ),
                )
            }
        }
            .getOrDefault(
                emptyList(),
            )
    }

    private suspend fun extractSourcesScript(
        url: String,
        label: String,
    ): List<Video> {
        return runCatching {
            val document =
                client.newCall(
                    GET(
                        url,
                        headers,
                    ),
                )
                    .awaitSuccess()
                    .useAsJsoup()

            val script =
                document
                    .selectFirst(
                        "script:containsData(sources)",
                    )
                    ?.data()
                    .orEmpty()

            val streamLink =
                SOURCES_REGEX
                    .find(
                        script,
                    )
                    ?.groupValues
                    ?.get(
                        1,
                    )
                    .orEmpty()

            if (
                streamLink.isBlank()
            ) {
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
            .getOrDefault(
                emptyList(),
            )
    }

    private suspend fun extractGeneric(
        url: String,
    ): List<Video> {

        // جرّب StreamWish أولاً حتى لو الدومين تغير
        runCatching {
            streamWishExtractor
                .videosFromUrl(
                    url,
                )
        }
            .getOrNull()
            ?.takeIf {
                it.isNotEmpty()
            }
            ?.let {
                return it
            }

        return runCatching {
            val response =
                client.newCall(
                    GET(
                        url,
                        headers,
                    ),
                )
                    .awaitSuccess()

            val html =
                response
                    .body
                    .string()
                    .replace(
                        "\\/",
                        "/",
                    )

            VIDEO_URL_REGEX
                .findAll(
                    html,
                )
                .map {
                    it.value
                }
                .distinct()
                .map {
                    Video(
                        it,
                        "Mirror",
                        it,
                    )
                }
                .toList()
        }
            .getOrDefault(
                emptyList(),
            )
    }

    override fun videoListSelector(): String =
        "ul.serversList li, " +
            ".serversList li, " +
            "li[data-link], " +
            "iframe[src], " +
            "video[src], " +
            "source[src]"

    override fun videoFromElement(
        element: Element,
    ): Video =
        throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality =
            preferences.getString(
                PREF_QUALITY,
                "1080",
            )

        if (
            quality == null
        ) {
            return this
        }

        val preferred =
            mutableListOf<Video>()

        val others =
            mutableListOf<Video>()

        forEach {
            if (
                it.videoTitle.contains(
                    quality,
                    true,
                )
            ) {
                preferred.add(
                    it,
                )
            } else {
                others.add(
                    it,
                )
            }
        }

        return preferred + others
    }

    // =========================
    // Search
    // =========================

    override fun searchAnimeNextPageSelector(): String =
        "div.pagination-two a:contains(›), a.next"

    override fun searchAnimeSelector(): String =
        "div.catHolder li.movieItem, li.movieItem"

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {

        if (
            query.isNotBlank()
        ) {
            return GET(
                "$baseUrl/page/$page/?s=$query",
                headers,
            )
        }

        (if (
            filters.isEmpty()
        ) {
            getFilterList()
        } else {
            filters
        })
            .forEach { filter ->

                if (
                    filter is CategoryList &&
                    filter.state > 0
                ) {
                    val category =
                        getCategoryList()[
                            filter.state
                        ]

                    return GET(
                        "$baseUrl/${category.query}/?page=$page/",
                        headers,
                    )
                }
            }

        return GET(
            homeUrl,
            headers,
        )
    }

    override fun searchAnimeFromElement(
        element: Element,
    ): SAnime {
        val anime =
            SAnime.create()

        anime.setUrlWithoutDomain(
            element
                .select(
                    "a",
                )
                .attr(
                    "href",
                ),
        )

        anime.title =
            element
                .select(
                    "h1.BottomTitle",
                )
                .text()

        anime.thumbnail_url =
            element
                .select(
                    "a img",
                )
                .attr(
                    "src",
                )

        return anime
    }

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            CategoryList(
                categoriesName,
            ),
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
            .map {
                it.name
            }
            .toTypedArray()

    private fun getCategoryList(): List<CatUnit> =
        listOf(
            CatUnit(
                "اختر القسم",
                "",
            ),
            CatUnit(
                "افلام اجنبى",
                "category/افلام-اجنبي",
            ),
            CatUnit(
                "افلام اسلام الجيزاوى",
                "category/ترجمات-اسلام-الجيزاوي",
            ),
            CatUnit(
                "افلام انمى",
                "category/افلام-كرتون",
            ),
            CatUnit(
                "افلام تركيه",
                "category/افلام-تركية",
            ),
            CatUnit(
                "افلام اسيويه",
                "category/افلام-اسيوية",
            ),
            CatUnit(
                "افلام مدبلجة",
                "category/افلام-اجنبية-مدبلجة",
            ),
            CatUnit(
                "سلاسل افلام",
                "assembly",
            ),
            CatUnit(
                "مسلسلات اجنبية",
                "series-category/مسلسلات-اجنبي",
            ),
            CatUnit(
                "مسلسلات انمى",
                "series-category/مسلسلات-انمي",
            ),
            CatUnit(
                "مسلسلات تركية",
                "series-category/مسلسلات-تركية",
            ),
            CatUnit(
                "مسلسلات اسيوية",
                "series-category/مسلسلات-اسيوية",
            ),
            CatUnit(
                "مسلسلات لاتينية",
                "series-category/مسلسلات-لاتينية",
            ),
            CatUnit(
                "المسلسلات الكاملة",
                "serie",
            ),
            CatUnit(
                "المواسم الكاملة",
                "season",
            ),
        )

    // =========================
    // Details
    // =========================

    override fun animeDetailsParse(
        document: Document,
    ): SAnime {
        val anime =
            SAnime.create()

        anime.thumbnail_url =
            document
                .select(
                    "div.single-thumbnail img",
                )
                .attr(
                    "src",
                )

        anime.title =
            document
                .select(
                    "div.infoBox div.singleTitle",
                )
                .text()

        anime.author =
            document
                .select(
                    "div.LeftBox li:contains(البلد) a",
                )
                .text()

        anime.artist =
            document
                .select(
                    "div.LeftBox li:contains(القسم) a",
                )
                .text()

        anime.genre =
            document
                .select(
                    "div.LeftBox li:contains(النوع) a, " +
                        "div.LeftBox li:contains(اللغه) a, " +
                        "div.LeftBox li:contains(السنه) a",
                )
                .joinToString(
                    ", ",
                ) {
                    it.text()
                }

        anime.description =
            document
                .select(
                    "div.infoBox div.extra-content p",
                )
                .text()

        anime.status =
            if (
                anime.title.contains(
                    "كامل",
                ) ||
                anime.title.contains(
                    "فيلم",
                )
            ) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }

        return anime
    }

    // =========================
    // Latest
    // =========================

    override fun latestUpdatesSelector(): String =
        "section.main-section li.movieItem, li.movieItem"

    override fun latestUpdatesNextPageSelector(): String =
        "div.pagination ul.page-numbers li a.next, a.next"

    override fun latestUpdatesRequest(
        page: Int,
    ): Request =
        GET(
            if (
                page <= 1
            ) {
                homeUrl
            } else {
                "$baseUrl/?page=$page"
            },
            headers,
        )

    override fun latestUpdatesFromElement(
        element: Element,
    ): SAnime {
        val anime =
            SAnime.create()

        anime.setUrlWithoutDomain(
            element
                .select(
                    "a",
                )
                .attr(
                    "href",
                ),
        )

        anime.title =
            element
                .select(
                    "h1.BottomTitle",
                )
                .text()

        anime.thumbnail_url =
            element
                .select(
                    "a img",
                )
                .attr(
                    "src",
                )

        return anime
    }

    // =========================
    // Settings
    // =========================

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {
        val baseUrlPreference =
            EditTextPreference(
                screen.context,
            ).apply {
                key =
                    PREF_BASE_URL

                title =
                    "رابط موقع EgyDead"

                summary =
                    "الرابط الحالي: %s"

                dialogTitle =
                    "تغيير رابط الموقع"

                dialogMessage =
                    "الصق رابط الموقع الحالي كاملاً، مثال: https://tv10.egydead.live/h3/"

                setDefaultValue(
                    DEFAULT_SITE_URL,
                )
            }

        screen.addPreference(
            baseUrlPreference,
        )

        val qualityPreference =
            ListPreference(
                screen.context,
            ).apply {
                key =
                    PREF_QUALITY

                title =
                    "الجودة المفضلة"

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

                setDefaultValue(
                    "1080",
                )

                summary =
                    "%s"
            }

        screen.addPreference(
            qualityPreference,
        )
    }

    private fun String.toAbsoluteUrl(): String =
        when {
            startsWith(
                "http://",
            ) ||
                startsWith(
                    "https://",
                ) -> {
                this
            }

            startsWith(
                "/",
            ) -> {
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
                "ajmidyad|" +
                    "alhayabambi|" +
                    "atabknh[ks]|" +
                    "streamwish|" +
                    "https://.*\\.(?:sbs|top)/[efd]/",
                RegexOption.IGNORE_CASE,
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
