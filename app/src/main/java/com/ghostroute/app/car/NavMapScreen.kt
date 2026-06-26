package com.ghostroute.app.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ghostroute.app.data.CameraEntity
import com.ghostroute.app.data.CameraRepository
import com.ghostroute.app.location.LocationManagerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Android Auto screen: GhostRoute's offline map on the car display, following the
 * driver. Pulls location + cameras straight from their singletons (so it works even if
 * the phone UI was never opened), and shows the route the user picked on the phone
 * (shared via [NavHub]). The map is drawn by [CarMapRenderer] onto the car surface.
 */
class NavMapScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarMapRenderer(carContext)
    private val locationSource = LocationManagerSource(carContext)
    private var lastCameras: List<CameraEntity> = emptyList()
    private var scope: CoroutineScope? = null

    private val navHubListener: () -> Unit = {
        renderer.setOverlay(NavHub.routePoints, lastCameras)
        invalidate()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface = surfaceContainer.surface ?: return
            renderer.setSurface(surface, surfaceContainer.width, surfaceContainer.height)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            renderer.clearSurface()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            renderer.render()
        }
    }

    init {
        lifecycle.addObserver(this)
        // Ask the host for the map surface; callbacks arrive when it's ready.
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onStart(owner: LifecycleOwner) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        NavHub.addListener(navHubListener)
        renderer.setOverlay(NavHub.routePoints, lastCameras)
        locationSource.lastKnownLocation()?.let { renderer.updateLocation(it) }
        s.launch {
            locationSource.locationUpdates().collect { renderer.updateLocation(it) }
        }
        s.launch {
            CameraRepository.get(carContext).observeCameras().collect { cams ->
                lastCameras = cams
                renderer.setOverlay(NavHub.routePoints, cams)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        NavHub.removeListener(navHubListener)
        scope?.cancel()
        scope = null
    }

    override fun onGetTemplate(): Template {
        val recenter = Action.Builder()
            .setTitle("Recenter")
            .setOnClickListener { renderer.render() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(recenter).build())
            .build()
    }
}
