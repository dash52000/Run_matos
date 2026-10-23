package com.example.data.remote

import com.example.data.model.GpxColoredSegment
import com.example.data.model.GpxPoint
import com.example.data.model.GpxSurfaceBreakdown
import com.example.data.model.GpxTrackAnalysis
import com.example.data.model.SurfaceCategory
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import com.example.data.local.SurfaceCacheStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.io.StringReader
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

    // Serveurs Overpass publics à couverture mondiale (wiki OSM), essayés dans l'ordre avec bascule.
    // (overpass.osm.ch, utilisé auparavant en premier, ne couvre que la Suisse : il renvoyait une réponse vide.)
    // Mesuré le 23/10/2026 (zone Virton, 939 voies) : 26 à 34 s de réponse quand les serveurs sont chargés,
    // ou refus immédiat 502/504 « at capacity ». D'où le délai de lecture long et le second passage.
    internal val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    /** Délai serveur demandé dans la requête : bas pour être accepté plus souvent par un serveur chargé. */
    internal const val QUERY_TIMEOUT_SECONDS = 25

    /** Budget total de l'analyse (tous serveurs et passages confondus). */
    private const val TOTAL_BUDGET_MILLIS = 4L * 60L * 1000L

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

    // Un serveur chargé met souvent ~30 s à répondre (file d'attente + calcul) : on attend jusqu'à 75 s.
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(userAgentInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val overpassService: OverpassApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://overpass-api.de/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OverpassApiService::class.java)
    }

    /** Seuil d'hystérésis du D+ : les variations d'altitude plus petites sont du bruit GPS/baro. */
    const val ELEVATION_HYSTERESIS_METERS = 3.0

    /**
     * Parse un contenu XML GPX, extrait le nom de la trace et les points de trace (trkpt),
     * ou à défaut d'itinéraire (rtept). Les waypoints (wpt : ravitos, points d'intérêt) sont ignorés
     * car ils ne font pas partie du tracé. Calcule la distance totale (km), le D+ (m) et le point de départ.
     */
    fun parseGpxContent(rawXml: String, fallbackName: String = "Trace GPX importée"): GpxTrackAnalysis? {
        val trackPoints = mutableListOf<GpxPoint>()
        val routePoints = mutableListOf<GpxPoint>()
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
            var currentPointKind = ""
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT).substringAfterLast(':')
                        currentTag = tag
                        if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                            insidePoint = true
                            currentPointKind = tag
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
                            if (currentPointKind != "wpt" &&
                                currentLat != null && currentLon != null &&
                                currentLat.isFinite() && currentLat in -90.0..90.0 &&
                                currentLon.isFinite() && currentLon in -180.0..180.0
                            ) {
                                // Valider et borner l'altitude (entre -500m et 9000m pour éliminer les codes d'erreur 99999m)
                                val sanitizedEle = currentEle?.takeIf { it.isFinite() && it in -500.0..9000.0 }
                                val point = GpxPoint(lat = currentLat, lon = currentLon, ele = sanitizedEle)
                                if (currentPointKind == "trkpt") trackPoints.add(point) else routePoints.add(point)
                            }
                            insidePoint = false
                            currentPointKind = ""
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            return null
        }

        // Trace enregistrée en priorité ; sinon l'itinéraire (rtept) d'un fichier de planification.
        val points = if (trackPoints.size >= 2) trackPoints else routePoints
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

        // Hystérésis de 3 m contre le bruit d'altitude et élimination des faux décrochages (sauts > 120 m sans déplacement horizontal)
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
                    if (diff >= ELEVATION_HYSTERESIS_METERS) {
                        dPlusMeters += diff
                        lastEle = ele2
                    } else if (diff <= -ELEVATION_HYSTERESIS_METERS) {
                        lastEle = ele2
                    }
                }
            } else if (ele2 != null) {
                lastEle = ele2
            }
        }

        // Une trace sans longueur mesurable est invalide : on ne lui invente pas de distance.
        if (!totalDistanceMeters.isFinite() || totalDistanceMeters < 50.0) return null
        val distanceKm = (((totalDistanceMeters / 1000.0) * 10.0).roundToInt() / 10.0).coerceIn(0.1, 1000.0)

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

    /** Résultat d'une tentative sur un serveur. */
    internal enum class AttemptOutcome { SUCCESS, EMPTY, BUSY, TIMEOUT, OFFLINE, OTHER_ERROR }

    internal fun classifyFailure(e: Throwable): AttemptOutcome = when (e) {
        is HttpException -> if (e.code() in setOf(429, 502, 503, 504)) AttemptOutcome.BUSY else AttemptOutcome.OTHER_ERROR
        is SocketTimeoutException, is InterruptedIOException -> AttemptOutcome.TIMEOUT
        is UnknownHostException -> AttemptOutcome.OFFLINE
        else -> AttemptOutcome.OTHER_ERROR
    }

    internal fun failureMessage(outcomes: Collection<AttemptOutcome>, attempts: Int): String = when {
        outcomes.isEmpty() -> "Serveurs OpenStreetMap non interrogés."
        outcomes.all { it == AttemptOutcome.OFFLINE } -> "Hors-ligne : serveurs OpenStreetMap injoignables."
        outcomes.all { it == AttemptOutcome.EMPTY } -> "Aucune voie OpenStreetMap trouvée autour de la trace."
        outcomes.any { it == AttemptOutcome.BUSY || it == AttemptOutcome.TIMEOUT } ->
            "Serveurs OpenStreetMap saturés ($attempts tentatives sans réponse à temps). Réessaie un peu plus tard."
        else -> "Serveurs OpenStreetMap momentanément indisponibles."
    }

    /**
     * Interroge l'API Overpass dans l'emprise de la trace + 50 m pour récupérer les voies (ways)
     * avec leurs tags highway, surface et tracktype.
     * - Découpe l'emprise en sous-boîtes le long du corridor de la trace si elle est grande.
     * - Essaie plusieurs serveurs publics, en deux passages espacés, dans un budget de 4 min :
     *   les serveurs publics sont souvent saturés (réponse en ~30 s ou refus « at capacity »).
     * - Une analyse réussie est gardée en mémoire et sur disque ([persistentCache]) : plus besoin du réseau ensuite.
     * - Si rien ne répond, renvoie un revêtement 100 % « inconnu » avec le message : aucune répartition
     *   n'est inventée, et cet échec n'est pas mis en cache pour pouvoir réessayer.
     */
    suspend fun analyzeSurfaceWithOverpass(
        track: GpxTrackAnalysis,
        forceRefresh: Boolean = false,
        persistentCache: SurfaceCacheStore? = null
    ): GpxSurfaceBreakdown {
        if (!forceRefresh) {
            surfaceAnalysisCache[track.trackIdHash]?.let { return it }
            persistentCache?.load(track.trackIdHash)?.let {
                surfaceAnalysisCache[track.trackIdHash] = it
                return it
            }
        } else {
            surfaceAnalysisCache.remove(track.trackIdHash)
        }

        val pts = track.points
        val rawSegments = buildTrackSegments(pts)
        val totalMeters = rawSegments.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
        val query = buildOptimizedOverpassQuery(pts)

        val startedAt = System.currentTimeMillis()
        val outcomes = mutableListOf<AttemptOutcome>()

        for (round in 0 until 2) {
            if (round > 0) delay(5_000L)
            for (endpoint in overpassEndpoints) {
                if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MILLIS) break
                try {
                    val response = overpassService.queryOverpassPost(endpoint, query)
                    val ways = response.elements.orEmpty()
                        .filter { it.type == "way" && !it.geometry.isNullOrEmpty() }
                        .distinctBy { it.id }
                    if (ways.isEmpty()) {
                        // Réponse vide (ou requête coupée côté serveur) : on tente le serveur suivant.
                        outcomes.add(AttemptOutcome.EMPTY)
                        continue
                    }
                    val result = computeSurfaceBreakdownFromWays(rawSegments, ways, totalMeters)
                    surfaceAnalysisCache[track.trackIdHash] = result
                    persistentCache?.save(track.trackIdHash, result)
                    return result
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val outcome = classifyFailure(e)
                    outcomes.add(outcome)
                    // Hors-ligne : inutile d'insister sur les autres serveurs.
                    if (outcome == AttemptOutcome.OFFLINE && outcomes.all { it == AttemptOutcome.OFFLINE }) {
                        return buildUnknownSurfaceBreakdown(rawSegments, totalMeters, failureMessage(outcomes, outcomes.size) + " Revêtement inconnu, règles chaussures classiques.")
                    }
                }
            }
            // Deux passages seulement si les serveurs étaient saturés (pas s'ils ont répondu « vide »).
            if (outcomes.none { it == AttemptOutcome.BUSY || it == AttemptOutcome.TIMEOUT }) break
        }

        val message = failureMessage(outcomes, outcomes.size) + " Revêtement inconnu, règles chaussures classiques."
        return buildUnknownSurfaceBreakdown(rawSegments, totalMeters, message)
    }

    /** Revêtement 100 % inconnu (trace grise) quand OSM ne fournit rien : rien n'est estimé. */
    internal fun buildUnknownSurfaceBreakdown(
        rawSegments: List<RawTrackSegment>,
        totalMeters: Double,
        message: String
    ): GpxSurfaceBreakdown {
        return GpxSurfaceBreakdown(
            bitumeMeters = 0.0,
            cheminStabiliseMeters = 0.0,
            terreForetMeters = 0.0,
            inconnuMeters = totalMeters,
            totalMeters = totalMeters,
            bitumePercent = 0.0,
            cheminStabilisePercent = 0.0,
            terreForetPercent = 0.0,
            inconnuPercent = 100.0,
            isUnreliable = true,
            coloredSegments = rawSegments.map {
                GpxColoredSegment(it.startLat, it.startLon, it.endLat, it.endLon, it.lengthMeters, SurfaceCategory.INCONNU)
            },
            overpassError = message,
            isManualOrOfflineEstimate = false
        )
    }

    /**
     * Répartition de revêtement saisie à la main. Les % s'appliquent au choix des chaussures,
     * mais la carte reste grise : on ne sait pas où se trouve chaque portion sur la trace.
     */
    fun buildCustomSurfaceBreakdown(
        track: GpxTrackAnalysis,
        bitumePct: Double,
        cheminPct: Double,
        terrePct: Double
    ): GpxSurfaceBreakdown {
        val rawSegments = buildTrackSegments(track.points)
        val totalMeters = rawSegments.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
        val rawTotal = bitumePct.coerceAtLeast(0.0) + cheminPct.coerceAtLeast(0.0) + terrePct.coerceAtLeast(0.0)
        if (rawTotal < 1.0) {
            return buildUnknownSurfaceBreakdown(rawSegments, totalMeters, "Répartition manuelle vide : revêtement inconnu.")
        }
        val normBitume = (bitumePct.coerceAtLeast(0.0) / rawTotal) * 100.0
        val normChemin = (cheminPct.coerceAtLeast(0.0) / rawTotal) * 100.0
        val normTerre = (terrePct.coerceAtLeast(0.0) / rawTotal) * 100.0

        return GpxSurfaceBreakdown(
            bitumeMeters = totalMeters * normBitume / 100.0,
            cheminStabiliseMeters = totalMeters * normChemin / 100.0,
            terreForetMeters = totalMeters * normTerre / 100.0,
            inconnuMeters = 0.0,
            totalMeters = totalMeters,
            bitumePercent = ((normBitume * 10.0).roundToInt()) / 10.0,
            cheminStabilisePercent = ((normChemin * 10.0).roundToInt()) / 10.0,
            terreForetPercent = ((normTerre * 10.0).roundToInt()) / 10.0,
            inconnuPercent = 0.0,
            isUnreliable = false,
            coloredSegments = rawSegments.map {
                GpxColoredSegment(it.startLat, it.startLon, it.endLat, it.endLon, it.lengthMeters, SurfaceCategory.INCONNU)
            },
            overpassError = null,
            isManualOrOfflineEstimate = true
        )
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
            [out:json][timeout:$QUERY_TIMEOUT_SECONDS];
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
            [out:json][timeout:$QUERY_TIMEOUT_SECONDS];
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
}
