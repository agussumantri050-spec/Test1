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

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/"       to "Featured",
        "$mainUrl/movie"  to "Film Terbaru",
        "$mainUrl/series" to "Serial TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val doc = app.get(url, headers = ua).document
        val list = mutableListOf<SearchResponse>()

        // Metode 1: Parse dari JSON-LD ItemList (pasti ada di SSR Next.js)
        doc.select("script[type='application/ld+json']").forEach { script ->
            try {
                val json = script.data().trim()
                // Bisa berupa array atau object tunggal
                val items = if (json.startsWith("[")) {
                    // Array of schemas
                    Regex(""""itemListElement"\s*:\s*\[([^\]]+)\]""").find(json)?.groupValues?.get(1)
                } else {
                    Regex(""""itemListElement"\s*:\s*\[([^\]]+)\]""").find(json)?.groupValues?.get(1)
                }
                if (items != null) {
                    // Extract url dan name dari setiap item
                    val urls  = Regex(""""url"\s*:\s*"(https://z1\.idlixku\.com/(?:movie|series)/[^"]+)"""")
                        .findAll(items).map { it.groupValues[1] }.toList()
                    val names = Regex(""""name"\s*:\s*"([^"]+)"""")
                        .findAll(items).map { it.groupValues[1] }.toList()

                    urls.forEachIndexed { i, itemUrl ->
                        val itemName = names.getOrElse(i) { itemUrl.substringAfterLast("/") }
                        val isTV = itemUrl.contains("/series/")
                        list.add(
                            if (isTV)
                                newTvSeriesSearchResponse(itemName, itemUrl, TvType.TvSeries)
                            else
                                newMovieSearchResponse(itemName, itemUrl, TvType.Movie)
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Metode 2: Parse dari section sr-only (jika ada)
        if (list.isEmpty()) {
            val srOnly = doc.selectFirst("section.sr-only, section[aria-label]")
            srOnly?.select("div")?.forEach { div ->
                div.select("ul li a").forEach { a ->
                    val r = a.toResult() ?: return@forEach
                    list.add(r)
                }
            }
        }

        // Metode 3: Ambil semua link movie/series di halaman
        if (list.isEmpty()) {
            doc.select("a[href*='/movie/'], a[href*='/series/']").forEach { a ->
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
        val doc     = app.get("$mainUrl/search?q=${query}", headers = ua).document
        val results = mutableListOf<SearchResponse>()

        // JSON-LD
        doc.select("script[type='application/ld+json']").forEach { script ->
            try {
                val json  = script.data()
                val urls  = Regex(""""url"\s*:\s*"(https://z1\.idlixku\.com/(?:movie|series)/[^"]+)"""")
                    .findAll(json).map { it.groupValues[1] }.toList()
                val names = Regex(""""name"\s*:\s*"([^"]+)"""")
                    .findAll(json).map { it.groupValues[1] }.toList()
                urls.forEachIndexed { i, u ->
                    val n    = names.getOrElse(i) { u.substringAfterLast("/") }
                    val isTV = u.contains("/series/")
                    results.add(
                        if (isTV) newTvSeriesSearchResponse(n, u, TvType.TvSeries)
                        else newMovieSearchResponse(n, u, TvType.Movie)
                    )
                }
            } catch (_: Exception) {}
        }

        // Fallback: link langsung
        if (results.isEmpty()) {
            doc.select("a[href*='/movie/'], a[href*='/series/']").forEach { a ->
                val r = a.toResult() ?: return@forEach
                results.add(r)
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url, headers = ua).document
        val isTV   = url.contains("/series/")

        // Ambil semua JSON-LD dari halaman
        val jsonLd = doc.select("script[type='application/ld+json']").joinToString(" ") { it.data() }

        // Title: dari JSON-LD "name" pertama yang bukan "IDLIX"
        val title = Regex(""""name"\s*:\s*"(?!IDLIX)([^"]{2,})"""")
            .findAll(jsonLd).map { it.groupValues[1] }.firstOrNull()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()
            ?: return null

        // Poster dari JSON-LD "image"
        val poster = Regex(""""image"\s*:\s*"(https://[^"]+)"""").find(jsonLd)?.groupValues?.get(1)

        // Plot dari JSON-LD "description"
        val plot = Regex(""""description"\s*:\s*"([^"]{10,})"""").find(jsonLd)?.groupValues?.get(1)

        // Tahun dari URL slug
        val year = Regex("""-(\d{4})$""")
            .find(url.trimEnd('/').substringAfterLast("/"))
            ?.groupValues?.get(1)?.toIntOrNull()

        // Poster fallback dari og:image
        val posterFallback = poster
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        return if (!isTV) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterFallback
                this.plot      = plot
                this.year      = year
            }
        } else {
            // Episode: dari section sr-only atau semua link /episode/
            val episodes   = mutableListOf<Episode>()
            val srOnly     = doc.selectFirst("section.sr-only, section[aria-label]")

            srOnly?.select("li a")?.forEach { a ->
                val epHref = a.attr("href").trim()
                if (epHref.contains("/episode/") || epHref.contains("/watch/")) {
                    val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    episodes.add(newEpisode(epUrl) { name = a.text().trim() })
                }
            }

            if (episodes.isEmpty()) {
                doc.select("a[href*='/episode/'], a[href*='/watch/']").forEach { a ->
                    val epHref = a.attr("href").trim()
                    val epUrl  = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    episodes.add(newEpisode(epUrl) { name = a.text().trim() })
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { name = title })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterFallback
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
                Regex("""["'](https?://(?:doodstream|streamtape|filemoon|vidplay|upcloud|voe|vido|streamsb)[^"']+)["']""")
                    .findAll(script.data()).forEach { embeds.add(it.groupValues[1]) }
            }
        }

        embeds.distinct().forEach { loadExtractor(it, data, subtitleCallback, callback) }
        return embeds.isNotEmpty()
    }
}
