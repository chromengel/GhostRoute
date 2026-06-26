package com.ghostroute.app.ui.map

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostroute.app.data.CameraEntity
import com.ghostroute.app.data.CameraRepository
import com.ghostroute.app.map.TopoProvider
import com.ghostroute.app.data.GeoBounds
import com.ghostroute.app.car.NavHub
import com.ghostroute.app.data.SyncOutcome
import com.ghostroute.app.geocode.GeoResult
import com.ghostroute.app.geocode.GeocoderService
import com.ghostroute.app.navigation.NavigationEngine
import com.ghostroute.app.navigation.Voice
import com.ghostroute.app.places.LearnedRoutesStore
import com.ghostroute.app.places.PlacesStore
import com.ghostroute.app.places.SavedPlace
import com.ghostroute.app.routing.AlternativesOutcome
import com.ghostroute.app.routing.RoutePoint
import com.ghostroute.app.routing.RoutingService
import com.ghostroute.app.routing.ScoredRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for a camera-data refresh. */
sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Syncing : SyncUiState
    data class Done(val cameraCount: Int) : SyncUiState
    data class Failed(val message: String) : SyncUiState
}

/** A transient post-refresh "flash" toast: a title ("Updated") plus a one-line summary of
 *  what changed (how many new devices landed on the map). [ok] picks the success styling. */
data class RefreshFlash(val title: String, val detail: String, val ok: Boolean)

/** UI state for camera-aware routing (long-press a destination → scored alternatives). */
sealed interface RoutingUiState {
    data object Idle : RoutingUiState
    data object Computing : RoutingUiState
    data class Ready(val routes: List<ScoredRoute>) : RoutingUiState
    data class Failed(val message: String) : RoutingUiState
    data object NeedsLocation : RoutingUiState
    data object GraphMissing : RoutingUiState
}

/** Live turn-by-turn state shown during navigation. */
data class NavState(
    val maneuverText: String,
    val maneuverSign: Int,
    val distanceToManeuverM: Double,
    val distanceRemainingM: Double,
    val distanceTraveledM: Double,
    val timeRemainingMillis: Long,
    val arrived: Boolean,
    val recalculating: Boolean,
)

/** Minimum spacing between recorded GPS trace points (meters) while learning a trip. */
private const val TRACE_SAMPLE_M = 25.0

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CameraRepository.get(app)
    private val placesStore = PlacesStore(app)
    private val learnedRoutes = LearnedRoutesStore(app)

    // ---- Saved places: recents + favorites (Home, Work, pinned). All offline. ----
    var recents by mutableStateOf(placesStore.recents())
        private set
    var favorites by mutableStateOf(placesStore.favorites())
        private set
    var home by mutableStateOf(placesStore.home())
        private set
    var work by mutableStateOf(placesStore.work())
        private set

    /** Current GPS speed in whole MPH, for the on-map speedometer. */
    var speedMph by mutableStateOf(0)
        private set

    fun onSpeedUpdate(speedMps: Float) {
        // m/s → mph; clamp tiny GPS jitter to 0 so a parked phone reads "0".
        val mph = if (speedMps < 0.45f) 0 else (speedMps * 2.2369363f).toInt()
        if (mph != speedMph) speedMph = mph
    }

    private fun reloadPlaces() {
        recents = placesStore.recents()
        favorites = placesStore.favorites()
        home = placesStore.home()
        work = placesStore.work()
    }

    /** Tap a saved place (favorite/recent) → make it the destination and route there. */
    fun routeToPlace(place: SavedPlace) {
        searchQuery = place.name
        searchResults = emptyList()
        searchJob?.cancel()
        placesStore.addRecent(place)
        reloadPlaces()
        requestRoutes(lastKnownLat, lastKnownLon, RoutePoint(place.lat, place.lon))
    }

    fun saveAsHome(place: SavedPlace) { placesStore.setHome(place); reloadPlaces() }
    fun saveAsWork(place: SavedPlace) { placesStore.setWork(place); reloadPlaces() }
    fun clearHome() { placesStore.clearHome(); reloadPlaces() }
    fun clearWork() { placesStore.clearWork(); reloadPlaces() }
    fun addFavorite(place: SavedPlace) { placesStore.addFavorite(place); reloadPlaces() }
    fun removeFavorite(place: SavedPlace) { placesStore.removeFavorite(place); reloadPlaces() }

    /** Save [place] as a favorite under a user-chosen [name] (the "Save as…" flow). */
    fun saveFavoriteAs(place: SavedPlace, name: String) {
        placesStore.addFavorite(place.copy(name = name.trim().ifEmpty { place.name }))
        reloadPlaces()
    }

    /** Rename an existing favorite. */
    fun renameFavorite(place: SavedPlace, newName: String) {
        placesStore.renameFavorite(place, newName)
        reloadPlaces()
    }

    /**
     * Cameras from Room, observed so the map layer updates as syncs land. We also
     * re-read the last-sync time on every emission so a *background* (WorkManager)
     * sync refreshes the status line, not just manual refreshes.
     */
    val cameras: StateFlow<List<CameraEntity>> =
        repository.observeCameras()
            .onEach { lastSyncedMillis = repository.lastSyncedMillis() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /**
     * The subset of cameras the router actually detours around — only the plate-reading
     * ones ([CameraEntity.AVOIDED_TYPES]). The map still DISPLAYS every type (gunshot,
     * speed, CCTV, untyped), but we don't reroute around those — GhostRoute exists to dodge
     * ALPR/Flock, not speed cameras or gunshot sensors.
     */
    private fun avoidedCameras(): List<CameraEntity> =
        cameras.value.filter { it.type in CameraEntity.AVOIDED_TYPES }

    var camerasVisible by mutableStateOf(true)
        private set

    /** Whether the optional topographic overlay (hillshade + contours) is installed, and
     *  whether it's currently shown. The toggle only appears when the data is present. */
    val topoAvailable: Boolean = TopoProvider.isInstalled(app)
    var topoVisible by mutableStateOf(false)
        private set

    var syncState by mutableStateOf<SyncUiState>(SyncUiState.Idle)
        private set

    /** Set when a manual refresh finishes; the UI shows it briefly then calls
     *  [consumeRefreshFlash]. Null when there's nothing to flash. */
    var refreshFlash by mutableStateOf<RefreshFlash?>(null)
        private set

    fun consumeRefreshFlash() { refreshFlash = null }

    var lastSyncedMillis by mutableStateOf(repository.lastSyncedMillis())
        private set

    var routingState by mutableStateOf<RoutingUiState>(RoutingUiState.Idle)
        private set

    /** Index of the chosen alternative within [RoutingUiState.Ready.routes]. */
    var selectedRouteIndex by mutableStateOf(0)
        private set

    /** The current destination pin, shown while computing and after success. */
    var destination by mutableStateOf<RoutePoint?>(null)
        private set

    private var lastFrom: RoutePoint? = null

    // Last known location + a throttle so coverage is only re-checked every ~5 km.
    private var lastKnownLat: Double? = null
    private var lastKnownLon: Double? = null
    private var lastCoverageLat = Double.NaN
    private var lastCoverageLon = Double.NaN

    // Where the map is currently centered — so "refresh cameras" (↻) fetches the area the
    // user is LOOKING AT (e.g. scrolled to Western NC), not just where the phone is.
    private var mapCenterLat: Double? = null
    private var mapCenterLon: Double? = null

    /** Called as the map settles after a pan/zoom, with its new center. */
    fun onMapCenterChanged(lat: Double, lon: Double) {
        mapCenterLat = lat
        mapCenterLon = lon
    }

    fun toggleCameras() {
        camerasVisible = !camerasVisible
    }

    fun toggleTopo() {
        topoVisible = !topoVisible
    }

    // ---- Offline address/POI search (Phase 6) ----

    var searchQuery by mutableStateOf("")
        private set

    var searchResults by mutableStateOf<List<GeoResult>>(emptyList())
        private set

    val geocoderAvailable: Boolean = GeocoderService.isInstalled(app)

    private var searchJob: Job? = null

    /** Called as the user types; debounced offline lookup ordered by prominence + nearness. */
    fun onSearchQueryChange(text: String) {
        searchQuery = text
        searchJob?.cancel()
        if (text.trim().length < 2) {
            searchResults = emptyList()
            return
        }
        // "Distance from me" reference: real GPS first, else wherever the map is centered
        // (so results are near what the user is looking at), else the default region.
        val lat = lastKnownLat ?: mapCenterLat ?: 35.96
        val lon = lastKnownLon ?: mapCenterLon ?: -83.92
        searchJob = viewModelScope.launch {
            delay(180) // debounce keystrokes
            searchResults = GeocoderService.search(getApplication(), text, lat, lon)
        }
    }

    /** A search hit was tapped → make it the destination and compute routes. */
    fun selectSearchResult(result: GeoResult) {
        searchQuery = result.name
        searchResults = emptyList()
        searchJob?.cancel()
        placesStore.addRecent(SavedPlace(result.name, result.addr, result.lat, result.lon))
        reloadPlaces()
        requestRoutes(lastKnownLat, lastKnownLon, RoutePoint(result.lat, result.lon))
    }

    fun clearSearch() {
        searchQuery = ""
        searchResults = emptyList()
        searchJob?.cancel()
    }

    /**
     * Long-press a destination → compute the fastest route plus camera-avoiding
     * alternatives. [fromLat]/[fromLon] are null when location isn't known yet.
     */
    fun requestRoutes(fromLat: Double?, fromLon: Double?, to: RoutePoint) {
        destination = to
        if (fromLat == null || fromLon == null) {
            lastFrom = null
            routingState = RoutingUiState.NeedsLocation
            return
        }
        lastFrom = RoutePoint(fromLat, fromLon)
        compute()
    }

    fun selectRoute(index: Int) {
        selectedRouteIndex = index
        (routingState as? RoutingUiState.Ready)?.routes?.getOrNull(index)?.let { NavHub.setRoute(it.points) }
    }

    private fun compute() {
        val from = lastFrom ?: return
        val to = destination ?: return
        viewModelScope.launch {
            routingState = RoutingUiState.Computing
            routingState = when (
                val outcome = RoutingService.routeAlternatives(
                    getApplication(), avoidedCameras(), from.lat, from.lon, to.lat, to.lon,
                )
            ) {
                is AlternativesOutcome.Success -> {
                    val routes = withLearnedRoute(from, to, outcome.routes)
                    // If we reconstructed the user's usual route, default to it; else fewest cameras.
                    selectedRouteIndex = if (routes.firstOrNull()?.isLearned == true) 0 else defaultSelection(routes)
                    // Mirror the chosen route to the Android Auto screen.
                    NavHub.setRoute(routes.getOrNull(selectedRouteIndex)?.points ?: emptyList())
                    RoutingUiState.Ready(routes)
                }
                is AlternativesOutcome.Error -> RoutingUiState.Failed(outcome.message)
                AlternativesOutcome.GraphNotReady -> RoutingUiState.GraphMissing
            }
        }
    }

    /** If we've learned a usual route to [to] and the user is starting near where it was
     *  learned, reconstruct it and put it first as "Your usual"; otherwise leave [routes]. */
    private suspend fun withLearnedRoute(
        from: RoutePoint,
        to: RoutePoint,
        routes: List<ScoredRoute>,
    ): List<ScoredRoute> {
        val chain = learnedRoutes.chainFrom(to, from) ?: return routes
        val learned = RoutingService.routeViaPoints(getApplication(), avoidedCameras(), chain) ?: return routes
        return listOf(learned) + routes
    }

    /**
     * The route to pre-select. GhostRoute exists to avoid cameras, so default to the
     * one passing the fewest — tie-broken by time (the list is already time-sorted).
     * The "Fastest" card is always one tap away for the user who'd rather have speed.
     */
    private fun defaultSelection(routes: List<ScoredRoute>): Int =
        routes.indices.minWithOrNull(
            compareBy({ routes[it].camerasPassed }, { routes[it].durationMillis }),
        ) ?: 0

    fun clearRoute() {
        routingState = RoutingUiState.Idle
        destination = null
        lastFrom = null
        selectedRouteIndex = 0
        NavHub.clearRoute()
    }

    // ---- Navigation (Phase 5) ----

    var isNavigating by mutableStateOf(false)
        private set

    var navState by mutableStateOf<NavState?>(null)
        private set

    var voiceEnabled by mutableStateOf(true)
        private set

    private var navEngine: NavigationEngine? = null
    private var voice: Voice? = null
    private var navDestination: RoutePoint? = null
    private var recalculating = false

    // Raw GPS trace of the current trip, recorded so GhostRoute can auto-learn the way you
    // actually drove to a destination and default to it next time. On-device only.
    private val drivenTrace = ArrayList<RoutePoint>()
    private var learnedThisTrip = false

    /** Begins turn-by-turn on the currently selected alternative. */
    fun startNavigation() {
        val ready = routingState as? RoutingUiState.Ready ?: return
        val route = ready.routes.getOrNull(selectedRouteIndex) ?: return
        if (route.points.size < 2) return
        val from = lastFrom
        val to = destination ?: return

        navDestination = to
        isNavigating = true
        navState = null
        drivenTrace.clear()
        learnedThisTrip = false
        if (voice == null) voice = Voice(getApplication())
        voice?.enabled = voiceEnabled

        // Start the engine IMMEDIATELY so the map follows you and progress shows at once
        // (snapping/ETA work from the route geometry alone). Turn-by-turn directions are
        // computed on demand for just this route and streamed in a moment later — at
        // which point we swap in an engine that has them (we're still at the start).
        navEngine = NavigationEngine(route)
        voice?.speak("Starting navigation.")

        if (route.maneuvers.isEmpty() && from != null) {
            viewModelScope.launch {
                val maneuvers = RoutingService.maneuversFor(
                    getApplication(), avoidedCameras(), from.lat, from.lon, to.lat, to.lon, route.penaltyPerCamera,
                )
                if (isNavigating && maneuvers.isNotEmpty()) {
                    navEngine = NavigationEngine(route.copy(maneuvers = maneuvers))
                }
            }
        }
    }

    fun stopNavigation() {
        isNavigating = false
        navEngine = null
        navState = null
        navDestination = null
        recalculating = false
        voice?.stop()
    }

    /** On a completed trip, remember the downsampled path we drove, keyed to the destination,
     *  so the next route there can default to "your usual" way. */
    private fun learnTripIfWorthwhile() {
        val dest = navDestination ?: return
        if (drivenTrace.size < 3) return
        val chain = LearnedRoutesStore.downsample(drivenTrace, dest)
        if (chain.size >= 2) learnedRoutes.learn(dest, chain)
    }

    fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        voice?.enabled = voiceEnabled
        if (!voiceEnabled) voice?.stop()
    }

    /** Feed each location fix here while navigating (from the map's location stream). */
    fun onLocationUpdate(lat: Double, lon: Double, speedMps: Float) {
        val engine = navEngine ?: return
        if (!isNavigating) return

        // Record the path actually driven (sampled by distance to stay compact).
        val here = RoutePoint(lat, lon)
        if (drivenTrace.isEmpty() || LearnedRoutesStore.meters(drivenTrace.last(), here) >= TRACE_SAMPLE_M) {
            drivenTrace.add(here)
        }

        val update = engine.update(lat, lon, speedMps)
        // On arrival, remember the way we came so it becomes the default next time.
        if (update.arrived && !learnedThisTrip) {
            learnedThisTrip = true
            learnTripIfWorthwhile()
        }
        navState = NavState(
            maneuverText = update.nextManeuver?.text ?: "",
            maneuverSign = update.nextManeuver?.sign ?: 0,
            distanceToManeuverM = update.distanceToManeuverM,
            distanceRemainingM = update.distanceRemainingM,
            distanceTraveledM = update.distanceTraveledM,
            timeRemainingMillis = update.timeRemainingMillis,
            arrived = update.arrived,
            recalculating = recalculating,
        )
        update.speak?.let { voice?.speak(it) }

        if (update.offRoute && !recalculating) recalculate(lat, lon)
    }

    private fun recalculate(lat: Double, lon: Double) {
        val dest = navDestination ?: return
        recalculating = true
        voice?.speak("Recalculating.")
        viewModelScope.launch {
            val outcome = RoutingService.routeAlternatives(
                getApplication(), avoidedCameras(), lat, lon, dest.lat, dest.lon,
            )
            if (outcome is AlternativesOutcome.Success && outcome.routes.isNotEmpty()) {
                val idx = defaultSelection(outcome.routes)
                routingState = RoutingUiState.Ready(outcome.routes)
                selectedRouteIndex = idx
                val route = outcome.routes[idx]
                // Distinct-corridor routes already carry maneuvers; penalty routes don't.
                val maneuvers = route.maneuvers.ifEmpty {
                    RoutingService.maneuversFor(
                        getApplication(), avoidedCameras(), lat, lon, dest.lat, dest.lon, route.penaltyPerCamera,
                    )
                }
                navEngine = NavigationEngine(route.copy(maneuvers = maneuvers))
            }
            recalculating = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        voice?.shutdown()
    }

    /**
     * Feed EVERY location fix here (regardless of navigation) so camera coverage can
     * follow the user. Throttled to roughly every 5 km; the repository itself no-ops
     * when the current tile is already fresh.
     */
    fun onLocationForCoverage(lat: Double, lon: Double) {
        lastKnownLat = lat
        lastKnownLon = lon
        if (!lastCoverageLat.isNaN() &&
            kotlin.math.abs(lat - lastCoverageLat) < 0.05 &&
            kotlin.math.abs(lon - lastCoverageLon) < 0.05
        ) {
            return
        }
        lastCoverageLat = lat
        lastCoverageLon = lon
        viewModelScope.launch {
            val outcome = repository.ensureCoverage(lat, lon)
            if (outcome is SyncOutcome.Success) lastSyncedMillis = outcome.syncedAt
        }
    }

    /**
     * Manual "refresh now" (↻): re-fetch cameras for the area the user is currently
     * VIEWING (the map center), falling back to the phone's location, then the default
     * region. This is what lets you scroll to Western NC and pull its cameras even when
     * the phone itself is still in Tennessee.
     */
    fun refreshNow() {
        if (syncState is SyncUiState.Syncing) return
        viewModelScope.launch {
            syncState = SyncUiState.Syncing
            val lat = mapCenterLat ?: lastKnownLat
            val lon = mapCenterLon ?: lastKnownLon
            val outcome = if (lat != null && lon != null) {
                repository.ensureCoverage(lat, lon, force = true)
            } else {
                repository.refresh() // no map/location fix yet → fall back to default region
            }
            syncState = when (outcome) {
                is SyncOutcome.Success -> {
                    lastSyncedMillis = outcome.syncedAt
                    refreshFlash = successFlash(outcome.newCount, outcome.cameraCount)
                    SyncUiState.Done(outcome.cameraCount)
                }
                is SyncOutcome.Error -> {
                    refreshFlash = RefreshFlash("Update failed", outcome.message, ok = false)
                    SyncUiState.Failed(outcome.message)
                }
                null -> SyncUiState.Idle
            }
        }
    }

    private fun successFlash(newCount: Int, total: Int): RefreshFlash {
        val detail = if (newCount > 0) {
            "$newCount new ${if (newCount == 1) "device" else "devices"} • $total in this area"
        } else {
            "No new devices • $total in this area"
        }
        return RefreshFlash("Updated", detail, ok = true)
    }
}
