package com.ghostroute.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ghostroute.app.ui.map.MapScreen
import com.ghostroute.app.ui.theme.GhostRouteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keep the display awake while GhostRoute is in the foreground so the screen never
        // locks mid-drive. The flag only applies while this window is visible, so it stops
        // holding the screen on the moment you leave the app — no background battery drain.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            GhostRouteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MapScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
