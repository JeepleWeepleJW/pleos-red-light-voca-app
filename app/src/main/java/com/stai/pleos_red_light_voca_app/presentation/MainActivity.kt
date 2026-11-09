package com.stai.pleos_red_light_voca_app.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.stai.pleos_red_light_voca_app.presentation.learning.LearningScreen
import com.stai.pleos_red_light_voca_app.presentation.theme.PleosredlightvocaappTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PleosredlightvocaappTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LearningScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
