package com.idlixku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class IdlixKuProvider : MainAPI() {

    override var mainUrl            = "https://z1.idlixku.com"
    override var name               = "IdlixKu"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // Header agar tidak diblokir server
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Referer" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending/"      to "Trending",
        "$mainUrl/movies/"        to "Film Terbaru",
        "$mainUrl/tv/"            to "Serial TV",
        "$mainUrl/drama/"         to "Drama Asia",
        "$mainUrl/genre/action/"  to "Aksi",
        "$mainUrl/genre/horror/"  to "Horor",
        "$mainUrl/genre/comedy/"  to "Komedi",
        "$mainUrl/genre/romance/" to "Romantis",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc  = app.get(url, headers = headers).document
        // Coba beberapa selector umum tema DooPlay/WordPress streaming Indonesia
        val items = doc.select("article.item, article.post, div.item, div.poster")
        val home  = items.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Coba berbagai kombinasi selector untuk judul dan link
        val a = selectFirst("h3 a, h2 a, .entry-title a, .data h3 a, a.lnk-blk")
            ?: selectFirst("a[href*='${mainUrl}']")
            ?: return null
        val title  = a.text().trim().ifEmpty { selectFirst("img")?.attr("alt")?.trim() ?: return null }
        val href   = a.attr("href").ifEmpty { return null }
        // Coba berbagai selector poster
        val poster = selectFirst("img.attachment-thumbnail, img.wp-post-image, div.poster img, img")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
        val cls    = attr("class") + " " + parent()?.attr("class")
        val isTV   = cls.contains("tvshows") || cls.contains("series") || cls.contains("drama") || href.contains("/tv/") || href.contains("/drama/")
        return if (isTV)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document
        return doc.select("article.item, article.post, div.item, div.result-item article")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url, headers = headers).document
        val title  = doc.selectFirst("h1.entry-title, div.data h1, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.poster img, div.sheader img, img.wp-post-image")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
        val plot   = doc.selectFirst("div.wp-content p, div[itemprop=description] p, div.sinopsis p")?.text()?.trim()
        val year   = doc.selectFirst("span.year, a[href*='/year/']")?.text()?.trim()?.toIntOrNull()
        val tags   = doc.select("div.sgeneros a, div.genres a").map { it.text() }
        val epEls  = doc.select("ul.episodios li, #seasons .se-c .episodios li")

        return if (epEls.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        } else {
            val episodes = epEls.mapNotNull { li ->
                val href    = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = li.selectFirst("div.episodiotitle a")?.text()
                val num     = li.selectFirst("div.numerando")?.text()?.split("-")
                val seasonNum  = num?.getOrNull(0)?.trim()?.toIntOrNull()
                val episodeNum = num?.getOrNull(1)?.trim()?.toIntOrNull()
                newEpisode(href) {
                    name    = epTitle
                    season  = seasonNum
                    episode = episodeNum
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc    = app.get(data, headers = headers).document
        val embeds = mutableListOf<String>()

        // Method 1: DooPlay AJAX (tema paling umum Indonesia)
        doc.select("ul#playeroptionsul li, ul.dooplay_player_option li").forEach { li ->
            val post = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")
            if (post.isNotEmpty() && nume.isNotEmpty()) {
                try {
                    val res = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post"   to post,
                            "nume"   to nume,
                            "type"   to type,
                        ),
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    ).text
                    val embedUrl = tryParseJson<EmbedData>(res)?.embed_url
                        ?: Regex("""embed_url["']?\s*:\s*["']([^"']+)""").find(res)?.groupValues?.getOrNull(1)
                    embedUrl?.replace("\\", "")?.let { if (it.startsWith("http")) embeds.add(it) }
                } catch (_: Exception) {}
            }
        }

        // Method 2: Cari iframe langsung
        if (embeds.isEmpty()) {
            doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.startsWith("http")) embeds.add(src)
            }
        }

        embeds.distinct().forEach { loadExtractor(it, data, subtitleCallback, callback) }
        return embeds.isNotEmpty()
    }

    data class EmbedData(val embed_url: String? = null)
}
