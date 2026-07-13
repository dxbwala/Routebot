package com.routedns.routebot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.routedns.routebot.ui.navigation.RouteBotNavHost
import com.routedns.routebot.ui.theme.RouteBotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* granted map inspected via Settings / dashboard */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) {
            val perms = buildList {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.SEND_SMS)
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.READ_SMS)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permissionLauncher.launch(perms)
        }
        setContent {
            RouteBotTheme {
                RouteBotNavHost()
            }
        }
    }
}
