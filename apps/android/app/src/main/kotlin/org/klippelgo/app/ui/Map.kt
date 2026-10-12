package org.klippelgo.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import org.klippelgo.app.R
import org.klippelgo.app.ui.tags.Tags
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.klippelgo.core.Bounds
import org.klippelgo.core.Coordinate
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/** How a line on the map is drawn. */
enum class LineKind {
    /** A route not yet being driven: dotted. */
    PLANNED,

    /** The route being driven: solid blue. */
    UNDER_WAY,

    /** A ride waiting for the driver's answer, or queued after the current one: another colour. */
    NEXT,

    /** The way to a pickup, between rides: thin and grey. */
    LINK,
}

/**
 * A route on the map. `ahead` is what is left of it once a car is on it: only that part is drawn, and the
 * whole route is still what the map frames.
 */
data class MapLine(val points: List<Coordinate>, val kind: LineKind, val ahead: List<Coordinate>? = null)

/** How far off a route a car may be and still be taken as driving it, in metres. */
private const val ON_ROUTE_M = 150.0

/** How far ahead of where the car last was it is looked for first, in metres: half a minute of fast road. */
private const val LOOK_AHEAD_M = 800.0

private fun metres(a: Coordinate, b: Coordinate): Double {
    val north = (b.latE7 - a.latE7) * 0.0111195
    val east = (b.lonE7 - a.lonE7) * 0.0111195 * kotlin.math.cos(Math.toRadians(a.latE7 / 1e7))
    return kotlin.math.hypot(north, east)
}

/**
 * How far along a route a car has got. It only goes forward, and looks for the car just ahead of where it last
 * was before anywhere else: a route that comes back past the same place (down an avenue and back, round a
 * block) would otherwise lose, on one imprecise fix, the stretch still to be driven. A car found further on
 * than that is drawn there but not remembered there, so one wrong position does not shorten the route for
 * the rest of the ride.
 */
class RouteProgress(private val route: List<Coordinate>) {
    private var segment = 0

    /**
     * The part of the route still ahead of a car at `at`: from the point of the route nearest the car to its
     * end. Null when there is no position or it is not on the route, so a position that is wrong, or a lie,
     * leaves the whole route on the map instead of erasing it.
     */
    fun ahead(at: Coordinate?): List<Coordinate>? {
        if (at == null || route.size < 2) return null
        var best: Triple<Double, Int, Double>? = null
        var gone = 0.0
        var within = true
        for (i in segment until route.size - 1) {
            // Past the stretch just ahead, the search goes on only while the car has not been found.
            if (within && gone > LOOK_AHEAD_M) {
                if (best != null && best.first <= ON_ROUTE_M) break
                // Not there: the nearest point of all the road left, wherever it is.
                within = false
            }
            val a = route[i]
            val b = route[i + 1]
            // Metres on a flat sheet around the car: good to centimetres over the length of a street.
            val east = 0.0111195 * kotlin.math.cos(Math.toRadians(at.latE7 / 1e7))
            val ax = (a.lonE7 - at.lonE7) * east
            val ay = (a.latE7 - at.latE7) * 0.0111195
            val dx = (b.lonE7 - a.lonE7) * east
            val dy = (b.latE7 - a.latE7) * 0.0111195
            val length = dx * dx + dy * dy
            val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
            val distance = kotlin.math.hypot(ax + dx * t, ay + dy * t)
            if (best == null || distance < best.first) best = Triple(distance, i, t)
            gone += metres(a, b)
        }
        val (distance, found, share) = best ?: return null
        if (distance > ON_ROUTE_M) return null
        // Remembered only when it is a step the car could have made.
        if (within) segment = found
        val a = route[found]
        val b = route[found + 1]
        val here = Coordinate((a.latE7 + (b.latE7 - a.latE7) * share).toInt(), (a.lonE7 + (b.lonE7 - a.lonE7) * share).toInt())
        return listOf(here) + route.subList(found + 1, route.size)
    }
}

/** A region shaded on the map: where a requested trip starts or ends, to about a square kilometre. */
data class MapArea(val bounds: Bounds, val pickup: Boolean)

/**
 * What a map shows. The tiles, the style, the fonts and the icons all come from this phone: the pack's
 * `basemap.pmtiles` and the style bundled in the app's assets. No tile, font or sprite server is asked.
 */
data class MapScene(
    /** Path of the pack's `basemap.pmtiles`. */
    val basemap: String,
    /** Where the map looks when there is nothing to frame. */
    val centre: Coordinate,
    /** Routes to draw and frame. A ride's route has its ends marked; a link has none. */
    val lines: List<MapLine> = emptyList(),
    /** Regions to shade and frame. */
    val areas: List<MapArea> = emptyList(),
    /** This phone's own position. */
    val me: Coordinate? = null,
    /** The driver's position, on the rider's map. */
    val driver: Coordinate? = null,
    /** Called with the point under the middle of the map each time the rider stops moving it. */
    val onCentre: ((Coordinate) -> Unit)? = null,
)

private fun Coordinate.latLng() = LatLng(latE7 / 1e7, lonE7 / 1e7)

private fun Coordinate.json() = "[${lonE7 / 1e7},${latE7 / 1e7}]"

private fun Color.hex() = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

/** The bundled style, pointed at the pack's tiles. */
private fun styleJson(context: Context, basemap: String): String =
    context.assets.open("map/style.json").bufferedReader().use { it.readText() }.replace("{basemap}", basemap)

private fun Bounds.corners() = listOf(
    southWest,
    Coordinate(southWest.latE7, northEast.lonE7),
    northEast,
    Coordinate(northEast.latE7, southWest.lonE7),
)

private fun sceneGeoJson(lines: List<MapLine>, areas: List<MapArea>, me: Coordinate?, driver: Coordinate? = null): String {
    val features = mutableListOf<String>()
    for (area in areas) {
        val ring = (area.bounds.corners() + area.bounds.southWest).joinToString(",") { it.json() }
        features += """{"type":"Feature","properties":{"area":"${if (area.pickup) "pickup" else "dropoff"}"},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }
    fun point(at: Coordinate, kind: String) =
        """{"type":"Feature","properties":{"point":"$kind"},"geometry":{"type":"Point","coordinates":${at.json()}}}"""
    for (line in lines.filter { it.points.size >= 2 }) {
        val kind = line.kind.name
        features += """{"type":"Feature","properties":{"line":"$kind"},"geometry":{"type":"LineString","coordinates":[${(line.ahead ?: line.points).joinToString(",") { it.json() }}]}}"""
        if (line.kind != LineKind.LINK) {
            val next = line.kind == LineKind.NEXT
            // A ride being driven has left its pickup behind: the car is where the route now starts.
            if (line.kind != LineKind.UNDER_WAY) features += point(line.points.first(), if (next) "next-pickup" else "pickup")
            features += point(line.points.last(), if (next) "next-dropoff" else "dropoff")
        }
    }
    me?.let { features += point(it, "me") }
    driver?.let { features += point(it, "driver") }
    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

private const val SOURCE = "klippelgo"

/** From the whole region down to single buildings. The pack's tiles go to zoom 15 and are stretched beyond. */
private const val MIN_ZOOM = 9.0
private const val MAX_ZOOM = 19.0

/** The colour of a ride that is not the current one, picked to stand apart from the blue and the black. */
val NextRide = Color(0xFFE8590C)

private fun only(property: String, value: String) = Expression.eq(Expression.get(property), Expression.literal(value))

/** The map itself: MapLibre drawing the pack's vector tiles. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PackMap(scene: MapScene, ink: Color, accent: Color, ring: Color, muted: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onCentre by rememberUpdatedState(scene.onCentre)
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    // Whether a hand has moved or zoomed the map since it last framed something new. While it has, the map
    // is the user's: it frames nothing by itself.
    val byHandSinceFramed = remember { booleanArrayOf(false) }
    // Where a hand last left the middle of the map: that centre came from the map and is not given back to it.
    val centredByHand = remember { arrayOfNulls<Coordinate>(1) }
    // Whether the move the map is making was started by a hand, and whether a finger is on the map now.
    val byHand = remember { booleanArrayOf(false) }
    val fingerDown = remember { booleanArrayOf(false) }

    val view = remember(scene.basemap) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            // Keep a pinch or a drag on the map from being taken over by whatever the map sits in.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> fingerDown[0] = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDown[0] = false
                }
                false
            }
            getMapAsync { map ->
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                // Pinch, double tap, and double tap then drag all zoom. The buttons below do too, for one hand.
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isDoubleTapGesturesEnabled = true
                map.uiSettings.isQuickZoomGesturesEnabled = true
                map.setMinZoomPreference(MIN_ZOOM)
                map.setMaxZoomPreference(MAX_ZOOM)
                map.cameraPosition = CameraPosition.Builder().target(scene.centre.latLng()).zoom(15.0).build()
                // Only a hand moving the map moves the pin. The map framing a route by itself must not.
                map.addOnCameraMoveStartedListener { reason ->
                    byHand[0] = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                    if (byHand[0]) byHandSinceFramed[0] = true
                }
                map.addOnCameraIdleListener {
                    if (!byHand[0]) return@addOnCameraIdleListener
                    val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                    val at = Coordinate((target.latitude * 1e7).toInt(), (target.longitude * 1e7).toInt())
                    centredByHand[0] = at
                    onCentre?.invoke(at)
                }
                map.setStyle(Style.Builder().fromJson(styleJson(context, scene.basemap))) { style ->
                    style.addSource(GeoJsonSource(SOURCE, sceneGeoJson(emptyList(), emptyList(), null)))
                    // Regions under everything else, in the colour of a ride not yet taken so they are not read as
                    // part of the trip being driven: the pickup filled and solid, the drop-off lighter and dashed.
                    for ((area, opacity, dash) in listOf(Triple("pickup", 0.28f, arrayOf(1f, 0f)), Triple("dropoff", 0.10f, arrayOf(2f, 2f)))) {
                        style.addLayer(
                            FillLayer("klippelgo-area-$area", SOURCE).withProperties(
                                PropertyFactory.fillColor(NextRide.hex()),
                                PropertyFactory.fillOpacity(opacity),
                            ).withFilter(only("area", area)),
                        )
                        style.addLayer(
                            LineLayer("klippelgo-area-$area-edge", SOURCE).withProperties(
                                PropertyFactory.lineColor(NextRide.hex()),
                                PropertyFactory.lineWidth(2.5f),
                                PropertyFactory.lineDasharray(dash),
                            ).withFilter(only("area", area)),
                        )
                    }
                    val solid = arrayOf(1f, 0f)
                    val dotted = arrayOf(0.2f, 2f)
                    // Links first, so the rides they join are drawn over them.
                    for ((kind, colour, width, dash) in listOf(
                        Stroke(LineKind.LINK, muted, 3f, arrayOf(2f, 2f)),
                        Stroke(LineKind.NEXT, NextRide, 5f, solid),
                        Stroke(LineKind.PLANNED, ink, 5f, dotted),
                        Stroke(LineKind.UNDER_WAY, accent, 5f, solid),
                    )) {
                        style.addLayer(
                            LineLayer("klippelgo-line-${kind.name}", SOURCE).withProperties(
                                PropertyFactory.lineColor(colour.hex()),
                                PropertyFactory.lineWidth(width),
                                PropertyFactory.lineDasharray(dash),
                                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                            ).withFilter(only("line", kind.name)),
                        )
                    }
                    for ((point, colour) in listOf("pickup" to accent, "dropoff" to ink, "next-pickup" to NextRide, "next-dropoff" to NextRide)) {
                        style.addLayer(
                            CircleLayer("klippelgo-$point", SOURCE).withProperties(
                                PropertyFactory.circleRadius(8f),
                                PropertyFactory.circleColor(colour.hex()),
                                PropertyFactory.circleStrokeWidth(3f),
                                PropertyFactory.circleStrokeColor(ring.hex()),
                            ).withFilter(only("point", point)),
                        )
                    }
                    // The driver, on the rider's map: the colour of a ride on its way, in a halo, so it reads as
                    // something moving and not as one of the route's ends.
                    style.addLayer(
                        CircleLayer("klippelgo-driver-halo", SOURCE).withProperties(
                            PropertyFactory.circleRadius(20f),
                            PropertyFactory.circleColor(NextRide.hex()),
                            PropertyFactory.circleOpacity(0.25f),
                        ).withFilter(only("point", "driver")),
                    )
                    style.addLayer(
                        CircleLayer("klippelgo-driver", SOURCE).withProperties(
                            PropertyFactory.circleRadius(9f),
                            PropertyFactory.circleColor(NextRide.hex()),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor(ring.hex()),
                        ).withFilter(only("point", "driver")),
                    )
                    // This phone: a blue dot in a soft halo, over everything else.
                    style.addLayer(
                        CircleLayer("klippelgo-me-halo", SOURCE).withProperties(
                            PropertyFactory.circleRadius(18f),
                            PropertyFactory.circleColor(accent.hex()),
                            PropertyFactory.circleOpacity(0.2f),
                        ).withFilter(only("point", "me")),
                    )
                    style.addLayer(
                        CircleLayer("klippelgo-me", SOURCE).withProperties(
                            PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleColor(accent.hex()),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor(ring.hex()),
                        ).withFilter(only("point", "me")),
                    )
                    ready = map to style
                }
            }
        }
    }

    // MapLibre needs the host's lifecycle: it stops drawing when the app leaves the screen.
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(ready, scene.lines, scene.areas, scene.me, scene.driver) {
        val (_, style) = ready ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(sceneGeoJson(scene.lines, scene.areas, scene.me, scene.driver))
    }
    // Under a pin the middle of the map is the place meant. When that place moves without a hand on the map,
    // as when the pickup follows the phone, the map goes there, or the pin would point somewhere else.
    val pinned = scene.onCentre != null
    LaunchedEffect(ready, pinned, scene.centre) {
        val (map, _) = ready ?: return@LaunchedEffect
        // With a finger on the map the hand decides: where it lets go becomes the pickup.
        if (!pinned || fingerDown[0] || scene.centre == centredByHand[0]) return@LaunchedEffect
        val target = map.cameraPosition.target ?: return@LaunchedEffect
        if (abs(target.latitude * 1e7 - scene.centre.latE7) < 10 && abs(target.longitude * 1e7 - scene.centre.lonE7) < 10) return@LaunchedEffect
        // At once and not as a hand's move: an animation would leave a moment in which a drag starting, or a
        // fling still running, is taken for the other, and the pin would name one place and point at another.
        byHand[0] = false
        centredByHand[0] = null
        map.moveCamera(CameraUpdateFactory.newLatLng(scene.centre.latLng()))
    }
    // The map frames what it shows when there is something new to show: a ride's route appearing, leaving or
    // changing state, or the regions of a request. Not when a position moves, and not when the way to a pickup
    // is drawn again from where the car now is, which happens every hundred metres of driving: following
    // those would take the map out of the hand that zoomed or moved it, every few seconds, for a whole ride.
    val latest by rememberUpdatedState(scene)
    fun frame(map: MapLibreMap) {
        val s = latest
        val points = s.lines.flatMap { it.points } + s.areas.flatMap { it.bounds.corners() } + listOfNotNull(s.me, s.driver)
        when {
            points.size >= 2 -> {
                val bounds = LatLngBounds.Builder().includes(points.map { it.latLng() }).build()
                // More room at the bottom: the sheet's round button and its corners lie over that edge of the map.
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120, 120, 120, 260))
            }
            points.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0].latLng(), 15.0))
        }
    }
    // A ride by its whole route and its state: the part already driven coming off it is no news to frame.
    val rides = scene.lines.filter { it.kind != LineKind.LINK }.map { it.points to it.kind }
    LaunchedEffect(ready, rides, scene.areas) {
        val (map, _) = ready ?: return@LaunchedEffect
        byHandSinceFramed[0] = false
        frame(map)
    }
    // A position showing up for the first time, this phone's or the driver's, is worth a look too, unless the
    // user has taken the map in hand. A position that comes and goes with the signal must not move it each time.
    val located = scene.me != null || scene.driver != null
    LaunchedEffect(ready, located) {
        val (map, _) = ready ?: return@LaunchedEffect
        if (!byHandSinceFramed[0]) frame(map)
    }

    // The tag goes on the box: the map itself is an Android view, which takes no tag from Compose.
    Box(modifier.testTag(Tags.MAP)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The buttons are a hand on the map as much as a pinch is.
            ZoomButton("+", stringResource(R.string.zoom_in), Tags.MAP_ZOOM_IN) {
                byHandSinceFramed[0] = true
                ready?.first?.animateCamera(CameraUpdateFactory.zoomIn())
            }
            ZoomButton("−", stringResource(R.string.zoom_out), Tags.MAP_ZOOM_OUT) {
                byHandSinceFramed[0] = true
                ready?.first?.animateCamera(CameraUpdateFactory.zoomOut())
            }
        }
    }
}

private data class Stroke(val kind: LineKind, val colour: Color, val width: Float, val dash: Array<Float>)

@Composable
private fun ZoomButton(symbol: String, description: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.size(44.dp).testTag(tag).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
    }
}
