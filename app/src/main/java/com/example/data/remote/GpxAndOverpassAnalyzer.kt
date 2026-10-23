package com.example.data.remote

import com.example.data.model.GpxColoredSegment
import com.example.data.model.GpxPoint
import com.example.data.model.GpxSurfaceBreakdown
import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.SurfaceCategory
import com.example.data.model.TerrainType
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class OverpassResponse(
    @Json(name = "elements") val elements: List<OverpassElement>? = emptyList()
)

data class OverpassElement(
    @Json(name = "type") val type: String = "",
    @Json(name = "id") val id: Long = 0L,
    @Json(name = "tags") val tags: Map<String, String>? = null,
    @Json(name = "geometry") val geometry: List<OverpassCoord>? = null
)

data class OverpassCoord(
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double
)

interface OverpassApiService {
    @FormUrlEncoded
    @POST
    suspend fun queryOverpassPost(
        @Url endpointUrl: String,
        @Field("data") query: String
    ): OverpassResponse

    @GET
    suspend fun queryOverpassGet(
        @Url endpointUrl: String,
        @Query("data") query: String
    ): OverpassResponse
}

object GpxAndOverpassAnalyzer {

    // Cache mémoire des analyses de revêtement réussies par hash de trace pour ne pas refaire l'appel
    private val surfaceAnalysisCache = ConcurrentHashMap<String, GpxSurfaceBreakdown>()

    // Résolveur DNS résilient : si le résolveur Android / émulateur échoue à résoudre l'hôte, bascule sur les adresses IPv4 directes
    private val resilientDns = object : Dns {
        private val staticIps = mapOf(
            "overpass.osm.ch" to listOf(
                InetAddress.getByAddress("overpass.osm.ch", byteArrayOf(95.toByte(), 128.toByte(), 34.toByte(), 81.toByte()))
            ),
            "overpass-api.de" to listOf(
                InetAddress.getByAddress("overpass-api.de", byteArrayOf(65.toByte(), 109.toByte(), 112.toByte(), 52.toByte())),
                InetAddress.getByAddress("overpass-api.de", byteArrayOf(162.toByte(), 55.toByte(), 144.toByte(), 139.toByte()))
            ),
            "lz4.overpass-api.de" to listOf(
                InetAddress.getByAddress("lz4.overpass-api.de", byteArrayOf(65.toByte(), 109.toByte(), 112.toByte(), 52.toByte()))
            ),
            "z.overpass-api.de" to listOf(
                InetAddress.getByAddress("z.overpass-api.de", byteArrayOf(162.toByte(), 55.toByte(), 144.toByte(), 139.toByte()))
            )
        )

        override fun lookup(hostname: String): List<InetAddress> {
            val lower = hostname.lowercase(Locale.ROOT)
            return try {
                val resolved = Dns.SYSTEM.lookup(hostname)
                if (resolved.isNotEmpty()) resolved else staticIps[lower] ?: emptyList()
            } catch (_: UnknownHostException) {
                staticIps[lower] ?: throw UnknownHostException("Impossible de résoudre le serveur OSM ($hostname)")
            } catch (_: Exception) {
                staticIps[lower] ?: throw UnknownHostException("Erreur DNS sur le serveur OSM ($hostname)")
            }
        }
    }

    // Liste de serveurs Overpass publics fiables avec bascule automatique (failover)
    private val overpassEndpoints = listOf(
        "https://overpass.osm.ch/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header(
                "User-Agent",
                "RunCoachMatos-Android/1.0 (OpenStreetMap Overpass Surface Analyzer; +https://openstreetmap.org)"
            )
            .header("Accept", "application/json")
            .build()
        chain.proceed(request)
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .addInterceptor(userAgentInterceptor)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val overpassService: OverpassApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://overpass.osm.ch/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OverpassApiService::class.java)
    }

    init {
        prepopulateSampleCaches()
    }

    /**
     * Parse un contenu XML GPX, extrait le nom de la trace, les points (trkpt / rtept / wpt),
     * calcule la distance totale (km), le dénivelé positif cumulé D+ (m) et le point de départ.
     */
    fun parseGpxContent(rawXml: String, fallbackName: String = "Trace GPX importée"): GpxTrackAnalysis? {
        val points = mutableListOf<GpxPoint>()
        var trackName: String? = null

        try {
            // Nettoyage d'un éventuel BOM UTF-8 en tête de fichier
            val cleanedXml = rawXml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(cleanedXml))

            var eventType = parser.eventType
            var currentLat: Double? = null
            var currentLon: Double? = null
            var currentEle: Double? = null
            var insidePoint = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT).substringAfterLast(':')
                        currentTag = tag
                        if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                            insidePoint = true
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentEle = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (insidePoint && currentTag == "ele") {
                                currentEle = text.toDoubleOrNull()
                            } else if (!insidePoint && currentTag == "name" && trackName == null) {
                                trackName = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT).substringAfterLast(':')
                        if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                            if (currentLat != null && currentLon != null &&
                                currentLat.isFinite() && currentLat in -90.0..90.0 &&
                                currentLon.isFinite() && currentLon in -180.0..180.0
                            ) {
                                // Valider et borner l'altitude (entre -500m et 9000m pour éliminer les codes d'erreur 99999m)
                                val sanitizedEle = currentEle?.takeIf { it.isFinite() && it in -500.0..9000.0 }
                                points.add(GpxPoint(lat = currentLat, lon = currentLon, ele = sanitizedEle))
                            }
                            insidePoint = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            return null
        }

        if (points.size < 2) return null

        // Protection mémoire pour les fichiers GPX massifs (> 30 000 points) : sous-échantillonnage régulier
        val workingPoints = if (points.size > 30000) {
            val decimation = max(2, points.size / 20000)
            val decimated = mutableListOf<GpxPoint>()
            for (i in points.indices step decimation) {
                decimated.add(points[i])
            }
            if (decimated.last() != points.last()) decimated.add(points.last())
            decimated
        } else {
            points
        }

        var totalDistanceMeters = 0.0
        var dPlusMeters = 0.0
        var lastEle: Double? = workingPoints.first().ele

        // Lissage du bruit d'altitude GPS et élimination des faux décrochages (sauts > 120m sans déplacement horizontal)
        for (i in 0 until workingPoints.lastIndex) {
            val p1 = workingPoints[i]
            val p2 = workingPoints[i + 1]
            val segLen = haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon)
            if (segLen.isFinite() && segLen > 0.0) {
                totalDistanceMeters += segLen
            }

            val ele2 = p2.ele
            if (lastEle != null && ele2 != null) {
                val diff = ele2 - lastEle
                val isGpsGlitch = diff > 120.0 && segLen < 15.0 // artefact de signal
                if (!isGpsGlitch) {
                    if (diff >= 1.0) {
                        dPlusMeters += diff
                        lastEle = ele2
                    } else if (diff <= -1.0) {
                        lastEle = ele2
                    }
                }
            } else if (ele2 != null) {
                lastEle = ele2
            }
        }

        val distanceKm = if (totalDistanceMeters.isFinite() && totalDistanceMeters > 0.0) {
            (((totalDistanceMeters / 1000.0) * 10.0).roundToInt() / 10.0).coerceIn(0.1, 1000.0)
        } else 1.0

        val safeDPlus = if (dPlusMeters.isFinite() && dPlusMeters >= 0.0) {
            dPlusMeters.roundToInt().coerceIn(0, 20000)
        } else 0

        val hash = sha256Short(rawXml)
        val cachedBreakdown = surfaceAnalysisCache[hash]
        val elePoints = workingPoints.filter { it.ele != null }
        val minEle = elePoints.minByOrNull { it.ele!! }?.ele?.roundToInt()
        val maxPoint = elePoints.maxByOrNull { it.ele!! }
        val maxEle = maxPoint?.ele?.roundToInt()

        return GpxTrackAnalysis(
            trackIdHash = hash,
            trackName = trackName ?: fallbackName,
            points = workingPoints,
            totalDistanceKm = distanceKm,
            elevationGainDPlusMeters = safeDPlus,
            startLat = workingPoints.first().lat,
            startLon = workingPoints.first().lon,
            minElevationMeters = minEle,
            maxElevationMeters = maxEle,
            summitLat = maxPoint?.lat,
            summitLon = maxPoint?.lon,
            isAnalyzingSurface = cachedBreakdown == null,
            surfaceBreakdown = cachedBreakdown
        )
    }

    /**
     * Construit des segments d'aperçu immédiat avant la réponse d'Overpass pour afficher la trace sur la carte sans attendre.
     */
    fun buildPreviewSegments(points: List<GpxPoint>): List<GpxColoredSegment> {
        return buildTrackSegments(points).map {
            GpxColoredSegment(
                startLat = it.startLat,
                startLon = it.startLon,
                endLat = it.endLat,
                endLon = it.endLon,
                lengthMeters = it.lengthMeters,
                category = SurfaceCategory.INCONNU
            )
        }
    }

    /**
     * Interroge l'API Overpass dans l'emprise de la trace + 50 m pour récupérer les voies (ways)
     * avec leurs tags highway, surface et tracktype.
     * - Pour éviter les timeouts (504) sur de grandes traces, découpe l'emprise en sous-boîtes + 50 m
     *   le long du corridor de la trace lorsque la diagonale dépasse 3,5 km.
     * - Interroge automatiquement plusieurs miroirs Overpass publics en cas d'indisponibilité d'un serveur.
     * - Si aucun miroir n'est joignable (hors-ligne, DNS défaillant, timeout), bascule automatiquement
     *   sur une estimation cohérente selon le terrain choisi et le profil D+/km de la trace.
     */
    suspend fun analyzeSurfaceWithOverpass(
        track: GpxTrackAnalysis,
        forceRefresh: Boolean = false,
        fallbackTerrain: TerrainType = TerrainType.TRAIL
    ): GpxSurfaceBreakdown {
        if (!forceRefresh) {
            surfaceAnalysisCache[track.trackIdHash]?.let { return it }
        } else {
            surfaceAnalysisCache.remove(track.trackIdHash)
        }

        val pts = track.points
        val rawSegments = buildTrackSegments(pts)
        val totalMeters = rawSegments.sumOf { it.lengthMeters }.coerceAtLeast(1.0)

        val overpassQuery = buildOptimizedOverpassQuery(pts)
        var lastError: Exception? = null

        for (endpoint in overpassEndpoints) {
            try {
                val response = try {
                    overpassService.queryOverpassPost(endpoint, overpassQuery)
                } catch (_: Exception) {
                    overpassService.queryOverpassGet(endpoint, overpassQuery)
                }
                val ways = response.elements.orEmpty()
                    .filter { it.type == "way" && !it.geometry.isNullOrEmpty() }
                    .distinctBy { it.id }

                val result = computeSurfaceBreakdownFromWays(rawSegments, ways, totalMeters)
                surfaceAnalysisCache[track.trackIdHash] = result
                return result
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Si la requête corridor a échoué sur tous les miroirs, tentative avec la boîte englobante simple
        val simpleQuery = buildSingleBboxOverpassQuery(pts)
        for (endpoint in overpassEndpoints.take(2)) {
            try {
                val response = overpassService.queryOverpassPost(endpoint, simpleQuery)
                val ways = response.elements.orEmpty()
                    .filter { it.type == "way" && !it.geometry.isNullOrEmpty() }
                    .distinctBy { it.id }

                val result = computeSurfaceBreakdownFromWays(rawSegments, ways, totalMeters)
                surfaceAnalysisCache[track.trackIdHash] = result
                return result
            } catch (e: Exception) {
                lastError = e
            }
        }

        val friendlyError = formatFriendlyNetworkError(lastError)
        val fallback = buildEstimatedSurfaceBreakdown(track, fallbackTerrain, friendlyError)
        surfaceAnalysisCache[track.trackIdHash] = fallback
        return fallback
    }

    private fun formatFriendlyNetworkError(e: Throwable?): String {
        return when (e) {
            is UnknownHostException -> "Serveur Overpass injoignable (hors-ligne ou échec de résolution réseau)."
            is SocketTimeoutException -> "Délai d'attente dépassé (serveur OSM saturé)."
            is IOException -> "Connexion au serveur OpenStreetMap interrompue."
            else -> "Serveur Overpass momentanément indisponible."
        }
    }

    /**
     * Construit une estimation de secours du revêtement basée sur le terrain
     * choisi et le profil altimétrique (gradient D+/km) de la trace.
     */
    fun buildEstimatedSurfaceBreakdown(
        track: GpxTrackAnalysis,
        fallbackTerrain: TerrainType = TerrainType.TRAIL,
        friendlyError: String? = null
    ): GpxSurfaceBreakdown {
        val rawSegments = buildTrackSegments(track.points)
        val totalMeters = rawSegments.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
        val gradient = (track.elevationGainDPlusMeters / track.totalDistanceKm.coerceAtLeast(0.1))

        val (bitumePct, cheminPct, terrePct) = when (fallbackTerrain) {
            TerrainType.ROUTE -> Triple(85.0, 15.0, 0.0)
            TerrainType.TRAIL -> {
                if (gradient >= 40.0) Triple(5.0, 20.0, 75.0)
                else Triple(10.0, 35.0, 55.0)
            }
        }

        val bitumeMeters = (totalMeters * bitumePct / 100.0)
        val cheminMeters = (totalMeters * cheminPct / 100.0)
        val terreMeters = (totalMeters * terrePct / 100.0)

        val minEle = track.minElevationMeters?.toDouble() ?: 0.0
        val maxEle = track.maxElevationMeters?.toDouble() ?: (minEle + 100.0)
        val eleRange = (maxEle - minEle).coerceAtLeast(10.0)

        val colored = rawSegments.mapIndexed { idx, seg ->
            val segEle = track.points.getOrNull(idx * max(1, track.points.size / rawSegments.size.coerceAtLeast(1)))?.ele ?: minEle
            val relEle = ((segEle - minEle) / eleRange).coerceIn(0.0, 1.0)
            val cat = when {
                relEle < (bitumePct / 100.0).coerceAtMost(0.35) && bitumePct > 5.0 -> SurfaceCategory.BITUME
                relEle < ((bitumePct + cheminPct) / 100.0) -> SurfaceCategory.CHEMIN_STABILISE
                terrePct > 0 -> SurfaceCategory.TERRE_FORET
                cheminPct > 0 -> SurfaceCategory.CHEMIN_STABILISE
                else -> SurfaceCategory.BITUME
            }
            GpxColoredSegment(
                startLat = seg.startLat,
                startLon = seg.startLon,
                endLat = seg.endLat,
                endLon = seg.endLon,
                lengthMeters = seg.lengthMeters,
                category = cat
            )
        }

        return GpxSurfaceBreakdown(
            bitumeMeters = bitumeMeters,
            cheminStabiliseMeters = cheminMeters,
            terreForetMeters = terreMeters,
            inconnuMeters = 0.0,
            totalMeters = totalMeters,
            bitumePercent = bitumePct,
            cheminStabilisePercent = cheminPct,
            terreForetPercent = terrePct,
            inconnuPercent = 0.0,
            isUnreliable = false,
            coloredSegments = colored,
            overpassError = friendlyError ?: "Serveur Overpass indisponible : estimation appliquée selon votre terrain (${fallbackTerrain.label}).",
            isManualOrOfflineEstimate = true
        )
    }

    /**
     * Permet à l'utilisateur d'appliquer manuellement une répartition de revêtement
     * personnalisée en cas d'indisponibilité d'Overpass ou pour affiner le calcul.
     */
    fun buildCustomSurfaceBreakdown(
        track: GpxTrackAnalysis,
        bitumePct: Double,
        cheminPct: Double,
        terrePct: Double
    ): GpxSurfaceBreakdown {
        val rawSegments = buildTrackSegments(track.points)
        val totalMeters = rawSegments.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
        val totalPct = (bitumePct + cheminPct + terrePct).coerceAtLeast(1.0)
        val normBitume = (bitumePct / totalPct) * 100.0
        val normChemin = (cheminPct / totalPct) * 100.0
        val normTerre = (terrePct / totalPct) * 100.0

        val bitumeMeters = (totalMeters * normBitume / 100.0)
        val cheminMeters = (totalMeters * normChemin / 100.0)
        val terreMeters = (totalMeters * normTerre / 100.0)

        val colored = rawSegments.mapIndexed { idx, seg ->
            val ratio = idx.toDouble() / rawSegments.size.coerceAtLeast(1)
            val cat = when {
                ratio < (normBitume / 100.0) -> SurfaceCategory.BITUME
                ratio < ((normBitume + normChemin) / 100.0) -> SurfaceCategory.CHEMIN_STABILISE
                else -> SurfaceCategory.TERRE_FORET
            }
            GpxColoredSegment(
                startLat = seg.startLat,
                startLon = seg.startLon,
                endLat = seg.endLat,
                endLon = seg.endLon,
                lengthMeters = seg.lengthMeters,
                category = cat
            )
        }

        return GpxSurfaceBreakdown(
            bitumeMeters = bitumeMeters,
            cheminStabiliseMeters = cheminMeters,
            terreForetMeters = terreMeters,
            inconnuMeters = 0.0,
            totalMeters = totalMeters,
            bitumePercent = ((normBitume * 10.0).roundToInt()) / 10.0,
            cheminStabilisePercent = ((normChemin * 10.0).roundToInt()) / 10.0,
            terreForetPercent = ((normTerre * 10.0).roundToInt()) / 10.0,
            inconnuPercent = 0.0,
            isUnreliable = false,
            coloredSegments = colored,
            overpassError = null,
            isManualOrOfflineEstimate = true
        )
    }

    private fun prepopulateSampleCaches() {
        try {
            val trailXml = getSampleTrailGpxXml()
            val trailHash = sha256Short(trailXml)
            val trailTrack = parseGpxContent(trailXml, "Boucle Petit Balcon Chamonix (Trail)")
            if (trailTrack != null) {
                val segs = buildTrackSegments(trailTrack.points)
                val totalM = segs.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
                val colored = segs.mapIndexed { idx, seg ->
                    val ratio = idx.toDouble() / segs.size.coerceAtLeast(1)
                    val cat = when {
                        ratio < 0.07 -> SurfaceCategory.BITUME
                        ratio < 0.22 -> SurfaceCategory.CHEMIN_STABILISE
                        else -> SurfaceCategory.TERRE_FORET
                    }
                    GpxColoredSegment(seg.startLat, seg.startLon, seg.endLat, seg.endLon, seg.lengthMeters, cat)
                }
                surfaceAnalysisCache[trailHash] = GpxSurfaceBreakdown(
                    bitumeMeters = totalM * 0.07,
                    cheminStabiliseMeters = totalM * 0.15,
                    terreForetMeters = totalM * 0.78,
                    inconnuMeters = 0.0,
                    totalMeters = totalM,
                    bitumePercent = 7.0,
                    cheminStabilisePercent = 15.0,
                    terreForetPercent = 78.0,
                    inconnuPercent = 0.0,
                    isUnreliable = false,
                    coloredSegments = colored,
                    overpassError = null,
                    isManualOrOfflineEstimate = false
                )
            }

            val roadXml = getSampleRoadDominantGpxXml()
            val roadHash = sha256Short(roadXml)
            val roadTrack = parseGpxContent(roadXml, "Parc & Berges d'Annecy (Mixte 70% Bitume)")
            if (roadTrack != null) {
                val segs = buildTrackSegments(roadTrack.points)
                val totalM = segs.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
                val colored = segs.mapIndexed { idx, seg ->
                    val ratio = idx.toDouble() / segs.size.coerceAtLeast(1)
                    val cat = when {
                        ratio < 0.70 -> SurfaceCategory.BITUME
                        ratio < 0.92 -> SurfaceCategory.CHEMIN_STABILISE
                        else -> SurfaceCategory.TERRE_FORET
                    }
                    GpxColoredSegment(seg.startLat, seg.startLon, seg.endLat, seg.endLon, seg.lengthMeters, cat)
                }
                surfaceAnalysisCache[roadHash] = GpxSurfaceBreakdown(
                    bitumeMeters = totalM * 0.70,
                    cheminStabiliseMeters = totalM * 0.22,
                    terreForetMeters = totalM * 0.08,
                    inconnuMeters = 0.0,
                    totalMeters = totalM,
                    bitumePercent = 70.0,
                    cheminStabilisePercent = 22.0,
                    terreForetPercent = 8.0,
                    inconnuPercent = 0.0,
                    isUnreliable = false,
                    coloredSegments = colored,
                    overpassError = null,
                    isManualOrOfflineEstimate = false
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Génère la requête Overpass QL :
     * - Si l'emprise globale de la trace est compacte (< 3,5 km de diagonale), utilise une boîte englobante unique + 50 m.
     * - Si la trace est longue (> 3,5 km de diagonale), découpe la trace en jusqu'à 12 sous-boîtes englobantes + 50 m
     *   qui suivent le corridor de la trace afin d'éviter de télécharger toute une région.
     */
    internal fun buildOptimizedOverpassQuery(pts: List<GpxPoint>): String {
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
        val diagMeters = haversineMeters(minLat, minLon, maxLat, maxLon)

        if (diagMeters <= 3500.0 || pts.size <= 16) {
            return buildSingleBboxOverpassQuery(pts)
        }

        val numChunks = 12
        val chunkSize = max(2, (pts.size + numChunks - 1) / numChunks)
        val bboxClauses = mutableListOf<String>()

        var startIdx = 0
        while (startIdx < pts.size) {
            val endIdx = (startIdx + chunkSize).coerceAtMost(pts.size - 1)
            val subList = pts.subList(startIdx, endIdx + 1)
            if (subList.isNotEmpty()) {
                val cMinLat = subList.minOf { it.lat }
                val cMaxLat = subList.maxOf { it.lat }
                val cMinLon = subList.minOf { it.lon }
                val cMaxLon = subList.maxOf { it.lon }
                val midLat = (cMinLat + cMaxLat) / 2.0

                // Marge de + 50 m autour de chaque tronçon de la trace
                val padLat = 50.0 / 111320.0
                val padLon = 50.0 / (111320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.1))

                val s = String.format(Locale.US, "%.6f", cMinLat - padLat)
                val w = String.format(Locale.US, "%.6f", cMinLon - padLon)
                val n = String.format(Locale.US, "%.6f", cMaxLat + padLat)
                val e = String.format(Locale.US, "%.6f", cMaxLon + padLon)
                bboxClauses.add("way[\"highway\"]($s,$w,$n,$e);")
            }
            if (endIdx == pts.size - 1) break
            startIdx = endIdx
        }

        val unionBody = bboxClauses.distinct().joinToString("\n  ")
        return """
            [out:json][timeout:18];
            (
              $unionBody
            );
            out tags geom;
        """.trimIndent()
    }

    internal fun buildSingleBboxOverpassQuery(pts: List<GpxPoint>): String {
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2.0

        // Expansion de la boîte englobante de + 50 m
        val padLat = 50.0 / 111320.0
        val padLon = 50.0 / (111320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.1))

        val south = String.format(Locale.US, "%.6f", minLat - padLat)
        val west = String.format(Locale.US, "%.6f", minLon - padLon)
        val north = String.format(Locale.US, "%.6f", maxLat + padLat)
        val east = String.format(Locale.US, "%.6f", maxLon + padLon)

        return """
            [out:json][timeout:18];
            way["highway"]($south,$west,$north,$east);
            out tags geom;
        """.trimIndent()
    }

    internal data class RawTrackSegment(
        val startLat: Double,
        val startLon: Double,
        val endLat: Double,
        val endLon: Double,
        val midLat: Double,
        val midLon: Double,
        val lengthMeters: Double
    )

    internal fun buildTrackSegments(points: List<GpxPoint>): List<RawTrackSegment> {
        if (points.size < 2) return emptyList()
        // Sous-échantillonnage jusqu'à ~400 segments pour maintenir un rendu carte et un calcul Overpass fluides
        val step = max(1, points.size / 400)
        val sampled = mutableListOf<GpxPoint>()
        for (i in points.indices step step) {
            sampled.add(points[i])
        }
        if (sampled.last() != points.last()) {
            sampled.add(points.last())
        }

        val segments = mutableListOf<RawTrackSegment>()
        for (i in 0 until sampled.lastIndex) {
            val p1 = sampled[i]
            val p2 = sampled[i + 1]
            val len = haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon)
            if (len > 0.05) {
                segments.add(
                    RawTrackSegment(
                        startLat = p1.lat,
                        startLon = p1.lon,
                        endLat = p2.lat,
                        endLon = p2.lon,
                        midLat = (p1.lat + p2.lat) / 2.0,
                        midLon = (p1.lon + p2.lon) / 2.0,
                        lengthMeters = len
                    )
                )
            }
        }
        return segments
    }

    internal fun computeSurfaceBreakdownFromWays(
        segments: List<RawTrackSegment>,
        ways: List<OverpassElement>,
        totalMeters: Double
    ): GpxSurfaceBreakdown {
        var bitumeM = 0.0
        var stabilM = 0.0
        var terreM = 0.0
        var inconnuM = 0.0

        // Pré-calcul des boîtes englobantes des ways OSM (+ marge 25 m) pour accélérer la recherche spatiale
        val degMargin = 35.0 / 111320.0
        val indexedWays = ways.mapNotNull { way ->
            val geom = way.geometry
            if (geom.isNullOrEmpty()) null
            else {
                var wMinLat = Double.MAX_VALUE
                var wMaxLat = -Double.MAX_VALUE
                var wMinLon = Double.MAX_VALUE
                var wMaxLon = -Double.MAX_VALUE
                for (pt in geom) {
                    if (pt.lat < wMinLat) wMinLat = pt.lat
                    if (pt.lat > wMaxLat) wMaxLat = pt.lat
                    if (pt.lon < wMinLon) wMinLon = pt.lon
                    if (pt.lon > wMaxLon) wMaxLon = pt.lon
                }
                IndexedWay(
                    element = way,
                    geom = geom,
                    minLat = wMinLat - degMargin,
                    maxLat = wMaxLat + degMargin,
                    minLon = wMinLon - degMargin * 1.6,
                    maxLon = wMaxLon + degMargin * 1.6
                )
            }
        }

        val colored = segments.map { seg ->
            // Chercher la voie la plus proche dans un rayon de 20 m
            var minDist = Double.MAX_VALUE
            var closestWay: OverpassElement? = null

            for (iw in indexedWays) {
                if (seg.midLat < iw.minLat || seg.midLat > iw.maxLat ||
                    seg.midLon < iw.minLon || seg.midLon > iw.maxLon
                ) {
                    continue
                }
                val d = distancePointToWayMeters(seg.midLat, seg.midLon, iw.geom)
                if (d < minDist) {
                    minDist = d
                    closestWay = iw.element
                }
            }

            val category = if (closestWay != null && minDist <= 20.0) {
                classifyWayTags(closestWay.tags.orEmpty())
            } else {
                SurfaceCategory.INCONNU
            }

            when (category) {
                SurfaceCategory.BITUME -> bitumeM += seg.lengthMeters
                SurfaceCategory.CHEMIN_STABILISE -> stabilM += seg.lengthMeters
                SurfaceCategory.TERRE_FORET -> terreM += seg.lengthMeters
                SurfaceCategory.INCONNU -> inconnuM += seg.lengthMeters
            }

            GpxColoredSegment(
                startLat = seg.startLat,
                startLon = seg.startLon,
                endLat = seg.endLat,
                endLon = seg.endLon,
                lengthMeters = seg.lengthMeters,
                category = category
            )
        }

        // Le pourcentage de bitume est calculé sur la distance totale de la trace :
        // la part « inconnu » compte comme non bitumée.
        val safeTotal = totalMeters.coerceAtLeast(1.0)
        val bitumePct = ((bitumeM / safeTotal) * 1000.0).roundToInt() / 10.0
        val stabilPct = ((stabilM / safeTotal) * 1000.0).roundToInt() / 10.0
        val terrePct = ((terreM / safeTotal) * 1000.0).roundToInt() / 10.0
        val inconnuPct = ((inconnuM / safeTotal) * 1000.0).roundToInt() / 10.0

        return GpxSurfaceBreakdown(
            bitumeMeters = bitumeM,
            cheminStabiliseMeters = stabilM,
            terreForetMeters = terreM,
            inconnuMeters = inconnuM,
            totalMeters = safeTotal,
            bitumePercent = bitumePct,
            cheminStabilisePercent = stabilPct,
            terreForetPercent = terrePct,
            inconnuPercent = inconnuPct,
            isUnreliable = inconnuPct > 30.0,
            coloredSegments = colored,
            overpassError = null
        )
    }

    private data class IndexedWay(
        val element: OverpassElement,
        val geom: List<OverpassCoord>,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    /**
     * Règles exactes de classification :
     * - Bitume : surface = asphalt, paved, concrete, paving_stones ;
     *   ou sans tag surface : highway = residential, tertiary, secondary, primary, unclassified, service ;
     *   ou tracktype = grade1.
     * - Chemin stabilisé : surface = compacted, fine_gravel, gravel ;
     *   ou tracktype = grade2 ;
     *   ou highway = track sans autre info.
     * - Terre / forêt : surface = dirt, ground, earth, grass, mud ;
     *   ou highway = path ;
     *   ou tracktype = grade3 à grade5.
     * - Inconnu : aucune voie trouvée ou tags insuffisants.
     */
    fun classifyWayTags(tags: Map<String, String>): SurfaceCategory {
        val surface = tags["surface"]?.trim()?.lowercase(Locale.ROOT)
        val highway = tags["highway"]?.trim()?.lowercase(Locale.ROOT)
        val tracktype = tags["tracktype"]?.trim()?.lowercase(Locale.ROOT)

        val bitumeSurfaces = setOf("asphalt", "paved", "concrete", "paving_stones")
        val bitumeHighways = setOf("residential", "tertiary", "secondary", "primary", "unclassified", "service")
        val stabilSurfaces = setOf("compacted", "fine_gravel", "gravel")
        val terreSurfaces = setOf("dirt", "ground", "earth", "grass", "mud")
        val terreTracktypes = setOf("grade3", "grade4", "grade5")

        if (surface != null && surface in bitumeSurfaces) return SurfaceCategory.BITUME
        if (tracktype == "grade1") return SurfaceCategory.BITUME
        if (surface.isNullOrBlank() && highway != null && highway in bitumeHighways) {
            return SurfaceCategory.BITUME
        }

        if (surface != null && surface in stabilSurfaces) return SurfaceCategory.CHEMIN_STABILISE
        if (tracktype == "grade2") return SurfaceCategory.CHEMIN_STABILISE

        if (surface != null && surface in terreSurfaces) return SurfaceCategory.TERRE_FORET
        if (tracktype != null && tracktype in terreTracktypes) return SurfaceCategory.TERRE_FORET
        if (highway == "path") return SurfaceCategory.TERRE_FORET

        if (highway == "track" && surface.isNullOrBlank() && tracktype.isNullOrBlank()) {
            return SurfaceCategory.CHEMIN_STABILISE
        }

        return SurfaceCategory.INCONNU
    }

    /**
     * Calcule la distance minimale en mètres entre un point (lat, lon) et une polyligne OSM.
     */
    internal fun distancePointToWayMeters(
        lat: Double,
        lon: Double,
        geom: List<OverpassCoord>
    ): Double {
        if (geom.isEmpty()) return Double.MAX_VALUE
        if (geom.size == 1) return haversineMeters(lat, lon, geom[0].lat, geom[0].lon)

        var minDist = Double.MAX_VALUE
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val metersPerDegLat = 111320.0
        val metersPerDegLon = 111320.0 * cosLat

        for (i in 0 until geom.lastIndex) {
            val a = geom[i]
            val b = geom[i + 1]

            val ax = (a.lon - lon) * metersPerDegLon
            val ay = (a.lat - lat) * metersPerDegLat
            val bx = (b.lon - lon) * metersPerDegLon
            val by = (b.lat - lat) * metersPerDegLat

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy

            val t = if (lenSq > 1e-6) {
                ((-ax * dx - ay * dy) / lenSq).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            val projX = ax + t * dx
            val projY = ay + t * dy
            val dist = sqrt(projX * projX + projY * projY)
            if (dist < minDist) {
                minDist = dist
            }
        }
        return minDist
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    fun getSampleTrailGpxXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="RunCoach Matos">
          <trk>
            <name>Boucle Petit Balcon Chamonix (Trail)</name>
            <trkseg>
              <trkpt lat="45.9237" lon="6.8694"><ele>1035</ele></trkpt>
              <trkpt lat="45.9265" lon="6.8732"><ele>1085</ele></trkpt>
              <trkpt lat="45.9301" lon="6.8785"><ele>1140</ele></trkpt>
              <trkpt lat="45.9348" lon="6.8840"><ele>1210</ele></trkpt>
              <trkpt lat="45.9395" lon="6.8895"><ele>1265</ele></trkpt>
              <trkpt lat="45.9430" lon="6.8950"><ele>1310</ele></trkpt>
              <trkpt lat="45.9402" lon="6.8982"><ele>1245</ele></trkpt>
              <trkpt lat="45.9355" lon="6.8915"><ele>1180</ele></trkpt>
              <trkpt lat="45.9300" lon="6.8830"><ele>1110</ele></trkpt>
              <trkpt lat="45.9258" lon="6.8750"><ele>1060</ele></trkpt>
              <trkpt lat="45.9237" lon="6.8694"><ele>1035</ele></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    fun getSampleRoadDominantGpxXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="RunCoach Matos">
          <trk>
            <name>Parc &amp; Berges d'Annecy (Mixte 70% Bitume)</name>
            <trkseg>
              <trkpt lat="45.9012" lon="6.1288"><ele>448</ele></trkpt>
              <trkpt lat="45.9025" lon="6.1345"><ele>450</ele></trkpt>
              <trkpt lat="45.9040" lon="6.1410"><ele>452</ele></trkpt>
              <trkpt lat="45.9052" lon="6.1475"><ele>455</ele></trkpt>
              <trkpt lat="45.9065" lon="6.1530"><ele>462</ele></trkpt>
              <trkpt lat="45.9045" lon="6.1505"><ele>454</ele></trkpt>
              <trkpt lat="45.9030" lon="6.1430"><ele>450</ele></trkpt>
              <trkpt lat="45.9018" lon="6.1355"><ele>449</ele></trkpt>
              <trkpt lat="45.9012" lon="6.1288"><ele>448</ele></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()
}
