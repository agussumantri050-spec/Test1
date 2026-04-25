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
        val doc  = app.get(url).document
        val home = doc.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a      = selectFirst("h3.entry-title a, h2.entry-title a") ?: return null
        val title  = a.text().trim()
        val href   = a.attr("href").ifEmpty { return null }
        val poster = selectFirst("div.poster img")?.attr("src")
        val isTV   = attr("class").let { it.contains("tvshows") || it.contains("series") || it.contains("drama") }
        return if (isTV)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url).document
        val title  = doc.selectFirst("h1.entry-title, div.data h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.poster img")?.attr("src")
        val plot   = doc.selectFirst("div.wp-content p, div[itemprop=description] p")?.text()?.trim()
        val year   = doc.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val tags   = doc.select("div.sgeneros a").map { it.text() }
        val epEls  = doc.select("ul.episodios li")

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
                newEpisode(href) {
                    this.name    = epTitle
                    this.season  = num?.getOrNull(0)?.trim()?.toIntOrNull()
                    this.episode = num?.getOrNull(1)?.trim()?.toIntOrNull()
                }
                )
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
        val doc    = app.get(data).document
        val embeds = mutableListOf<String>()

        doc.select("ul#playeroptionsul li").forEach { li ->
            val post = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")
            if (post.isNotEmpty() && nume.isNotEmpty()) {
                try {
                    val res = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    ).text
                    val embedUrl = tryParseJson<EmbedData>(res)?.embed_url
                        ?: Regex("""embed_url["']?\s*:\s*["']([^"']+)""").find(res)?.groupValues?.getOrNull(1)
                    embedUrl?.replace("\\", "")?.let { embeds.add(it) }
                } catch (_: Exception) {}
            }
        }

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
