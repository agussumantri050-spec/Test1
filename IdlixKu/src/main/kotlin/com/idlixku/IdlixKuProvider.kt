package com.idlixku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        "Accept-Language" to "id-ID,id;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    // Semua kategori diambil dari homepage "/" yang punya SSR penuh
    // Value = keyword yang dicari di dalam teks h2
    override val mainPage = mainPageOf(
        "$mainUrl/|trending"         to "Trending",
        "$mainUrl/|recently-movies"  to "Film Terbaru",
        "$mainUrl/|recently-series"  to "Serial TV",
        "$mainUrl/|network"          to "Network Originals",
        "$mainUrl/|collections"      to "Koleksi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val keyword = request.data.substringAfterLast("|")
        val doc     = app.get("$mainUrl/", headers = ua).document

        // Struktur: section.sr-only > div > h2 + ul > li > a
        val section = doc.selectFirst("section.sr-only, section[aria-label]")
        val list    = mutableListOf<SearchResponse>()

        section?.select("div")?.forEach { div ->
            val h2 = div.selectFirst("h2") ?: return@forEach
            val h2text = h2.text().lowercase()

            val matched = when (keyword) {
                "trending"        -> h2text.contains("trending now")
                "recently-movies" -> h2text.contains("added movie") || h2text.contains("recently added movies")
                "recently-series" -> h2text.contains("added series") || h2text.contains("recently added series")
                "network"         -> h2text.contains("network")
                "collections"     -> h2text.contains("collection")
                else              -> false
            }

            if (matched) {
                div.select("ul li a").forEach { a ->
                    val r = a.toResult() ?: return@forEach
                    list.add(r)
                }
            }
        }

        // Fallback: ambil semua jika masih kosong
        if (list.isEmpty()) {
            section?.select("ul li a")?.forEach { a ->
                val r = a.toResult() ?: return@forEach
                list.add(r)
            }
        }

        return newHomePageResponse(request.name, list.distinctBy { it.url })
    }

    private fun Element.toResult(): SearchResponse? {
        val href  = attr("href").trim().ifEmpty { return null }
        val title = text().trim().ifEmpty { return null }
        if (!href.contains("/movie/") && !href.contains("/series/")) return null
        val url  = if (href.startsWith("http")) href else "$mainUrl$href"
        val isTV = href.contains("/series/")
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

        // Fallback JSON-LD
        if (results.isEmpty()) {
            doc.select("script[type='application/ld+json']").forEach { script ->
                val text  = script.data()
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
        val doc    = app.get(url, headers = ua).document
        val isTV   = url.contains("/series/")
        val jsonLd = doc.select("script[type='application/ld+json']").joinToString("") { it.data() }

        val title = Regex(""""name"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()
            ?: return null

        val poster = Regex(""""image"\s*:\s*"(https://[^"]+)"""").find(jsonLd)?.groupValues?.get(1)
        val plot   = Regex(""""description"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
        val year   = Regex("""-(\d{4})(?:/|$)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        return if (!isTV) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        } else {
            val episodes   = mutableListOf<Episode>()
            val srOnly     = doc.selectFirst("section.sr-only, section[aria-label]")
            srOnly?.select("li a")?.forEachIndexed { i, a ->
                val epHref = a.attr("href").trim()
                if (epHref.contains("/episode/") || epHref.contains("/watch/") || epHref.contains("/series/")) {
                    val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    episodes.add(newEpisode(epUrl) {
                        name    = a.text().trim().ifEmpty { "Episode ${i + 1}" }
                        episode = i + 1
                    })
                }
            }
            if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = title })

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

        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.startsWith("http")) embeds.add(src)
        }

        if (embeds.isEmpty()) {
            doc.select("script:not([src])").forEach { script ->
                val text = script.data()
                Regex("""["'](https?://(?:doodstream|streamtape|filemoon|streamhub|upcloud|vidplay|vido|voe)[^"']+)["']""")
                    .findAll(text).forEach { embeds.add(it.groupValues[1]) }
            }
        }

        embeds.distinct().forEach { loadExtractor(it, data, subtitleCallback, callback) }
        return embeds.isNotEmpty()
    }
}
