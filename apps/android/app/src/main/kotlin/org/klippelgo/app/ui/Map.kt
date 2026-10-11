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
import org.klippelgo.core.Coordinate
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/**
 * What a map shows. The tiles, the style, the fonts and the icons all come from this phone: the pack's
 * `basemap.pmtiles` and the style bundled in the app's assets. No tile, font or sprite server is asked.
 */
data class MapScene(
    /** Path of the pack's `basemap.pmtiles`. */
    val basemap: String,
    /** Where the map looks when there is no route to frame. */
    val centre: Coordinate,
    /** A route to draw and frame, with its ends marked. */
    val route: List<Coordinate> = emptyList(),
    /** Solid once the trip is under way, dotted while it is only planned. */
    val underWay: Boolean = false,
    /** Called with the point under the middle of the map each time the rider stops moving it. */
    val onCentre: ((Coordinate) -> Unit)? = null,
)

private fun Coordinate.latLng() = LatLng(latE7 / 1e7, lonE7 / 1e7)

private fun Color.hex() = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

/** The bundled style, pointed at the pack's tiles. */
private fun styleJson(context: Context, basemap: String): String =
    context.assets.open("map/style.json").bufferedReader().use { it.readText() }.replace("{basemap}", basemap)

private fun routeGeoJson(route: List<Coordinate>): String {
    val line = route.joinToString(",") { "[${it.lonE7 / 1e7},${it.latE7 / 1e7}]" }
    fun point(c: Coordinate, end: String) =
        """{"type":"Feature","properties":{"end":"$end"},"geometry":{"type":"Point","coordinates":[${c.lonE7 / 1e7},${c.latE7 / 1e7}]}}"""
    val features = if (route.size < 2) "" else listOf(
        """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$line]}}""",
        point(route.first(), "pickup"),
        point(route.last(), "dropoff"),
    ).joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private const val ROUTE_SOURCE = "klippelgo-route"

/** From the whole region down to single buildings. The pack's tiles go to zoom 15 and are stretched beyond. */
private const val MIN_ZOOM = 9.0
private const val MAX_ZOOM = 19.0

/** The map itself: MapLibre drawing the pack's vector tiles. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PackMap(scene: MapScene, ink: Color, accent: Color, ring: Color, modifier: Modifier = Modifier) {
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
                map.addOnCameraIdleListener {
                    val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                    onCentre?.invoke(Coordinate((target.latitude * 1e7).toInt(), (target.longitude * 1e7).toInt()))
                }
                map.setStyle(Style.Builder().fromJson(styleJson(context, scene.basemap))) { style ->
                    style.addSource(GeoJsonSource(ROUTE_SOURCE, routeGeoJson(emptyList())))
                    style.addLayer(
                        LineLayer("klippelgo-route-line", ROUTE_SOURCE).withProperties(
                            PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    for ((end, colour) in listOf("pickup" to accent, "dropoff" to ink)) {
                        style.addLayer(
                            CircleLayer("klippelgo-$end", ROUTE_SOURCE).withProperties(
                                PropertyFactory.circleRadius(8f),
                                PropertyFactory.circleColor(colour.hex()),
                                PropertyFactory.circleStrokeWidth(3f),
                                PropertyFactory.circleStrokeColor(ring.hex()),
                            ).withFilter(org.maplibre.android.style.expressions.Expression.eq(
                                org.maplibre.android.style.expressions.Expression.get("end"),
                                org.maplibre.android.style.expressions.Expression.literal(end),
                            )),
                        )
                    }
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

    LaunchedEffect(ready, scene.route, scene.underWay) {
        val (map, style) = ready ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(routeGeoJson(scene.route))
        style.getLayerAs<LineLayer>("klippelgo-route-line")?.setProperties(
            PropertyFactory.lineColor((if (scene.underWay) accent else ink).hex()),
            PropertyFactory.lineDasharray(if (scene.underWay) arrayOf(1f, 0f) else arrayOf(0.2f, 2f)),
        )
        if (scene.route.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(scene.route.map { it.latLng() }).build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
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
