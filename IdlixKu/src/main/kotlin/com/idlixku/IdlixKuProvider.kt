package com.idlixku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class IdlixKuProvider : MainAPI() {

    override var mainUrl            = "https://z1.idlixku.com"
    override var name               = "IdlixKu"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movie"           to "Film Terbaru",
        "$mainUrl/series"          to "Serial TV",
        "$mainUrl/genre/action"    to "Aksi",
        "$mainUrl/genre/horror"    to "Horor",
        "$mainUrl/genre/comedy"    to "Komedi",
        "$mainUrl/genre/romance"   to "Romantis",
        "$mainUrl/genre/animation" to "Animasi",
        "$mainUrl/genre/thriller"  to "Thriller",
        "$mainUrl/genre/drama"     to "Drama",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, headers = ua).document
        val list = doc.parseItems()
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query", headers = ua).document
        return doc.parseItems()
    }

    // Parse item dari section sr-only yang dirender server-side
    private fun Document.parseItems(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Ambil dari section aria-label (SSR content Next.js)
        val section = selectFirst("section[aria-label]")
        section?.select("li a")?.forEach { a ->
            val href  = a.attr("href").trim()
            val title = a.text().trim()
            if (href.isEmpty() || title.isEmpty()) return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val isTV = href.contains("/series/")
            val res = if (isTV)
                newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries)
            else
                newMovieSearchResponse(title, fullUrl, TvType.Movie)
            results.add(res)
        }

        // Fallback: cari dari JSON-LD schema
        if (results.isEmpty()) {
            select("script[type='application/ld+json']").forEach { script ->
                val json = script.data()
                val urlRegex = Regex(""""url"\s*:\s*"(https://z1\.idlixku\.com/(movie|series)/[^"]+)"""")
                val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
                val urls  = urlRegex.findAll(json).map { it.groupValues[1] }.toList()
                val names = nameRegex.findAll(json).map { it.groupValues[1] }.toList()
                urls.forEachIndexed { i, itemUrl ->
                    val itemTitle = names.getOrNull(i + 1) ?: return@forEachIndexed
                    val isTV = itemUrl.contains("/series/")
                    val res = if (isTV)
                        newTvSeriesSearchResponse(itemTitle, itemUrl, TvType.TvSeries)
                    else
                        newMovieSearchResponse(itemTitle, itemUrl, TvType.Movie)
                    results.add(res)
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc   = app.get(url, headers = ua).document
        val isTV  = url.contains("/series/")

        // Ambil data dari JSON-LD
        val jsonLd = doc.select("script[type='application/ld+json']").joinToString("") { it.data() }

        // Title dari JSON-LD atau tag title
        val title = Regex(""""name"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()
            ?: return null

        // Poster dari JSON-LD image
        val poster = Regex(""""image"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)

        // Description
        val plot = Regex(""""description"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)

        // Year dari URL slug (misal: perfect-crown-2026 → 2026)
        val year = Regex("""-(\d{4})$""").find(url.trimEnd('/').substringAfterLast("/"))
            ?.groupValues?.get(1)?.toIntOrNull()

        return if (!isTV) {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        } else {
            // TV Series - coba ambil episode dari section sr-only
            val episodes = mutableListOf<Episode>()
            val section  = doc.selectFirst("section[aria-label]")
            section?.select("li a[href*='/episode/'], li a[href*='/watch/']")?.forEachIndexed { i, a ->
                val epHref  = a.attr("href")
                val epTitle = a.text().trim()
                val fullEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                episodes.add(newEpisode(fullEpUrl) {
                    name    = epTitle.ifEmpty { "Episode ${i + 1}" }
                    episode = i + 1
                })
            }

            // Jika tidak ada episode di sr-only, tambah satu episode dengan URL halaman itu sendiri
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    name = title
                })
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

        // Cari iframe dari halaman
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.startsWith("http")) embeds.add(src)
        }

        // Cari dari script (Next.js embed data)
        if (embeds.isEmpty()) {
            doc.select("script").forEach { script ->
                val text = script.data()
                Regex("""["'](https://[^"']+(?:embed|player|watch)[^"']+)["']""")
                    .findAll(text)
                    .forEach { match ->
                        val src = match.groupValues[1]
                        if (!src.contains("googletagmanager") &&
                            !src.contains("cloudflare") &&
                            !src.contains("favicon")) {
                            embeds.add(src)
                        }
                    }
            }
        }

        embeds.distinct().forEach { loadExtractor(it, data, subtitleCallback, callback) }
        return embeds.isNotEmpty()
    }
}
