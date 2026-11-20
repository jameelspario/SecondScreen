package com.example.secondscreenandroid.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.secondscreenandroid.connection.ConnectionState
import com.example.secondscreenandroid.connection.ConnectionViewModel
import com.example.secondscreenandroid.video.VideoDecoder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScreenActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var decoder: VideoDecoder
    private lateinit var viewModel: ConnectionViewModel

    private var host: String = ""
    private var port: Int = 7070

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"

        fun buildIntent(source: android.content.Context, host: String, port: Int): Intent =
            Intent(source, ScreenActivity::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        port = intent.getIntExtra(EXTRA_PORT, 7070)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Go edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Status overlay container
        val overlayLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC1A1A1A.toInt())
                cornerRadius = 24f
            }
            background = shape
        }

        statusProgress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            text = "Connecting to Mac ($host:$port)..."
            setPadding(0, 16, 0, 0)
        }

        overlayLayout.addView(statusProgress)
        overlayLayout.addView(statusText)

        statusOverlay = overlayLayout
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(statusOverlay, overlayParams)

        setContentView(root)

        // Hide system bars
        val controller = WindowInsetsControllerCompat(window, surfaceView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        decoder = VideoDecoder()
        viewModel = ViewModelProvider(this)[ConnectionViewModel::class.java]

        decoder.onFirstFrameRendered = {
            runOnUiThread {
                statusOverlay.animate().alpha(0f).setDuration(400).withEndAction {
                    statusOverlay.visibility = View.GONE
                }.start()
            }
        }

        // Touch forwarding
        surfaceView.setOnTouchListener { view, event ->
            val w = view.width.toFloat().coerceAtLeast(1f)
            val h = view.height.toFloat().coerceAtLeast(1f)
            val nx = event.x / w
            val ny = event.y / h
            val type: Byte = when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> 0x01
                android.view.MotionEvent.ACTION_MOVE -> 0x02
                android.view.MotionEvent.ACTION_UP   -> 0x03
                else -> return@setOnTouchListener false
            }
            viewModel.sendTouch(type, nx, ny)
            true
        }

        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connecting -> {
                        statusOverlay.visibility = View.VISIBLE
                        statusOverlay.alpha = 1f
                        statusProgress.visibility = View.VISIBLE
                        statusText.text = "Connecting to Mac (${state.host}:${state.port})..."
                    }
                    is ConnectionState.Connected -> {
                        statusText.text = "Connected! Awaiting video frames..."
                    }
                    is ConnectionState.Error -> {
                        statusOverlay.visibility = View.VISIBLE
                        statusOverlay.alpha = 1f
                        statusProgress.visibility = View.VISIBLE
                        statusText.text = "Disconnected: ${state.message}\nReconnecting..."
                    }
                    else -> {}
                }
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                decoder.init(surface)
                viewModel.onFrameReceived = { data -> decoder.decode(data) }
                viewModel.connectToHost(host, port)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                viewModel.onFrameReceived = null
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
        decoder.release()
    }
}
