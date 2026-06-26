package com.ghostroute.app.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.format.DateUtils
import android.view.Gravity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ghostroute.app.R
import com.ghostroute.app.geocode.GeoResult
import com.ghostroute.app.location.LocationManagerSource
import com.ghostroute.app.map.BasemapProvider
import com.ghostroute.app.map.SunCalculator
import com.ghostroute.app.places.SavedPlace
import com.ghostroute.app.routing.RoutePoint
import com.ghostroute.app.data.CameraEntity
import com.ghostroute.app.routing.ScoredRoute
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Default view: Knoxville, TN — a central city inside the supported TN + Western NC region.
private val DEFAULT_TARGET = LatLng(35.9606, -83.9207)
private const val DEFAULT_ZOOM = 11.0
private const val FOLLOW_ZOOM = 15.5

private const val LOCATION_SOURCE_ID = "ghostroute-location-source"
private const val LOCATION_ACCURACY_LAYER = "ghostroute-location-accuracy"
private const val LOCATION_DOT_LAYER = "ghostroute-location-dot"
private const val LOCATION_ARROW_IMAGE = "ghostroute-location-arrow"
private const val PROP_BEARING = "bearing"

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var hasLocationPermission by remember {
        mutableStateOf(context.hasLocationPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLocationPermission = result.values.any { it }
    }

    if (!BasemapProvider.isBasemapInstalled(context)) {
        BasemapMissing(context = context, modifier = modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        val mapView = rememberMapViewWithLifecycle()

        // Holders shared between the map callback and the location collector.
        val mapHolder = remember { mutableStateOf<MapLibreMap?>(null) }
        val styleHolder = remember { mutableStateOf<Style?>(null) }
        val mapConfigured = remember { mutableStateOf(false) }
        var following by remember { mutableStateOf(true) }
        // Last good travel heading, so the map stays "heading-up" (rotates the way you're
        // driving) and doesn't spin back to north every time you slow to a stop.
        var lastBearing by remember { mutableFloatStateOf(0f) }
        // Measured height of the bottom sheet, so the speedometer + side controls float
        // just above it instead of hiding behind it.
        var sheetHeightPx by remember { mutableIntStateOf(0) }
        // Drag/collapse state for the bottom sheet: 0 = fully expanded, maxOffset = collapsed
        // to just a peek (the grab handle). The user can drag the handle to adjust it, and
        // touching the map collapses it so the map fills the screen.
        val sheetScope = rememberCoroutineScope()
        val sheetOffsetY = remember { Animatable(0f) }
        val density = LocalDensity.current
        val peekPx = with(density) { 38.dp.toPx() }
        fun maxSheetOffset() = (sheetHeightPx - peekPx).coerceAtLeast(0f)
        // A bump-counter the map gestures flip to request a collapse (they're plain
        // MapLibre listeners, so they can't animate directly).
        val collapseSignal = remember { mutableIntStateOf(0) }
        // Height of the sheet that's actually on-screen right now (full minus how far it's
        // slid down) — what the floating controls pad themselves above.
        val visibleSheetDp = with(density) {
            (sheetHeightPx - sheetOffsetY.value).coerceAtLeast(0f).toDp()
        }
        // Day/night map theme, seeded from the last known location + clock and then
        // kept current as location fixes arrive (flips automatically at sunset/sunrise).
        var night by remember {
            val loc = LocationManagerSource(context).lastKnownLocation()
            mutableStateOf(loc != null && SunCalculator.isNight(loc.latitude, loc.longitude, System.currentTimeMillis()))
        }
        // A holder (not a captured var) so the map long-press listener reads the
        // current location at click time.
        val locationHolder = remember { mutableStateOf<LatLng?>(null) }
        // The camera marker the user last tapped (shows a small identify card), or null.
        val selectedCameraHolder = remember { mutableStateOf<CameraInfo?>(null) }

        val viewModel: MapViewModel = viewModel()
        val cameras by viewModel.cameras.collectAsStateWithLifecycle()
        val camerasVisible = viewModel.camerasVisible

        // All saved places as labeled map pins: Home/Work (relabeled) + pinned favorites.
        val favMarkers = remember(viewModel.home, viewModel.work, viewModel.favorites) {
            buildList {
                viewModel.home?.let { add(it.copy(name = "Home")) }
                viewModel.work?.let { add(it.copy(name = "Work")) }
                addAll(viewModel.favorites)
            }
        }
        val syncState = viewModel.syncState

        // Touching the map (pan or tap) slides the "Where to?" sheet down to its peek so the
        // map fills the screen. Only while browsing — not while picking a route.
        LaunchedEffect(collapseSignal.intValue) {
            if (collapseSignal.intValue > 0 && viewModel.routingState is RoutingUiState.Idle) {
                sheetOffsetY.animateTo(maxSheetOffset())
            }
        }
        // Route picker should always be fully visible — reset any sheet collapse when a
        // destination is set, and re-expand the home sheet when we return to browsing.
        LaunchedEffect(viewModel.routingState is RoutingUiState.Idle) {
            sheetOffsetY.animateTo(0f)
        }
        // When the user starts typing a destination, open the sheet fully so the live
        // results (which render below the search box) are on-screen.
        LaunchedEffect(viewModel.searchQuery.isNotBlank()) {
            if (viewModel.searchQuery.isNotBlank()) sheetOffsetY.animateTo(0f)
        }

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            view.getMapAsync { map ->
                mapHolder.value = map
                // We render our own OSM/OpenMapTiles credit (see attribution Text
                // below), so disable MapLibre's overlapping logo + attribution.
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                // Put the compass in the TOP-LEFT so it doesn't sit under the top-right
                // overflow (⋮) menu when the map rotates off north.
                val cm = (16 * view.context.resources.displayMetrics.density).toInt()
                map.uiSettings.compassGravity = Gravity.TOP or Gravity.START
                map.uiSettings.setCompassMargins(cm, cm, 0, 0)
                if (!mapConfigured.value) {
                    mapConfigured.value = true
                    map.cameraPosition = CameraPosition.Builder()
                        .target(DEFAULT_TARGET)
                        .zoom(DEFAULT_ZOOM)
                        .build()
                    viewModel.onMapCenterChanged(DEFAULT_TARGET.latitude, DEFAULT_TARGET.longitude)
                    // A user drag means "stop chasing my dot" until they recenter.
                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                            following = false
                            collapseSignal.intValue++ // touching the map slides the sheet away
                        }

                        override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                        override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                    })
                    // Track where the map settles so "refresh cameras" (↻) fetches the
                    // area the user is looking at, not just the phone's location.
                    map.addOnCameraIdleListener {
                        map.cameraPosition.target?.let {
                            viewModel.onMapCenterChanged(it.latitude, it.longitude)
                        }
                    }
                    // Long-press to drop a destination → camera-aware alternatives.
                    map.addOnMapLongClickListener { point ->
                        val from = locationHolder.value
                        viewModel.requestRoutes(
                            from?.latitude,
                            from?.longitude,
                            RoutePoint(point.latitude, point.longitude),
                        )
                        true
                    }
                    // Single tap on a camera marker → identify it (type + facing). A tap
                    // that misses clears any open card.
                    map.addOnMapClickListener { point ->
                        collapseSignal.intValue++ // a tap on the map slides the sheet away
                        val info = map.cameraAt(map.projection.toScreenLocation(point))
                        selectedCameraHolder.value = info
                        info != null
                    }
                }
            }
        }

        // Load (or reload) the basemap style for the current day/night theme. Re-runs
        // when the theme flips at sunrise/sunset, re-adding our overlay layers (route,
        // cameras, location puck) on the fresh style and re-pushing their data.
        LaunchedEffect(mapHolder.value, night) {
            val map = mapHolder.value ?: return@LaunchedEffect
            styleHolder.value = null
            map.setStyle(Style.Builder().fromJson(BasemapProvider.buildStyleJson(context, night))) { style ->
                // Route under cameras under the location puck (draw order).
                style.addTopoLayers(context)
                style.setTopoVisible(viewModel.topoVisible)
                style.addRouteLayers()
                style.addCameraLayers()
                style.addFavoriteLayers()
                style.addLocationPuckLayers()
                style.updateCameras(cameras)
                style.setCamerasVisible(camerasVisible)
                style.updateFavorites(favMarkers)
                locationHolder.value?.let {
                    style.getSourceAs<GeoJsonSource>(LOCATION_SOURCE_ID)
                        ?.setGeoJson(locationFeature(it.longitude, it.latitude, lastBearing))
                }
                styleHolder.value = style
            }
        }

        // Stream location once permitted, and push it into the map's puck layer.
        LaunchedEffect(hasLocationPermission) {
            if (!hasLocationPermission) return@LaunchedEffect
            val source = LocationManagerSource(context)
            source.locationUpdates().collect { location ->
                val latLng = LatLng(location.latitude, location.longitude)
                locationHolder.value = latLng
                viewModel.onLocationUpdate(location.latitude, location.longitude, location.speed)
                viewModel.onLocationForCoverage(location.latitude, location.longitude)
                viewModel.onSpeedUpdate(location.speed)
                // Flip the map theme at sunset/sunrise for the current location.
                val isNight = SunCalculator.isNight(location.latitude, location.longitude, System.currentTimeMillis())
                if (isNight != night) night = isNight
                // Track travel heading whenever we're actually moving; hold it when stopped so
                // neither the arrow nor the heading-up camera spins from noisy fixes.
                if (location.hasBearing() && location.speed > 0.9f) lastBearing = location.bearing
                styleHolder.value?.getSourceAs<GeoJsonSource>(LOCATION_SOURCE_ID)
                    ?.setGeoJson(locationFeature(latLng.longitude, latLng.latitude, lastBearing))
                if (following) {
                    // Heading-up: rotate the map to the direction of travel.
                    mapHolder.value?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(latLng)
                                .zoom(FOLLOW_ZOOM)
                                .bearing(lastBearing.toDouble())
                                .build(),
                        ),
                    )
                }
            }
        }

        // Push camera data into the map whenever it changes (or the style reloads).
        LaunchedEffect(cameras, styleHolder.value) {
            styleHolder.value?.updateCameras(cameras)
        }
        // Refresh favorite pins whenever saved places change (or the style reloads).
        LaunchedEffect(favMarkers, styleHolder.value) {
            styleHolder.value?.updateFavorites(favMarkers)
        }
        // Apply the camera-layer visibility toggle.
        LaunchedEffect(camerasVisible, styleHolder.value) {
            styleHolder.value?.setCamerasVisible(camerasVisible)
        }
        // Apply the topographic-overlay toggle.
        LaunchedEffect(viewModel.topoVisible, styleHolder.value) {
            styleHolder.value?.setTopoVisible(viewModel.topoVisible)
        }

        // Draw routes (selected + faint alternatives) + destination pin.
        val routingState = viewModel.routingState
        val selectedRouteIndex = viewModel.selectedRouteIndex
        LaunchedEffect(routingState, selectedRouteIndex, viewModel.destination, styleHolder.value) {
            val style = styleHolder.value ?: return@LaunchedEffect
            style.updateDestination(viewModel.destination)
            val routes = (routingState as? RoutingUiState.Ready)?.routes?.map { it.points } ?: emptyList()
            style.updateRoutes(routes, selectedRouteIndex)
        }

        // Dim the portion already driven: repaint the traveled prefix in pale blue each nav
        // tick, and clear it whenever we're not actively navigating.
        val navState = viewModel.navState
        LaunchedEffect(navState, routingState, selectedRouteIndex, styleHolder.value) {
            val style = styleHolder.value ?: return@LaunchedEffect
            val selectedPoints = (routingState as? RoutingUiState.Ready)
                ?.routes?.getOrNull(selectedRouteIndex)?.points
            // Always pass the points so the split can be undone (full route restored) the
            // moment navigation stops; traveled distance is 0 unless actively navigating.
            val traveledM = if (viewModel.isNavigating && navState != null) navState.distanceTraveledM else 0.0
            style.updateTraveled(selectedPoints, traveledM)
        }

        // Once routes are computed, frame the whole trip on screen (and stop chasing the
        // location dot). Keyed on routingState only, so switching cards doesn't re-zoom.
        LaunchedEffect(routingState) {
            if (routingState !is RoutingUiState.Ready || viewModel.isNavigating) return@LaunchedEffect
            val map = mapHolder.value ?: return@LaunchedEffect
            val pts = (routingState.routes.getOrNull(viewModel.selectedRouteIndex)
                ?: routingState.routes.firstOrNull())?.points ?: return@LaunchedEffect
            if (pts.size < 2) return@LaunchedEffect
            val bounds = LatLngBounds.Builder()
                .apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }
                .build()
            following = false
            val d = context.resources.displayMetrics.density
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds,
                    (32 * d).toInt(),   // left
                    (210 * d).toInt(),  // top — clears the route panel
                    (32 * d).toInt(),   // right
                    (96 * d).toInt(),   // bottom
                ),
            )
        }

        // When navigation begins, track the driver — and use the last known location
        // right away (seed the nav banner + zoom in) instead of waiting for the next
        // GPS fix, so it doesn't sit on "Locating…" while parked.
        LaunchedEffect(viewModel.isNavigating) {
            if (viewModel.isNavigating) {
                following = true
                locationHolder.value?.let { latLng ->
                    viewModel.onLocationUpdate(latLng.latitude, latLng.longitude, 0f)
                    mapHolder.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, FOLLOW_ZOOM))
                }
            }
        }

        if (viewModel.isNavigating) {
            ManeuverBanner(
                context = context,
                navState = viewModel.navState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
            )
        } else {
            // Bottom panel (Waze-style): while browsing it's the "Where to?" sheet with
            // favorites + recents; once a destination is set it becomes the route picker.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { sheetHeightPx = it.height }
                    .offset { IntOffset(0, sheetOffsetY.value.roundToInt()) },
            ) {
                if (routingState is RoutingUiState.Idle) {
                    HomeSheet(
                        query = viewModel.searchQuery,
                        searchEnabled = viewModel.geocoderAvailable,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onClearSearch = viewModel::clearSearch,
                        results = viewModel.searchResults,
                        onSelectResult = viewModel::selectSearchResult,
                        home = viewModel.home,
                        work = viewModel.work,
                        favorites = viewModel.favorites,
                        recents = viewModel.recents,
                        onPlaceTap = viewModel::routeToPlace,
                        onSetHome = viewModel::saveAsHome,
                        onSetWork = viewModel::saveAsWork,
                        onClearHome = viewModel::clearHome,
                        onClearWork = viewModel::clearWork,
                        onSaveFavoriteAs = viewModel::saveFavoriteAs,
                        onRenameFavorite = viewModel::renameFavorite,
                        onRemoveFavorite = viewModel::removeFavorite,
                        onHandleDrag = { delta ->
                            sheetScope.launch {
                                sheetOffsetY.snapTo((sheetOffsetY.value + delta).coerceIn(0f, maxSheetOffset()))
                            }
                        },
                        onHandleDragStopped = { velocity ->
                            // Leave the sheet exactly where the user lets go (so they can
                            // park it partway — e.g. just the search box). Only a deliberate
                            // hard flick snaps it all the way open or all the way down.
                            val max = maxSheetOffset()
                            when {
                                velocity > 2200f -> sheetScope.launch { sheetOffsetY.animateTo(max) }
                                velocity < -2200f -> sheetScope.launch { sheetOffsetY.animateTo(0f) }
                                else -> Unit
                            }
                        },
                        onHandleTap = {
                            sheetScope.launch {
                                val max = maxSheetOffset()
                                sheetOffsetY.animateTo(if (sheetOffsetY.value > max / 2f) 0f else max)
                            }
                        },
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(10.dp),
                        ) {
                            RoutePanel(
                                context = context,
                                routingState = routingState,
                                selectedIndex = selectedRouteIndex,
                                onSelect = viewModel::selectRoute,
                                onStart = viewModel::startNavigation,
                                onClear = viewModel::clearRoute,
                            )
                        }
                    }
                }
            }
        }

        // Navigation bottom bar (distance/ETA remaining, voice, stop).
        if (viewModel.isNavigating) {
            NavBottomBar(
                context = context,
                navState = viewModel.navState,
                voiceEnabled = viewModel.voiceEnabled,
                onToggleVoice = viewModel::toggleVoice,
                onStop = viewModel::stopNavigation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 22.dp),
            )
        }

        // Tapped-camera identify card (type + which way it faces).
        selectedCameraHolder.value?.let { info ->
            CameraInfoCard(
                info = info,
                onDismiss = { selectedCameraHolder.value = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (viewModel.isNavigating) 96.dp else visibleSheetDp + 12.dp),
            )
        }

        // NOTE: the camera-count / last-synced status line and the OpenMapTiles/OSM
        // attribution were removed from the map face (cluttered the view). The map data
        // attribution (© OpenMapTiles © OpenStreetMap contributors) still needs to live
        // somewhere reachable for licensing — fold it into the top-right menu / About
        // screen when that's built.

        // Speedometer (Waze-style) — current MPH, floating above the bottom sheet.
        if (!viewModel.isNavigating) {
            Speedometer(
                mph = viewModel.speedMph,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = visibleSheetDp + 16.dp),
            )
        }

        // Recenter button (bottom-right). Camera show/hide + refresh now live in the
        // top-right overflow menu so the map face stays clean.
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, top = 16.dp, bottom = if (viewModel.isNavigating) 120.dp else visibleSheetDp + 16.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    if (!hasLocationPermission) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    } else {
                        following = true
                        locationHolder.value?.let { latLng ->
                            mapHolder.value?.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(latLng)
                                        .zoom(FOLLOW_ZOOM)
                                        .bearing(lastBearing.toDouble())
                                        .build(),
                                ),
                            )
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringRes(context, R.string.recenter),
                )
            }
        }

        // Top-right overflow menu: camera show/hide + refresh. Keeps the map face clean;
        // hidden during navigation (the top is the maneuver banner then).
        if (!viewModel.isNavigating) {
            var menuOpen by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
            ) {
                SmallFloatingActionButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringRes(context, R.string.menu),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringRes(context, if (camerasVisible) R.string.hide_cameras else R.string.show_cameras))
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (camerasVisible) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                                contentDescription = null,
                            )
                        },
                        onClick = { viewModel.toggleCameras(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringRes(
                                    context,
                                    if (syncState is SyncUiState.Syncing) R.string.refreshing_cameras else R.string.refresh_cameras,
                                ),
                            )
                        },
                        leadingIcon = {
                            if (syncState is SyncUiState.Syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            }
                        },
                        enabled = syncState !is SyncUiState.Syncing,
                        onClick = { viewModel.refreshNow(); menuOpen = false },
                    )
                    if (viewModel.topoAvailable) {
                        DropdownMenuItem(
                            text = {
                                Text(stringRes(context, if (viewModel.topoVisible) R.string.hide_topo else R.string.show_topo))
                            },
                            leadingIcon = {
                                Icon(imageVector = Icons.Filled.Terrain, contentDescription = null)
                            },
                            onClick = { viewModel.toggleTopo(); menuOpen = false },
                        )
                    }
                }
            }
        }

        // Transient "Updated" flash after a manual refresh, summarizing what landed on the
        // map. Auto-dismisses; `shownFlash` keeps the last message visible during fade-out.
        val flash = viewModel.refreshFlash
        var shownFlash by remember { mutableStateOf<RefreshFlash?>(null) }
        LaunchedEffect(flash) {
            if (flash != null) {
                shownFlash = flash
                delay(2600)
                viewModel.consumeRefreshFlash()
            }
        }
        AnimatedVisibility(
            visible = flash != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
        ) {
            shownFlash?.let { RefreshFlashToast(it) }
        }
    }
}

/** A small "Updated" toast: title + one-line summary of new devices found on this refresh. */
@Composable
private fun RefreshFlashToast(flash: RefreshFlash, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = if (flash.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (flash.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = flash.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = flash.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The browse-mode bottom sheet (Waze-style): a "Where to?" search box, then a row of
 * favorites (Home, Work, pinned places), then recent destinations. While the user is
 * typing, the favorites/recents are swapped out for live search results.
 */
@Composable
private fun HomeSheet(
    query: String,
    searchEnabled: Boolean,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    results: List<GeoResult>,
    onSelectResult: (GeoResult) -> Unit,
    home: SavedPlace?,
    work: SavedPlace?,
    favorites: List<SavedPlace>,
    recents: List<SavedPlace>,
    onPlaceTap: (SavedPlace) -> Unit,
    onSetHome: (SavedPlace) -> Unit,
    onSetWork: (SavedPlace) -> Unit,
    onClearHome: () -> Unit,
    onClearWork: () -> Unit,
    onSaveFavoriteAs: (SavedPlace, String) -> Unit,
    onRenameFavorite: (SavedPlace, String) -> Unit,
    onRemoveFavorite: (SavedPlace) -> Unit,
    onHandleDrag: (Float) -> Unit,
    onHandleDragStopped: (Float) -> Unit,
    onHandleTap: () -> Unit,
) {
    // Pending name-entry dialogs: renaming an existing favorite, or saving a recent as a
    // named favorite. Holding the target place here keeps the dialog logic in one place.
    var renameTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var saveAsTarget by remember { mutableStateOf<SavedPlace?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Drag handle: drag it to slide the sheet down (show less) or up (show more),
            // or tap to toggle. The whole row is the grab target so it's easy to catch.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> onHandleDrag(delta) },
                        onDragStopped = { velocity -> onHandleDragStopped(velocity) },
                    )
                    .clickable { onHandleTap() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            DestinationSearchBar(
                query = query,
                enabled = searchEnabled,
                onQueryChange = onQueryChange,
                onClear = onClearSearch,
            )

            if (results.isNotEmpty()) {
                SearchResultsList(results = results, onSelect = onSelectResult)
            } else {
                FavoritesRow(
                    home = home,
                    work = work,
                    favorites = favorites,
                    onPlaceTap = onPlaceTap,
                    onClearHome = onClearHome,
                    onClearWork = onClearWork,
                    onRequestRename = { renameTarget = it },
                    onRemoveFavorite = onRemoveFavorite,
                )

                if (recents.isNotEmpty()) {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                    recents.take(4).forEach { place ->
                        PlaceRow(
                            icon = Icons.Filled.History,
                            title = place.name,
                            subtitle = place.addr,
                            onTap = { onPlaceTap(place) },
                            menuItems = { dismiss ->
                                DropdownMenuItem(
                                    text = { Text("Set as Home") },
                                    onClick = { onSetHome(place); dismiss() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Set as Work") },
                                    onClick = { onSetWork(place); dismiss() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save to favorites…") },
                                    onClick = { saveAsTarget = place; dismiss() },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        NamePlaceDialog(
            title = "Rename favorite",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name -> onRenameFavorite(target, name); renameTarget = null },
        )
    }
    saveAsTarget?.let { target ->
        NamePlaceDialog(
            title = "Save to favorites",
            initial = target.name,
            onDismiss = { saveAsTarget = null },
            onConfirm = { name -> onSaveFavoriteAs(target, name); saveAsTarget = null },
        )
    }
}

/** Name-entry dialog used for "Save as…" and "Rename" of favorites. */
@Composable
private fun NamePlaceDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Horizontal row of favorite chips: Home, Work, then any pinned places. Long-press a
 *  pinned favorite for a Rename / Remove menu. */
@Composable
private fun FavoritesRow(
    home: SavedPlace?,
    work: SavedPlace?,
    favorites: List<SavedPlace>,
    onPlaceTap: (SavedPlace) -> Unit,
    onClearHome: () -> Unit,
    onClearWork: () -> Unit,
    onRequestRename: (SavedPlace) -> Unit,
    onRemoveFavorite: (SavedPlace) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        // Home/Work are shown only once a place is saved there — an unused slot would just
        // be undeletable clutter. Set one via long-pressing a recent → "Set as Home/Work".
        home?.let { place ->
            SlotFavoriteChip(
                icon = Icons.Filled.Home,
                label = "Home",
                place = place,
                onTap = { onPlaceTap(place) },
                onClear = onClearHome,
            )
        }
        work?.let { place ->
            SlotFavoriteChip(
                icon = Icons.Filled.Work,
                label = "Work",
                place = place,
                onTap = { onPlaceTap(place) },
                onClear = onClearWork,
            )
        }
        favorites.forEach { fav ->
            CustomFavoriteChip(
                label = fav.name,
                onTap = { onPlaceTap(fav) },
                onRename = { onRequestRename(fav) },
                onRemove = { onRemoveFavorite(fav) },
            )
        }
    }
}

/** The Home/Work chip. When a place is set, long-press opens a menu to clear it; when empty
 *  it's just a label (long-press does nothing). */
@Composable
private fun SlotFavoriteChip(
    icon: ImageVector,
    label: String,
    place: SavedPlace?,
    onTap: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FavoriteChip(
            icon = icon,
            label = label,
            active = place != null,
            onClick = onTap,
            onLongClick = if (place != null) ({ expanded = true }) else null,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Clear $label") }, onClick = { expanded = false; onClear() })
        }
    }
}

/** A pinned-favorite chip whose long-press opens a Rename / Remove menu. */
@Composable
private fun CustomFavoriteChip(
    label: String,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FavoriteChip(
            icon = Icons.Filled.Bookmark,
            label = label,
            active = true,
            onClick = onTap,
            onLongClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Remove") }, onClick = { expanded = false; onRemove() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tappable place row (recent) with a long-press menu to save it as Home/Work/favorite. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onTap: () -> Unit,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = { expanded = true })
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuItems { expanded = false }
        }
    }
}

/** Round speedometer pill showing current speed in MPH. */
@Composable
private fun Speedometer(mph: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = modifier.size(64.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = "$mph",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "MPH",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Offline destination search box (geocoder over the on-device OSM index). */
@Composable
private fun DestinationSearchBar(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (query.isEmpty()) {
                    Text(
                        text = if (enabled) "Where to?" else "Search index not installed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        }
    }
}

/** Dropdown of geocoder hits; tapping one sets the destination. */
@Composable
private fun SearchResultsList(results: List<GeoResult>, onSelect: (GeoResult) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            results.forEach { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(r) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (r.addr.isBlank()) {
                                prettyKind(r.kind)
                            } else {
                                "${prettyKind(r.kind)} · ${r.addr}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun prettyKind(kind: String): String =
    kind.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Small card identifying a tapped camera marker: what it is, and which way it faces. */
@Composable
private fun CameraInfoCard(info: CameraInfo, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 4.dp,
        modifier = modifier.padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = cameraTypeColor(info.type),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(cameraTypeLabel(info.type), style = MaterialTheme.typography.bodyMedium)
                info.directionDeg?.let {
                    Text(
                        text = "Facing ${compassDir(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                info.osmTimestamp?.let {
                    Text(
                        text = "Mapped ${DateUtils.getRelativeTimeSpanString(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun cameraTypeLabel(type: String): String = when (type) {
    CameraEntity.TYPE_ALPR -> "ALPR camera"
    CameraEntity.TYPE_GUNSHOT -> "Gunshot sensor"
    CameraEntity.TYPE_CAMERA -> "CCTV camera"
    CameraEntity.TYPE_SPEED -> "Speed / red-light camera"
    CameraEntity.TYPE_DOME -> "Dome camera"
    else -> "Surveillance camera (unverified)"
}

/** Marker/icon color per camera type — matches the map dots (ALPR red = the threat). */
private fun cameraTypeColor(type: String): Color = when (type) {
    CameraEntity.TYPE_ALPR -> Color(0xFFFF5252)
    CameraEntity.TYPE_GUNSHOT -> Color(0xFFFFB300)
    CameraEntity.TYPE_SPEED -> Color(0xFF4C8DFF)
    CameraEntity.TYPE_CAMERA, CameraEntity.TYPE_DOME -> Color(0xFF9E9E9E)
    else -> Color(0xFFBDBDBD)
}

/** Bearing (0° = north, clockwise) → plain-language heading like "northeast". */
private fun compassDir(deg: Double): String {
    val dirs = arrayOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    val i = (((deg + 22.5) % 360 + 360) % 360 / 45).toInt().coerceIn(0, 7)
    return dirs[i]
}

/** Top-of-map routing panel: progress, scored alternatives, or errors. */
@Composable
private fun RoutePanel(
    context: Context,
    routingState: RoutingUiState,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    onClear: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    when (routingState) {
        // No idle hint — the map stays clean until a destination is set (address
        // search will replace the long-press affordance).
        RoutingUiState.Idle -> Unit

        RoutingUiState.Computing -> Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = shape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringRes(context, R.string.route_calculating_alts), style = MaterialTheme.typography.bodyMedium)
            }
        }

        is RoutingUiState.Ready -> {
            val routes = routingState.routes
            val labels = routeLabels(context, routes)
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringRes(context, R.string.routes_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = onStart,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringRes(context, R.string.nav_start))
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Close, contentDescription = stringRes(context, R.string.route_clear))
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        routes.forEachIndexed { i, route ->
                            RouteOptionCard(
                                label = labels[i],
                                route = route,
                                selected = i == selectedIndex,
                                onClick = { onSelect(i) },
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringRes(context, R.string.route_pick_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        is RoutingUiState.Failed -> RouteMessageCard(context.getString(R.string.route_failed, routingState.message), onClear)
        RoutingUiState.NeedsLocation -> RouteMessageCard(stringRes(context, R.string.route_needs_location), onClear)
        RoutingUiState.GraphMissing -> RouteMessageCard(stringRes(context, R.string.route_graph_missing), onClear)
    }
}

/**
 * Labels the option list (sorted by time): index 0 is fastest; the route passing the
 * fewest cameras is "Fewest cameras"; routes that cut cameras vs. fastest are "Fewer
 * cameras"; a genuinely different corridor that doesn't reduce cameras is "Alternate".
 */
private fun routeLabels(context: Context, routes: List<ScoredRoute>): List<String> {
    if (routes.isEmpty()) return emptyList()
    // A learned "Your usual" route may sit at the front; label the computed ones by their own
    // (time-sorted) ranking, ignoring the learned entry so "Fastest" still means fastest.
    val computed = routes.filter { !it.isLearned }
    val minCam = computed.minOfOrNull { it.camerasPassed } ?: 0
    val fastestCam = computed.firstOrNull()?.camerasPassed ?: 0
    return routes.map { r ->
        when {
            r.isLearned -> stringRes(context, R.string.route_label_usual)
            r === computed.firstOrNull() -> stringRes(context, R.string.route_label_fastest)
            r.camerasPassed == minCam && r.camerasPassed < fastestCam -> stringRes(context, R.string.route_label_low_exposure)
            r.camerasPassed < fastestCam -> stringRes(context, R.string.route_label_balanced)
            else -> stringRes(context, R.string.route_label_alternate)
        }
    }
}

@Composable
private fun RouteOptionCard(label: String, route: ScoredRoute, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val camColor = when {
        route.camerasPassed == 0 -> Color(0xFF3DDC97)
        route.camerasPassed <= 2 -> MaterialTheme.colorScheme.onSurface
        else -> Color(0xFFFF5252)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(formatDuration(route.durationMillis), style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatDistance(route.distanceMeters),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = camColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = context.getString(R.string.cameras_count, route.camerasPassed),
                    style = MaterialTheme.typography.labelSmall,
                    color = camColor,
                )
            }
        }
    }
}

@Composable
private fun RouteMessageCard(text: String, onClear: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            IconButton(onClick = onClear) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/** Big turn banner at the top during navigation. */
@Composable
private fun ManeuverBanner(context: Context, navState: NavState?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = maneuverIcon(navState?.maneuverSign ?: 0),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        navState == null -> "Locating…"
                        navState.arrived -> stringRes(context, R.string.nav_arrived)
                        else -> formatDistance(navState.distanceToManeuverM)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = when {
                        navState == null -> ""
                        navState.recalculating -> stringRes(context, R.string.nav_recalculating)
                        else -> navState.maneuverText
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Bottom bar during navigation: remaining time/distance, voice toggle, end. */
@Composable
private fun NavBottomBar(
    context: Context,
    navState: NavState?,
    voiceEnabled: Boolean,
    onToggleVoice: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDuration(navState?.timeRemainingMillis ?: 0L),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatDistance(navState?.distanceRemainingM ?: 0.0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleVoice) {
                Icon(
                    imageVector = if (voiceEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringRes(context, if (voiceEnabled) R.string.nav_voice_on else R.string.nav_voice_off),
                )
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringRes(context, R.string.nav_stop))
            }
        }
    }
}

/** Maps a GraphHopper turn sign to a Material turn icon. */
private fun maneuverIcon(sign: Int): ImageVector = when (sign) {
    -3 -> Icons.Filled.TurnSharpLeft
    -2 -> Icons.Filled.TurnLeft
    -1, -7 -> Icons.Filled.TurnSlightLeft
    1, 7 -> Icons.Filled.TurnSlightRight
    2 -> Icons.Filled.TurnRight
    3 -> Icons.Filled.TurnSharpRight
    4 -> Icons.Filled.Flag
    else -> Icons.Filled.Straight
}

private fun formatDistance(meters: Double): String {
    val miles = meters / 1609.344
    return if (miles < 0.1) "%.0f ft".format(meters * 3.28084) else "%.1f mi".format(miles)
}

private fun formatDuration(millis: Long): String {
    val totalMin = (millis / 60_000L).toInt().coerceAtLeast(1)
    return if (totalMin < 60) "$totalMin min" else "${totalMin / 60} h ${totalMin % 60} min"
}

/**
 * Adds the GeoJSON source + layers used to draw the user's location: a faint accuracy
 * halo, and a small Waze-style triangular arrow that rotates to the direction of travel.
 */
private fun Style.addLocationPuckLayers() {
    if (getSource(LOCATION_SOURCE_ID) != null) return
    addImage(LOCATION_ARROW_IMAGE, createNavArrowBitmap())
    addSource(GeoJsonSource(LOCATION_SOURCE_ID))
    addLayer(
        CircleLayer(LOCATION_ACCURACY_LAYER, LOCATION_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(18f),
            PropertyFactory.circleColor("#3DDC97"),
            PropertyFactory.circleOpacity(0.14f),
        ),
    )
    // Triangle drawn pointing north; rotated per-fix by the travel bearing, aligned to the
    // map — so it points the real heading whether the map is north-up or heading-up.
    addLayer(
        SymbolLayer(LOCATION_DOT_LAYER, LOCATION_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(LOCATION_ARROW_IMAGE),
            PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconSize(1.0f),
        ),
    )
}

/** A point at [lng],[lat] tagged with the travel [bearing] (deg) for the arrow rotation. */
private fun locationFeature(lng: Double, lat: Double, bearing: Float): Feature =
    Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
        addNumberProperty(PROP_BEARING, bearing)
    }

/** A small, rounded navigation arrowhead pointing up (north); MapLibre rotates it per-fix. */
private fun createNavArrowBitmap(): Bitmap {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val s = size.toFloat()
    // A kite/arrowhead: sharp tip at top, swept-back wings, notched tail — reads as "forward".
    val path = Path().apply {
        moveTo(s / 2f, s * 0.10f)    // tip (north)
        lineTo(s * 0.80f, s * 0.84f) // right wing
        lineTo(s / 2f, s * 0.66f)    // center tail notch
        lineTo(s * 0.20f, s * 0.84f) // left wing
        close()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#3DDC97")
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#0E1116")
        style = Paint.Style.STROKE
        strokeWidth = s * 0.06f
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bitmap
}

@Composable
private fun BasemapMissing(context: Context, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringRes(context, R.string.basemap_missing_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringRes(context, R.string.basemap_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = BasemapProvider.basemapFile(context).absolutePath,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Tiny indirection so non-composable helpers can resolve string resources. */
private fun stringRes(context: Context, resId: Int): String = context.getString(resId)

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Creates a [MapView] and forwards Android lifecycle events to it, as MapLibre
 * requires. Cleans up on disposal to avoid leaking the GL surface.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        // SurfaceView (default) for best navigation performance. Note: adb
        // screencap renders the GL SurfaceView as black; build with
        // MapLibreMapOptions.textureMode(true) if you need adb screenshots.
        MapView(context).apply { id = R.id.ghostroute_map_view }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
