package com.moviebox

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    val deviceId = generateDeviceId()

    data class BrandModel(val brand: String, val model: String)

    private val brandModels = mapOf(
        "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
        "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
        "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
        "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
        "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
    )

    fun randomBrandModel(): BrandModel {
        val brand = brandModels.keys.random()
        val model = brandModels[brand]!!.random()
        return BrandModel(brand, model)
    }

    @SuppressLint("UseKtx")
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

    override val mainPage = mainPageOf(
        "4516404531735022304" to "Trending",
        "5692654647815587592" to "Trending in Cinema",
        "8019599703232971616" to "Hollywood",
        "4741626294545400336" to "Top Series This Week",
        "1|1" to "Movies",
        "1|2" to "Series",
        "1|1006" to "Anime",
        "1|1;country=United States" to "USA (Movies)",
        "1|2;country=United States" to "USA (Series)",
        "1|1;country=Korea" to "South Korean (Movies)",
        "1|2;country=Korea" to "South Korean (Series)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 15
        val url = if (request.data.contains("|")) "$mainUrl/wefeed-mobile-bff/subject-api/list" else "$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=${request.data}&page=$page&perPage=$perPage"

        val data1 = request.data
        val mainParts = data1.substringBefore(";").split("|")
        val pg = mainParts.getOrNull(0)?.toIntOrNull() ?: 1
        val channelId = mainParts.getOrNull(1)

        val options = mutableMapOf<String, String>()
        data1.substringAfter(";", "")
            .split(";")
            .forEach {
                val p = it.split("=")
                val k = p.getOrNull(0)
                val v = p.getOrNull(1)
                if (!k.isNullOrBlank() && !v.isNullOrBlank()) {
                    options[k] = v
                }
            }

        val classify = options["classify"] ?: "All"
        val country  = options["country"] ?: "All"
        val year     = options["year"] ?: "All"
        val genre    = options["genre"] ?: "All"
        val sort     = options["sort"] ?: "ForYou"

        val jsonBody = """{"page":$pg,"perPage":$perPage,"channelId":"$channelId","classify":"$classify","country":"$country","year":"$year","genre":"$genre","sort":"$sort"}"""

        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url , jsonBody)
        val getxTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)

        val commonHeaders = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ2A.230405.003; Cronet/112.0.5615.135)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"13","device_id":"$deviceId","install_store":"ps","gaid":"${java.util.UUID.randomUUID()}","brand":"google","model":"Pixel 7","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )

        val response = if (request.data.contains("|")) {
            commonHeaders["x-tr-signature"] = xTrSignature
            app.post(url, headers = commonHeaders, requestBody = jsonBody.toRequestBody("application/json".toMediaType()))
        } else {
            commonHeaders["x-tr-signature"] = getxTrSignature
            app.get(url, headers = commonHeaders)
        }

        val responseBody = response.body.string()
        val data = try {
            val mapper = jacksonObjectMapper()
            val root = mapper.readTree(responseBody)
            val items = root["data"]?.get("items") ?: root["data"]?.get("subjects") ?: return newHomePageResponse(emptyList())
            items.mapNotNull { item ->
                val title = item["title"]?.asText()?.substringBefore("[") ?: return@mapNotNull null
                val id = item["subjectId"]?.asText() ?: return@mapNotNull null
                val coverImg = item["cover"]?.get("url")?.asText()
                val subjectType = if (id.startsWith("m")) 1 else 2
                val type = when (subjectType) {
                    1 -> TvType.Movie
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                newMovieSearchResponse(
                    name = title,
                    url = id,
                    type = type
                ) {
                    this.posterUrl = coverImg
                    this.score = item["imdbRatingValue"]?.asText()?.let { Score.from10(it) }
                }
            }
        } catch (_: Exception) {
            null
        } ?: emptyList()

        return newHomePageResponse(listOf(HomePageList(request.name, data)))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": $page, "perPage": 20, "keyword": "$query"}"""
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url, jsonBody)
        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ2A.230405.003; Cronet/112.0.5615.135)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"13","device_id":"$deviceId","install_store":"ps","gaid":"${java.util.UUID.randomUUID()}","brand":"google","model":"Pixel 7","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":""}""",
            "x-client-status" to "0"
        )

        val response = app.post(url, headers = headers, requestBody = jsonBody.toRequestBody("application/json".toMediaType())).text
        val root = jacksonObjectMapper().readTree(response)
        val items = root["data"]?.get("items") ?: return emptyList<SearchResponse>().toNewSearchResponseList()

        return items.mapNotNull { item ->
            val title = item["title"]?.asText() ?: return@mapNotNull null
            val id = item["subjectId"]?.asText() ?: return@mapNotNull null
            val coverImg = item["cover"]?.get("url")?.asText()
            val subjectType = if (id.startsWith("m")) 1 else 2
            val type = when (subjectType) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie
            }
            newMovieSearchResponse(title, id, type) {
                this.posterUrl = coverImg
            }
        }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrl = "$mainUrl/wefeed-mobile-bff/subject-api/detail?subjectId=$url"
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", apiUrl)
        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ2A.230405.003; Cronet/112.0.5615.135)",
            "accept" to "application/json",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"13","device_id":"$deviceId","install_store":"ps","gaid":"${java.util.UUID.randomUUID()}","brand":"google","model":"Pixel 7","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":""}""",
            "x-client-status" to "0"
        )

        val response = app.get(apiUrl, headers = headers).text
        val root = jacksonObjectMapper().readTree(response)
        val data = root["data"] ?: throw ErrorLoadingException("No data found")

        val title = data["title"]?.asText() ?: ""
        val poster = data["cover"]?.get("url")?.asText()
        val plot = data["description"]?.asText()
        val year = data["year"]?.asInt()
        val rating = data["imdbRatingValue"]?.asText()
        val subjectType = if (url.startsWith("m")) 1 else 2
        val imdbId = data["imdbId"]?.asText()

        return if (subjectType == 1) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                LoadResponse.addImdbId(this, imdbId)
            }
        } else {
            val seasons = data["seasons"]?.mapNotNull { season ->
                val seasonNum = season["seasonNumber"]?.asInt() ?: return@mapNotNull null
                season["episodes"]?.mapNotNull { episode ->
                    val epNum = episode["episodeNumber"]?.asInt() ?: return@mapNotNull null
                    val epId = episode["episodeId"]?.asText() ?: return@mapNotNull null
                    newEpisode(epId) {
                        this.name = episode["title"]?.asText()
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = episode["cover"]?.get("url")?.asText()
                    }
                }
            }?.flatten() ?: emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                LoadResponse.addImdbId(this, imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/play-info?episodeId=$data"
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)
        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ2A.230405.003; Cronet/112.0.5615.135)",
            "accept" to "application/json",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"13","device_id":"$deviceId","install_store":"ps","gaid":"${java.util.UUID.randomUUID()}","brand":"google","model":"Pixel 7","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":""}""",
            "x-client-status" to "0"
        )

        val response = app.get(url, headers = headers).text
        val root = jacksonObjectMapper().readTree(response)
        val playData = root["data"] ?: return false

        playData["playList"]?.forEach { source ->
            val sourceUrl = source["url"]?.asText() ?: return@forEach
            val qualityStr = source["quality"]?.asText() ?: "HD"
            val quality = when (qualityStr.uppercase()) {
                "4K" -> 2160
                "1080P" -> 1080
                "720P" -> 720
                "480P" -> 480
                "360P" -> 360
                else -> 0
            }
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    sourceUrl,
                    "",
                    quality,
                    sourceUrl.contains(".m3u8")
                )
            )
        }

        playData["subtitles"]?.forEach { sub ->
            val subUrl = sub["url"]?.asText() ?: return@forEach
            val lang = sub["language"]?.asText() ?: "English"
            subtitleCallback.invoke(
                SubtitleFile(lang, subUrl)
            )
        }

        return true
    }
}
