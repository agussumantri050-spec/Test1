package com.idlixku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class IdlixKuProvider : MainAPI() {

    override var mainUrl            = "https://z1.idlixku.com"
    override var name               = "IdlixKu"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    // Semua kategori diambil dari homepage "/" yang punya SSR penuh
    // Format: "URL#SECTION_KEYWORD" → keyword dipakai untuk filter h2
    override val mainPage = mainPageOf(
        "$mainUrl/#trending"          to "Trending",
        "$mainUrl/#recently-movies"   to "Film Terbaru",
        "$mainUrl/#recently-series"   to "Serial TV",
        "$mainUrl/#network"           to "Network Originals",
        "$mainUrl/#collections"       to "Koleksi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Semua data dari homepage (satu-satunya yang punya SSR lengkap)
        val doc     = app.get("$mainUrl/", headers = ua).document
        val section = request.data.substringAfterLast("#")
        val list    = doc.extractSection(section)
        return newHomePageResponse(request.name, list)
    }

    // Ekstrak item dari section berdasarkan keyword h2
    private fun Document.extractSection(keyword: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val srOnly  = selectFirst("section.sr-only, section[aria-label]") ?: return results

        // Cari h2 yang matching dengan keyword
        var capture = false
        srOnly.children().forEach { child ->
            when {
                child.tagName() == "h2" -> {
                    val h2text = child.text().lowercase()
                    capture = when (keyword) {
                        "trending"         -> h2text.contains("trending now")
                        "recently-movies"  -> h2text.contains("recently added movies") || h2text.contains("movie")
                        "recently-series"  -> h2text.contains("recently added series") || h2text.contains("series")
                        "network"          -> h2text.contains("network")
                        "collections"      -> h2text.contains("collection")
                        else               -> h2text.contains(keyword)
                    }
                }
                child.tagName() == "div" && capture -> {
                    // Stop di section berikutnya
                    capture = false
                }
                child.tagName() == "ul" && capture -> {
                    child.select("li a").forEach { a ->
                        val r = a.toResult() ?: return@forEach
                        results.add(r)
                    }
                }
            }
        }

        // Fallback: jika tidak ada grouping by h2, ambil semua sesuai type
        if (results.isEmpty()) {
            srOnly.select("li a").forEach { a ->
                val href = a.attr("href")
                val matched = when (keyword) {
                    "recently-movies"  -> href.contains("/movie/")
                    "recently-series", "trending" -> href.contains("/series/")
                    else -> href.contains("/movie/") || href.contains("/series/")
                }
                if (matched) {
                    val r = a.toResult() ?: return@forEach
                    results.add(r)
                }
            }
        }

        return results.distinctBy { it.url }
    }

    private fun Element.toResult(): SearchResponse? {
        val href  = attr("href").trim().ifEmpty { return null }
        val title = text().trim().ifEmpty { return null }
        val url   = if (href.startsWith("http")) href else "$mainUrl$href"
        if (!href.contains("/movie/") && !href.contains("/series/")) return null
        val isTV  = href.contains("/series/")
        return if (isTV)
            newTvSeriesSearchResponse(title, url, TvType.TvSeries)
        else
            newMovieSearchResponse(title, url, TvType.Movie)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc     = app.get("$mainUrl/search?q=$query", headers = ua).document
        val results = mutableListOf<SearchResponse>()

        // Coba dari section sr-only
        doc.select("section.sr-only li a, section[aria-label] li a").forEach { a ->
            val r = a.toResult() ?: return@forEach
            results.add(r)
        }

        // Fallback: JSON-LD
        if (results.isEmpty()) {
            doc.select("script[type='application/ld+json']").forEach { script ->
                val text = script.data()
                val urlR  = Regex(""""url"\s*:\s*"(https://z1\.idlixku\.com/(?:movie|series)/[^"]+)"""")
                val nameR = Regex(""""name"\s*:\s*"([^"]+)"""")
                val urls  = urlR.findAll(text).map { it.groupValues[1] }.toList()
                val names = nameR.findAll(text).map { it.groupValues[1] }.toList()
                urls.zip(names).forEach { (u, n) ->
                    val isTV = u.contains("/series/")
                    results.add(
                        if (isTV) newTvSeriesSearchResponse(n, u, TvType.TvSeries)
                        else newMovieSearchResponse(n, u, TvType.Movie)
                    )
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc   = app.get(url, headers = ua).document
        val isTV  = url.contains("/series/")

        // Judul dari JSON-LD, <title>, atau h1
        val jsonLd = doc.select("script[type='application/ld+json']").joinToString("") { it.data() }
        val title  =
            Regex(""""name"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()
            ?: return null

        // Poster dari JSON-LD image
        val poster  = Regex(""""image"\s*:\s*"(https://[^"]+)"""").find(jsonLd)?.groupValues?.get(1)
        val plot    = Regex(""""description"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
        val year    = Regex("""-(\d{4})(?:/|$)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        // Cari episode dari sr-only
        val srOnly = doc.selectFirst("section.sr-only, section[aria-label]")

        return if (!isTV) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        } else {
            val episodes = mutableListOf<Episode>()
            srOnly?.select("li a")?.forEachIndexed { i, a ->
                val epHref = a.attr("href").trim()
                if (epHref.contains("/episode/") || epHref.contains("/watch/") || epHref.contains("/series/")) {
                    val epUrl   = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    val epTitle = a.text().trim()
                    episodes.add(newEpisode(epUrl) {
                        name    = epTitle.ifEmpty { "Episode ${i + 1}" }
                        episode = i + 1
                    })
                }
            }
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { name = title })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc    = app.get(data, headers = ua).document
        val embeds = mutableListOf<String>()

        // Cari iframe
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.startsWith("http")) embeds.add(src)
        }

        // Cari embed URL dari script
        if (embeds.isEmpty()) {
            doc.select("script:not([src])").forEach { script ->
                val text = script.data()
                Regex("""["'](https?://[^"']+(?:embed|player|stream|watch)[^"']*\.(?:m3u8|mp4|html)[^"']*)["']""")
                    .findAll(text).forEach { embeds.add(it.groupValues[1]) }
                Regex("""["'](https?://(?:doodstream|streamtape|filemoon|streamhub|upcloud|vidplay)[^"']+)["']""")
                    .findAll(text).forEach { embeds.add(it.groupValues[1]) }
            }
        }

        embeds.distinct().forEach { loadExtractor(it, data, subtitleCallback, callback) }
        return embeds.isNotEmpty()
    }
}
