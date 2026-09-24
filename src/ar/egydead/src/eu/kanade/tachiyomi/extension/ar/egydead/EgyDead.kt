package eu.kanade.tachiyomi.animeextension.ar.egydead

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.megamaxmultiserver.MegaMaxMultiServer
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
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
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: DEFAULT_SITE_URL

    private val homeUrl: String
        get() = configuredUrl

    override val baseUrl: String
        get() = ORIGIN_REGEX.find(configuredUrl)?.groupValues?.get(1) ?: DEFAULT_ORIGIN

    override val lang = "ar"

    override val supportsLatest = true

    // ================================== popular ==================================

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

    // ================================== episodes ==================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.attr("href"))
            episode.name = element.attr("title").ifBlank { element.text() }
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
                    episode.name = if (season.contains("موسم")) "الموسم $seasonTxt ${episode.name}" else episode.name
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
                        runCatching {
                            addEpisodes(client.newCall(GET(it.attr("href"), headers)).execute(), true)
                        }
                    }
                }
            } else if (url.contains("episode")) {
                document.selectFirst("#breadcrumbs li a[itemprop=url]")?.let {
                    runCatching {
                        addEpisodes(client.newCall(GET(it.attr("href"), headers)).execute())
                    }
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
        episode.name = element.text().ifBlank { element.attr("title") }
        episode.episode_number = element.text().filter { it.isDigit() }.toFloatOrNull() ?: 0f
        return episode
    }

    // ================================== video urls ==================================

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private data class FastResolveResult(
        val videos: List<Video>,
        val fallbackUrls: List<String>,
    )

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = episode.url.toAbsoluteUrl()
        val fallbackUrls = mutableListOf<String>()

        // Fast path: current EgyDead pages often expose the iframe directly.
        val normalDocument = runCatching {
            client.newCall(GET(episodeUrl, headers))
                .awaitSuccess()
                .useAsJsoup()
        }.getOrNull()

        if (normalDocument != null) {
            val result = resolveFastPlayers(collectPlayerUrls(normalDocument), episodeUrl)
            if (result.videos.isNotEmpty()) return result.videos.distinctVideos()
            fallbackUrls += result.fallbackUrls
        }

        // Legacy path: old pages reveal servers only after View=1.
        val postDocument = runCatching {
            val body = FormBody.Builder().add("View", "1").build()
            client.newCall(POST(episodeUrl, headers, body))
                .awaitSuccess()
                .useAsJsoup()
        }.getOrNull()

        if (postDocument != null) {
            val result = resolveFastPlayers(collectPlayerUrls(postDocument), episodeUrl)
            if (result.videos.isNotEmpty()) return result.videos.distinctVideos()
            fallbackUrls += result.fallbackUrls
        }

        // Last resort only. UniversalExtractor uses WebView and can wait up to ~10 seconds.
        val fallback = prioritizePlayerUrls(fallbackUrls).firstOrNull()
            ?: return emptyList()

        return runCatching {
            universalExtractor.videosFromUrl(
                fallback,
                playerHeaders(episodeUrl),
                prefix = "EgyDead",
            )
        }.getOrDefault(emptyList()).distinctVideos()
    }

    private suspend fun resolveFastPlayers(
        urls: List<String>,
        referer: String,
    ): FastResolveResult {
        if (urls.isEmpty()) return FastResolveResult(emptyList(), emptyList())

        val ordered = prioritizePlayerUrls(urls)
        val (knownUrls, unknownUrls) = ordered.partition(::isKnownPlayer)

        // Resolve in priority order and stop as soon as one server works. Waiting for every
        // server defeats the fast path when a slow or dead host is present beside a good one.
        for (url in knownUrls) {
            val videos = runCatching { resolveKnownPlayer(url, referer) }
                .getOrDefault(emptyList())
            if (videos.isNotEmpty()) {
                return FastResolveResult(videos.distinctVideos(), emptyList())
            }
        }

        // Unknown hosters get a cheap HTML pass before WebView fallback.
        for (url in unknownUrls) {
            val videos = resolveUnknownFast(url, referer)
            if (videos.isNotEmpty()) {
                return FastResolveResult(videos.distinctVideos(), emptyList())
            }
        }

        return FastResolveResult(
            emptyList(),
            prioritizePlayerUrls(unknownUrls + knownUrls),
        )
    }

    private suspend fun resolveKnownPlayer(
        rawUrl: String,
        referer: String,
    ): List<Video> {
        val url = normalizePlayerUrl(rawUrl)
        if (url.isBlank()) return emptyList()

        return when {
            isDirectVideo(url) -> {
                listOf(Video(url, "EgyDead Direct", url, playerHeaders(referer)))
            }

            isMegaMax(url) -> resolveMegaMax(url, referer)

            url.contains("mixdrop", true) || url.contains("mdbekjwqa", true) -> {
                mixDropExtractor.videoFromUrl(url)
            }

            isDood(url) -> {
                doodExtractor.videoFromUrl(url, "EgyDead Dood")
                    ?.let(::listOf)
                    ?: emptyList()
            }

            isStreamWish(url) -> {
                streamWishExtractor.videosFromUrl(
                    url,
                    videoNameGen = { quality -> "EgyDead StreamWish - $quality" },
                )
            }

            isVidHide(url) -> {
                vidHideExtractor.videosFromUrl(
                    url,
                    videoNameGen = { quality -> "EgyDead VidHide - $quality" },
                )
            }

            isFilemoon(url) -> {
                filemoonExtractor.videosFromUrl(
                    url,
                    prefix = "EgyDead Filemoon:",
                )
            }

            url.contains("uqload", true) -> {
                uqloadExtractor.videosFromUrl(url, "EgyDead ")
            }

            url.contains("mp4upload", true) -> {
                mp4uploadExtractor.videosFromUrl(
                    url,
                    playerHeaders(referer),
                    prefix = "EgyDead ",
                )
            }

            isVoe(url) -> {
                voeExtractor.videosFromUrl(url, "EgyDead ")
            }

            url.contains("streamtape", true) ||
                url.contains("stape", true) ||
                url.contains("shavetape", true) -> {
                streamTapeExtractor.videosFromUrl(
                    url,
                    quality = "EgyDead StreamTape",
                )
            }

            url.contains("ok.ru", true) ||
                url.contains("okru", true) -> {
                okruExtractor.videosFromUrl(url, "EgyDead")
            }

            isVidGuard(url) -> {
                vidGuardExtractor.videosFromUrl(url, prefix = "EgyDead ")
            }

            else -> emptyList()
        }
    }

    private suspend fun resolveMegaMax(url: String, referer: String): List<Video> {
        val providers = runCatching {
            MegaMaxMultiServer(client, playerHeaders(referer)).extractUrls(url)
        }.getOrDefault(emptyList())

        val orderedUrls = prioritizePlayerUrls(providers.map { it.url })
        for (providerUrl in orderedUrls) {
            val videos = runCatching { resolveKnownPlayer(providerUrl, url) }
                .getOrDefault(emptyList())
            if (videos.isNotEmpty()) return videos
        }

        return emptyList()
    }

    private suspend fun resolveUnknownFast(
        rawUrl: String,
        referer: String,
    ): List<Video> {
        val url = normalizePlayerUrl(rawUrl)
        if (url.isBlank()) return emptyList()

        return runCatching {
            val document = client.newCall(
                GET(
                    url,
                    playerHeaders(referer),
                ),
            )
                .awaitSuccess()
                .useAsJsoup()

            val directVideos = VIDEO_URL_REGEX
                .findAll(document.html().replace("\\/", "/"))
                .map { it.value }
                .distinct()
                .map {
                    Video(
                        it,
                        "EgyDead Direct",
                        it,
                        playerHeaders(url),
                    )
                }
                .toList()

            if (directVideos.isNotEmpty()) {
                return@runCatching directVideos
            }

            val nestedPlayers = collectPlayerUrls(document)
                .filter(::isKnownPlayer)

            for (nestedUrl in nestedPlayers) {
                val videos = runCatching { resolveKnownPlayer(nestedUrl, url) }
                    .getOrDefault(emptyList())
                if (videos.isNotEmpty()) return@runCatching videos
            }

            emptyList()
        }.getOrDefault(emptyList())
    }

    private fun collectPlayerUrls(document: Document): List<String> {
        val urls = mutableListOf<String>()

        document.select(
            "ul.serversList li[data-link], " +
                ".serversList li[data-link], " +
                "li[data-link]",
        ).forEach {
            urls += it.attr("abs:data-link").ifBlank { it.attr("data-link") }
        }

        document.select(
            ".serversList a[href], " +
                "ul.serversList a[href]",
        ).forEach {
            urls += it.attr("abs:href").ifBlank { it.attr("href") }
        }

        document.select(
            "iframe[src], iframe[data-src]",
        ).filterNot(::isHiddenIframe).forEach {
            urls += it.attr("abs:src")
                .ifBlank { it.attr("src") }
                .ifBlank { it.attr("abs:data-src") }
                .ifBlank { it.attr("data-src") }
        }

        document.select(
            "video[src], video source[src], source[src]",
        ).forEach {
            urls += it.attr("abs:src").ifBlank { it.attr("src") }
        }

        document.select("[data-server]").forEach {
            PLAYER_URL_REGEX
                .findAll(it.attr("data-server").replace("\\/", "/"))
                .forEach { match -> urls += match.value }
        }

        VIDEO_URL_REGEX
            .findAll(document.html().replace("\\/", "/"))
            .forEach { match -> urls += match.value }

        return prioritizePlayerUrls(urls)
    }

    private fun prioritizePlayerUrls(urls: List<String>): List<String> = urls.asSequence()
        .map(::normalizePlayerUrl)
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .filterNot(::isIgnoredPlayerUrl)
        .distinct()
        .sortedByDescending(::playerPriority)
        .toList()

    private fun normalizePlayerUrl(url: String): String {
        val clean = url
            .trim()
            .trim('"', '\'', ' ')
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> baseUrl + clean
            else -> clean
        }
    }

    private fun playerPriority(url: String): Int {
        var score = 0

        if (isKnownPlayer(url)) score += 100
        if (url.contains("/embed/", true)) score += 40
        if (url.contains("/e/", true)) score += 35
        if (url.contains("/f/", true)) score += 30
        if (isDirectVideo(url)) score += 200

        return score
    }

    private fun isKnownPlayer(url: String): Boolean = isDirectVideo(url) ||
        isMegaMax(url) ||
        url.contains("mixdrop", true) ||
        url.contains("mdbekjwqa", true) ||
        isDood(url) ||
        isStreamWish(url) ||
        isVidHide(url) ||
        isFilemoon(url) ||
        url.contains("uqload", true) ||
        url.contains("mp4upload", true) ||
        isVoe(url) ||
        url.contains("streamtape", true) ||
        url.contains("stape", true) ||
        url.contains("shavetape", true) ||
        url.contains("ok.ru", true) ||
        url.contains("okru", true) ||
        isVidGuard(url)

    private fun isDirectVideo(url: String): Boolean = DIRECT_VIDEO_REGEX.containsMatchIn(url)

    private fun isMegaMax(url: String): Boolean = listOf(
        "megamax.",
        "megaup.",
        "megacloud.",
    ).any { url.contains(it, true) } &&
        (url.contains("/iframe/", true) || url.contains("/leech/", true))

    private fun isHiddenIframe(element: Element): Boolean {
        val style = element.attr("style").replace(" ", "").lowercase()
        return element.attr("width") == "0" ||
            element.attr("height") == "0" ||
            "width:0" in style ||
            "height:0" in style ||
            "left:-" in style ||
            element.hasAttr("hidden")
    }

    private fun isDood(url: String): Boolean = listOf(
        "doodstream",
        "dood.",
        "ds2play",
        "ds2video",
        "dooood",
        "d000d",
        "d0000d",
    ).any { url.contains(it, true) }

    private fun isStreamWish(url: String): Boolean = listOf(
        "streamwish",
        "wishembed",
        "strwish",
        "swhoi",
        "kswplayer",
        "neko-stream",
        "iplayerhls",
        "streamgg",
        "multimovies",
        "uqloads",
        "ajmidyad",
        "alhayabambi",
        "atabknh",
        "atabknhs",
    ).any { url.contains(it, true) }

    private fun isVidHide(url: String): Boolean = listOf(
        "ahvsh",
        "streamhide",
        "guccihide",
        "streamvid",
        "vidhide",
        "kinoger",
        "smoothpre",
        "dhtpre",
        "peytonepre",
        "earnvids",
        "ryderjet",
    ).any { url.contains(it, true) }

    private fun isFilemoon(url: String): Boolean = listOf(
        "filemoon",
        "moonplayer",
        "moviesm4u",
        "files.im",
    ).any { url.contains(it, true) }

    private fun isVoe(url: String): Boolean = listOf(
        "voe.",
        "tubelessceliolymph",
        "simpulumlamerop",
        "urochsunloath",
        "nathanfromsubject",
        "metagnathtuggers",
        "donaldlineelse",
    ).any { url.contains(it, true) }

    private fun isVidGuard(url: String): Boolean = listOf(
        "vembed",
        "listeamed",
        "bembed",
        "vgfplay",
        "vidguard",
    ).any { url.contains(it, true) }

    private fun isIgnoredPlayerUrl(url: String): Boolean = listOf(
        "youtube.com",
        "youtu.be",
        "facebook.com",
        "instagram.com",
        "tiktok.com",
        "twitter.com",
        "x.com/",
        "doubleclick",
        "googlesyndication",
    ).any { url.contains(it, true) }

    private fun playerHeaders(referer: String) = headers.newBuilder()
        .set("Referer", referer)
        .build()

    private fun List<Video>.distinctVideos(): List<Video> = distinctBy {
        it.videoTitle to it.videoUrl.substringBefore("?")
    }

    override fun videoListSelector() = "ul.serversList li, " +
        ".serversList li, " +
        "li[data-link], " +
        "iframe[src], " +
        "iframe[data-src], " +
        "video[src], " +
        "source[src]"

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, "1080")
        if (quality == null) return this

        return sortedWith(
            compareBy<Video>(
                { it.videoTitle.contains(quality, true) },
                {
                    Regex("(\\d+)p")
                        .find(it.videoTitle)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: 0
                },
            ),
        ).reversed()
    }

    // ================================== search ==================================

    override fun searchAnimeNextPageSelector(): String = "div.pagination-two a:contains(›), a.next"

    override fun searchAnimeSelector(): String = "div.catHolder li.movieItem, li.movieItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/page/$page/?s=$query", headers)
        }

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            if (filter is CategoryList && filter.state > 0) {
                val catQ = getCategoryList()[filter.state].query
                return GET("$baseUrl/$catQ/?page=$page/", headers)
            }
        }

        return GET(homeUrl, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)

    private data class CatUnit(val name: String, val query: String)

    private val categoriesName = getCategoryList().map { it.name }.toTypedArray()

    private fun getCategoryList() = listOf(
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

    // ================================== details ==================================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.single-thumbnail img").attr("src")
        anime.title = document.select("div.infoBox div.singleTitle").text()
        anime.author = document.select("div.LeftBox li:contains(البلد) a").text()
        anime.artist = document.select("div.LeftBox li:contains(القسم) a").text()
        anime.genre = document.select(
            "div.LeftBox li:contains(النوع) a, " +
                "div.LeftBox li:contains(اللغه) a, " +
                "div.LeftBox li:contains(السنه) a",
        ).joinToString(", ") { it.text() }
        anime.description = document.select("div.infoBox div.extra-content p").text()
        anime.status = if (anime.title.contains("كامل") || anime.title.contains("فيلم")) {
            SAnime.COMPLETED
        } else {
            SAnime.ONGOING
        }
        return anime
    }

    // ================================== latest ==================================

    override fun latestUpdatesSelector(): String = "section.main-section li.movieItem, li.movieItem"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul.page-numbers li a.next, a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET(if (page <= 1) homeUrl else "$baseUrl/?page=$page", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // ================================== preferences ==================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "رابط موقع EgyDead"
            summary = "الرابط الحالي: %s"
            dialogTitle = "تغيير رابط الموقع"
            dialogMessage = "الصق رابط الموقع الحالي كاملاً. مثال: https://tv10.egydead.live/h3/"
            setDefaultValue(DEFAULT_SITE_URL)
        }
        screen.addPreference(baseUrlPref)

        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "الجودة المفضلة"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    companion object {
        private const val DEFAULT_SITE_URL = "https://tv10.egydead.live/h3/"
        private const val DEFAULT_ORIGIN = "https://tv10.egydead.live"
        private const val PREF_BASE_URL = "base_url"
        private const val PREF_QUALITY = "preferred_quality"

        private val ORIGIN_REGEX = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
        private val VIDEO_URL_REGEX = Regex(
            "https?:[^\"'\\\\\\s]+(?:m3u8|mp4)[^\"'\\\\\\s]*",
            RegexOption.IGNORE_CASE,
        )
        private val DIRECT_VIDEO_REGEX = Regex(
            "\\.(?:m3u8|mp4)(?:[?#]|$)",
            RegexOption.IGNORE_CASE,
        )
        private val PLAYER_URL_REGEX = Regex(
            "https?://[^\\s\"'<>]+",
            RegexOption.IGNORE_CASE,
        )
    }
}
