package com.example.secondscreenandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.secondscreenandroid.connection.ConnectionViewModel
import com.example.secondscreenandroid.ui.ConnectScreen
import com.example.secondscreenandroid.ui.ScreenActivity

class MainActivity : ComponentActivity() {

    private val viewModel: ConnectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    ConnectScreen(
                        viewModel = viewModel,
                        onConnected = { host, port ->
                            startActivity(
                                ScreenActivity.buildIntent(this, host, port)
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startDiscovery(this)
    }
}
