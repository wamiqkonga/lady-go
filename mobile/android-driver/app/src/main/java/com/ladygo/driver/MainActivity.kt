package com.ladygo.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ladygo.driver.ui.HomeScreen
import com.ladygo.driver.ui.theme.LadyGoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LadyGoTheme {
                HomeScreen()
            }
        }
    }
}
