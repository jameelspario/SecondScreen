package com.example.secondscreenandroid.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private lateinit var zoomPercentText: TextView
    private lateinit var decoder: VideoDecoder
    private lateinit var viewModel: ConnectionViewModel

    private var host: String = ""
    private var port: Int = 7070

    // Zoom & Pan state
    private var currentScale = 1.0f
    private val minScale = 1.0f
    private val maxScale = 5.0f
    private var translationX = 0f
    private var translationY = 0f

    // Multi-touch tracking
    private var isMultiTouch = false
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var isMouseDownOnMac = false

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

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

        setupGestures()
        setupStatusOverlay(root)
        setupZoomControls(root)

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

        setupTouchListener()

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

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                clampTranslations()
                updateSurfaceTransform()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                viewModel.onFrameReceived = null
            }
        })
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                applyZoom(currentScale * factor, detector.focusX, detector.focusY)
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.15f) {
                    animateToZoom(1.0f, e.x, e.y)
                } else {
                    animateToZoom(2.2f, e.x, e.y)
                }
                return true
            }
        })
    }

    private fun setupTouchListener() {
        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            val pointerCount = event.pointerCount

            if (pointerCount >= 2) {
                // Multi-touch pinch / pan mode
                if (!isMultiTouch) {
                    isMultiTouch = true
                    // Cancel any active Mac mouse-down to prevent dragging items
                    if (isMouseDownOnMac) {
                        val (nx, ny) = screenToVirtualNormalized(event.getX(0), event.getY(0))
                        viewModel.sendTouch(0x03, nx, ny) // UP
                        isMouseDownOnMac = false
                    }
                    lastMidX = (event.getX(0) + event.getX(1)) / 2f
                    lastMidY = (event.getY(0) + event.getY(1)) / 2f
                } else {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = midX - lastMidX
                    val dy = midY - lastMidY
                    translationX += dx
                    translationY += dy
                    lastMidX = midX
                    lastMidY = midY
                    clampTranslations()
                    updateSurfaceTransform()
                }
                return@setOnTouchListener true
            }

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (isMultiTouch) {
                    isMultiTouch = false
                    return@setOnTouchListener true
                }
            }

            if (isMultiTouch) {
                return@setOnTouchListener true
            }

            // Single finger Mac mouse control with coordinate mapping
            val (nx, ny) = screenToVirtualNormalized(event.x, event.y)
            val type: Byte = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isMouseDownOnMac = true
                    0x01
                }
                MotionEvent.ACTION_MOVE -> 0x02
                MotionEvent.ACTION_UP -> {
                    isMouseDownOnMac = false
                    0x03
                }
                MotionEvent.ACTION_CANCEL -> {
                    isMouseDownOnMac = false
                    0x03
                }
                else -> return@setOnTouchListener false
            }

            viewModel.sendTouch(type, nx, ny)
            true
        }
    }

    private fun applyZoom(targetScale: Float, focusX: Float, focusY: Float) {
        val oldScale = currentScale
        val newScale = targetScale.coerceIn(minScale, maxScale)
        if (newScale == oldScale) return

        // Zoom centered around focal point
        val relX = (focusX - translationX) / oldScale
        val relY = (focusY - translationY) / oldScale

        currentScale = newScale
        translationX = focusX - relX * newScale
        translationY = focusY - relY * newScale

        clampTranslations()
        updateSurfaceTransform()
    }

    private fun animateToZoom(targetScale: Float, focusX: Float, focusY: Float) {
        val clampedTarget = targetScale.coerceIn(minScale, maxScale)
        val startScale = currentScale
        val startTx = translationX
        val startTy = translationY

        val targetTx: Float
        val targetTy: Float
        if (clampedTarget <= 1.0f) {
            targetTx = 0f
            targetTy = 0f
        } else {
            val w = surfaceView.width.toFloat().coerceAtLeast(1f)
            val h = surfaceView.height.toFloat().coerceAtLeast(1f)
            val relX = (focusX - startTx) / startScale
            val relY = (focusY - startTy) / startScale
            val minTx = w * (1f - clampedTarget)
            val minTy = h * (1f - clampedTarget)
            targetTx = (focusX - relX * clampedTarget).coerceIn(minTx, 0f)
            targetTy = (focusY - relY * clampedTarget).coerceIn(minTy, 0f)
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                currentScale = startScale + (clampedTarget - startScale) * f
                translationX = startTx + (targetTx - startTx) * f
                translationY = startTy + (targetTy - startTy) * f
                updateSurfaceTransform()
            }
            start()
        }
    }

    private fun clampTranslations() {
        val w = surfaceView.width.toFloat()
        val h = surfaceView.height.toFloat()
        if (currentScale <= 1.0f) {
            translationX = 0f
            translationY = 0f
            return
        }
        val minTx = w * (1f - currentScale)
        val minTy = h * (1f - currentScale)
        translationX = translationX.coerceIn(minTx, 0f)
        translationY = translationY.coerceIn(minTy, 0f)
    }

    private fun updateSurfaceTransform() {
        surfaceView.pivotX = 0f
        surfaceView.pivotY = 0f
        surfaceView.scaleX = currentScale
        surfaceView.scaleY = currentScale
        surfaceView.translationX = translationX
        surfaceView.translationY = translationY
        zoomPercentText.text = "${(currentScale * 100).toInt()}%"
    }

    private fun screenToVirtualNormalized(screenX: Float, screenY: Float): Pair<Float, Float> {
        val w = surfaceView.width.toFloat().coerceAtLeast(1f)
        val h = surfaceView.height.toFloat().coerceAtLeast(1f)
        val virtX = (screenX - translationX) / currentScale
        val virtY = (screenY - translationY) / currentScale
        val nx = (virtX / w).coerceIn(0f, 1f)
        val ny = (virtY / h).coerceIn(0f, 1f)
        return Pair(nx, ny)
    }

    private fun setupZoomControls(root: FrameLayout) {
        val zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(0xB31E1E1E.toInt())
                cornerRadius = 32f
            }
            elevation = 16f
        }

        val btnMinus = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                val target = (currentScale - 0.5f).coerceAtLeast(1.0f)
                val cx = surfaceView.width / 2f
                val cy = surfaceView.height / 2f
                animateToZoom(target, cx, cy)
            }
        }

        zoomPercentText = TextView(this).apply {
            text = "100%"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(16, 10, 16, 10)
            setOnClickListener {
                // Tap percentage cycles: 100% -> 175% -> 250% -> 100%
                val nextScale = when {
                    currentScale < 1.3f -> 1.75f
                    currentScale < 2.2f -> 2.5f
                    else -> 1.0f
                }
                val cx = surfaceView.width / 2f
                val cy = surfaceView.height / 2f
                animateToZoom(nextScale, cx, cy)
            }
        }

        val btnPlus = TextView(this).apply {
            text = "＋"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                val target = (currentScale + 0.5f).coerceAtMost(maxScale)
                val cx = surfaceView.width / 2f
                val cy = surfaceView.height / 2f
                animateToZoom(target, cx, cy)
            }
        }

        zoomBar.addView(btnMinus)
        zoomBar.addView(zoomPercentText)
        zoomBar.addView(btnPlus)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 32
            rightMargin = 40
        }

        root.addView(zoomBar, params)
    }

    private fun setupStatusOverlay(root: FrameLayout) {
        val overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            background = GradientDrawable().apply {
                setColor(0xCC1A1A1A.toInt())
                cornerRadius = 24f
            }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
        decoder.release()
    }
}
