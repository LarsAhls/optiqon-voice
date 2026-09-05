package se.optiqon.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import se.optiqon.voice.ui.navigation.AppNavGraph
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OptiqonVoiceTheme {
                AppNavGraph()
            }
        }
    }
}
