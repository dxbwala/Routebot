package com.routedns.routebot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.routedns.routebot.ui.navigation.RouteBotNavHost
import com.routedns.routebot.ui.theme.RouteBotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RouteBotTheme {
                RouteBotNavHost()
            }
        }
    }
}
