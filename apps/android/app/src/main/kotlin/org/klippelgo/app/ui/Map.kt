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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.klippelgo.app.R
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

data class MapLine(val points: List<Coordinate>, val kind: LineKind)

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
        features += """{"type":"Feature","properties":{"line":"$kind"},"geometry":{"type":"LineString","coordinates":[${line.points.joinToString(",") { it.json() }}]}}"""
        if (line.kind != LineKind.LINK) {
            val next = line.kind == LineKind.NEXT
            features += point(line.points.first(), if (next) "next-pickup" else "pickup")
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

    val view = remember(scene.basemap) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            // Keep a pinch or a drag on the map from being taken over by whatever the map sits in.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
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
                var byHand = false
                map.addOnCameraMoveStartedListener { reason ->
                    byHand = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                }
                map.addOnCameraIdleListener {
                    if (!byHand) return@addOnCameraIdleListener
                    val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                    onCentre?.invoke(Coordinate((target.latitude * 1e7).toInt(), (target.longitude * 1e7).toInt()))
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
    // Frame the routes when they change, and the driver with them. Following every move of the driver would
    // fight the hand that is moving the map.
    val located = scene.me != null || scene.driver != null
    LaunchedEffect(ready, scene.lines, scene.areas, located) {
        val (map, _) = ready ?: return@LaunchedEffect
        val points = scene.lines.flatMap { it.points } + scene.areas.flatMap { it.bounds.corners() } + listOfNotNull(scene.me, scene.driver)
        when {
            points.size >= 2 -> {
                val bounds = LatLngBounds.Builder().includes(points.map { it.latLng() }).build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            }
            points.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0].latLng(), 15.0))
        }
    }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZoomButton("+", stringResource(R.string.zoom_in)) { ready?.first?.animateCamera(CameraUpdateFactory.zoomIn()) }
            ZoomButton("−", stringResource(R.string.zoom_out)) { ready?.first?.animateCamera(CameraUpdateFactory.zoomOut()) }
        }
    }
}

private data class Stroke(val kind: LineKind, val colour: Color, val width: Float, val dash: Array<Float>)

@Composable
private fun ZoomButton(symbol: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
    }
}
