package com.ladygo.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.nav.LadyGoNavHost
import com.ladygo.driver.push.PushTokenRegistrar
import com.ladygo.driver.ui.theme.LadyGoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var pushTokens: PushTokenRegistrar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Whenever she is signed in (app open, or just after the OTP), tell the server where to push.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionManager.isLoggedIn.collectLatest { loggedIn -> if (loggedIn) pushTokens.sync() }
            }
        }
        setContent {
            LadyGoTheme {
                LadyGoNavHost()
            }
        }
    }
}
