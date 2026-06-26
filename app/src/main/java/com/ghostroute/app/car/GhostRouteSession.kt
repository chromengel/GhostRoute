package com.ghostroute.app.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** A single Android Auto session — opens straight onto the moving map screen. */
class GhostRouteSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NavMapScreen(carContext)
}
