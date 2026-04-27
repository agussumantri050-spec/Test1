package com.idlix

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = base64Decode("aHR0cHM6Ly96MS5pZGxpeGt1LmNvbQ==")
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "TV Series Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 10000L).parsedSafe<ApiResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
        val home = res.data.map { item ->
            val title = item.title ?: "UnKnown"
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                newMovieSearchResponse(title, "$mainUrl/api/movies/${item.slug}", TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getSearchQuality(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                newTvSeriesSearchResponse(title, "$mainUrl/api/series/${item.slug}", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage)
                    this.quality = getSearchQuality(item.quality)
                }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/api/search?q=$query&page=$page&limit=8"
        val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return null
        val results = res.results.mapNotNull { item ->
            val title = item.title
            val poster = item.posterPath.let { "https://image.tmdb.org/t/p/w342$it" }
            val year = (item.releaseDate ?: item.firstAirDate)?.substringBefore("-")?.toIntOrNull()
            val link = when (item.contentType) {
                "movie" -> "$mainUrl/api/movies/${item.slug}"
                "tv_series", "series" -> "$mainUrl/api/series/${item.slug}"
                else -> return@mapNotNull null
            }
            if (item.contentType == "movie") {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = getQualityFromString(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(item.voteAverage)
                }
            }
        }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = app.get(url, timeout = 10000L).parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid JSON")

        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        val year = (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val logourl = "https://image.tmdb.org/t/p/w500" + data.logoPath
        val actors = data.cast?.map {
            Actor(it.name ?: "", it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
        } ?: emptyList()

        val relatedUrl = if (data.seasons != null)
            "$mainUrl/api/series/${data.slug}/related"
        else
            "$mainUrl/api/movies/${data.slug}/related"

        val recommendations = try {
            app.get(relatedUrl, referer = mainUrl)
                .parsedSafe<ApiResponse>()?.data?.mapNotNull { item ->
                    val t = item.title ?: return@mapNotNull null
                    val p = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
                    val link = if (item.contentType == "movie")
                        "$mainUrl/api/movies/${item.slug}"
                    else
                        "$mainUrl/api/series/${item.slug}"
                    if (item.contentType == "movie")
                        newMovieSearchResponse(t, link, TvType.Movie) { this.posterUrl = p }
                    else
                        newTvSeriesSearchResponse(t, link, TvType.TvSeries) { this.posterUrl = p }
                } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        return if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()
            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(newEpisode(LoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) {
                    this.name = ep.name
                    this.season = data.firstSeason.seasonNumber
                    this.episode = ep.episodeNumber
                    this.description = ep.overview
                    this.runTime = ep.runtime
                    this.score = Score.from10(ep.voteAverage?.toString())
                    addDate(ep.airDate)
                    this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                })
            }
            data.seasons.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == data.firstSeason?.seasonNumber) return@forEach
                val seasonData = try {
                    app.get("$mainUrl/api/series/${data.slug}/season/$seasonNum", referer = mainUrl)
                        .parsedSafe<SeasonWrapper>()?.season
                } catch (_: Exception) { null }
                seasonData?.episodes?.forEach { ep ->
                    episodes.add(newEpisode(LoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) {
                        this.name = ep.name
                        this.season = seasonNum
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        addDate(ep.airDate)
                        this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LoadData(id = data.id ?: "", type = "movie").toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = try {
            AppUtils.parseJson<LoadData>(data)
        } catch (_: Exception) { null } ?: return false

        val contentId   = parsed.id
        val contentType = parsed.type

        // ── 1. Ambil aclr (opsional) ──────────────────────────────────────
        val ts      = System.currentTimeMillis()
        val aclrRes = try { app.get("$mainUrl/pagead/ad_frame.js?_=$ts").text } catch (_: Exception) { "" }
        val aclr    = Regex("""__aclr\s*=\s*"([a-f0-9]+)"""").find(aclrRes)?.groupValues?.getOrNull(1)

        // ── 2. Request challenge ───────────────────────────────────────────
        val challengeJson = buildString {
            append("""{"contentType":"$contentType","contentId":"$contentId"""")
            if (aclr != null) append(""","clearance":"$aclr"""")
            append("}")
        }

        val headers = mapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to mainUrl,
            "referer" to "$mainUrl/",
            "user-agent" to USER_AGENT,
        )

        val challengeRes = try {
            app.post(
                "$mainUrl/api/watch/challenge",
                requestBody = challengeJson.toRequestBody("application/json".toMediaType()),
                headers = headers
            ).parsedSafe<ChallengeResponse>()
        } catch (_: Exception) { null } ?: return false

        // ── 3. Solve proof-of-work ─────────────────────────────────────────
        val nonce = solvePow(challengeRes.challenge, challengeRes.difficulty)

        val solveJson = """{"challenge":"${challengeRes.challenge}","signature":"${challengeRes.signature}","nonce":$nonce}"""

        val solveText = try {
            app.post(
                "$mainUrl/api/watch/solve",
                requestBody = solveJson.toRequestBody("application/json".toMediaType()),
                headers = headers
            ).text
        } catch (_: Exception) { return false }

        Log.d(name, "solve response: $solveText")

        val json = try { JSONObject(solveText) } catch (_: Exception) { return false }

        // ── 4. Ambil embed URL ─────────────────────────────────────────────
        // Server bisa kembalikan: embedUrl, url, embed, iframeUrl, src
        val embedPath = json.optString("embedUrl").ifEmpty { null }
            ?: json.optString("url").ifEmpty { null }
            ?: json.optString("embed").ifEmpty { null }
            ?: json.optString("iframeUrl").ifEmpty { null }
            ?: json.optString("src").ifEmpty { null }

        if (embedPath.isNullOrEmpty()) {
            Log.e(name, "embed URL not found in response: $solveText")
            return false
        }

        // ── 5. Bangun URL lengkap ──────────────────────────────────────────
        // embedPath bisa berupa path (/embed/...) atau URL penuh (https://...)
        val embedUrl = if (embedPath.startsWith("http")) embedPath
                       else "$mainUrl$embedPath"

        Log.d(name, "embedUrl: $embedUrl")

        // ── 6. Resolve iframe dengan WebViewResolver ───────────────────────
        // Coba intercept berbagai pola URL player yang umum
        val interceptPattern = Regex(
            """(https?://[^"'\s]+(?:\.m3u8|\.mp4|/embed/|/player/|/stream/|/watch/)[^"'\s]*)""",
            RegexOption.IGNORE_CASE
        )

        return try {
            val resolver = WebViewResolver(
                interceptUrl = interceptPattern,
                additionalUrls = listOf(interceptPattern),
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolvedUrl = app.get(embedUrl, interceptor = resolver).url
            Log.d(name, "resolved: $resolvedUrl")

            if (resolvedUrl == embedUrl) {
                // WebView tidak bisa intercept → coba load langsung
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            } else {
                loadExtractor(resolvedUrl, mainUrl, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e(name, "loadLinks error: ${e.message}")
            // Fallback: langsung ekstrak tanpa WebView
            try {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                true
            } catch (_: Exception) { false }
        }
    }

    fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)
        var nonce = 0
        while (true) {
            if (sha256(challenge + nonce).startsWith(target)) return nonce
            nonce++
        }
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val s = check ?: return null
    val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
    )
    for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
    return null
}
