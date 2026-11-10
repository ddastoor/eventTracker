package io.github.ddastoor.eventtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ddastoor.eventtracker.ui.AppRoot
import io.github.ddastoor.eventtracker.ui.EventTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EventTrackerTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Pull changes made elsewhere (e.g. edits in Google Sheets) whenever the app is opened.
        (application as EventTrackerApp).container.repository.requestSync()
    }
}
