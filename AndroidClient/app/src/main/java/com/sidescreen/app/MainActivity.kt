package com.sidescreen.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbManager
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sidescreen.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar

private fun mainDiag(msg: String) = DiagLog.log("MA", msg)

private const val MAC_APP_URL = "https://sidescreen.dev"

class MainActivity : AppCompatActivity() {
    private lateinit var wirelessController: WirelessTabController
    private lateinit var appUpdater: AppUpdater
    private val pairedHostStorage by lazy { PairedHostStorage(this) }
    private val cameraPerm by lazy { CameraPermissionManager(this) }
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var ambientController: AmbientDisplayController
    private lateinit var ambientMediaStore: AmbientMediaStore
    private var ambientSyncServer: AmbientSyncServer? = null
    private var videoDecoder: VideoDecoder? = null
    private var streamClient: StreamClient? = null

    /** In-flight code-pairing attempt (issue #35); cancelled when its dialog closes. */
    private var pairingJob: Job? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private var currentTextureSurface: Surface? = null
    private var decoderUsingTextureView = false

    // Logical desktop behind the stream, 0 when the Mac predates desktopGeometry.
    // Shown next to the stream size; never used to size the decoder.
    private var desktopWidth = 0
    private var desktopHeight = 0

    private var displayWidth = 0 // 0 = no config received yet
    private var displayHeight = 0 // 0 = no config received yet
    private var displayRotation = 0 // 0, 90, 180, 270 degrees
    private var displayFlipHorizontal = false
    private var displayFlipVertical = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pingJob: kotlinx.coroutines.Job? = null

    // For dragging stats overlay
    private var isDraggingOverlay = false
    private var overlayDx = 0f
    private var overlayDy = 0f

    // Input prediction for low-latency gaming
    private val inputPredictor = InputPredictor()

    // Checklist status handler
    private val checklistHandler = Handler(Looper.getMainLooper())
    private var checklistRunnable: Runnable? = null
    private var isConnected = false // Track connection state to prevent checklist conflicts

    /** Tracks host-display privacy overlay state for idempotent setPrivacyMode. */
    private var privacyModeActive = false

    // Night-window boundary re-evaluation while privacy is active
    private val nightBoundaryHandler = Handler(Looper.getMainLooper())
    private var nightBoundaryRunnable: Runnable? = null

    // Live labels in an open settings dialog (updated after SAF picks / Mac sync)
    private var settingsPhotoNameView: TextView? = null
    private var settingsVideoNameView: TextView? = null
    private var settingsAmbientStatusView: TextView? = null
    private var settingsMacSyncStatusView: TextView? = null
    /** When settings is open, keep mode radios in sync with Mac preferredMode apply. */
    private var settingsAmbientModeGroup: RadioGroup? = null
    private var settingsAllowScreenOffSwitch: SwitchMaterial? = null
    private var settingsAllowScreenOffHint: TextView? = null

    /**
     * Last ambient PHOTOS/VIDEO fallback reason for the settings dialog.
     * In-memory only — never persisted across process death.
     */
    private var ambientFallbackStatus: String? = null

    /**
     * Compact Mac ambient-sync status for the settings dialog.
     * In-memory only — never persisted; rebuilt from store on launch when possible.
     */
    private var macSyncStatusText: String = "—"

    private val pickPhotoTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                mainDiag("Photo tree persist permission failed: ${e.message}")
            }
            prefs.photoTreeUri = uri.toString()
            val name =
                DocumentFile.fromTreeUri(this, uri)?.name
                    ?: uri.lastPathSegment
                    ?: "Folder"
            prefs.photoTreeDisplayName = name
            settingsPhotoNameView?.text = name
            if (privacyModeActive) {
                applyAmbientContent()
            }
        }

    private val pickVideoFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                mainDiag("Video persist permission failed: ${e.message}")
            }
            prefs.videoUri = uri.toString()
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Video"
            prefs.videoDisplayName = name
            settingsVideoNameView?.text = name
            if (privacyModeActive) {
                applyAmbientContent()
            }
        }
    // Demo mode: plays a bundled clip so the app can be tried without a Mac
    private var demoPlayer: DemoPlayer? = null
    private var demoActive = false
    private var demoTouchHintShown = false

    /** True while video is on screen — a real session or the demo. Drives the streaming chrome. */
    private val isStreamingUi get() = isConnected || demoActive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagLog.init(applicationContext)
        prefs = PreferencesManager(this)

        // Allow rotation based on device sensor when not connected
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable edge-to-edge display (draw behind system bars and cutout)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appUpdater = AppUpdater(this)

        ambientMediaStore = AmbientMediaStore(filesDir)
        macSyncStatusText =
            if (ambientMediaStore.hasSyncedMedia()) {
                ambientMediaStore.summaryOrEmpty()
            } else {
                "—"
            }
        ambientSyncServer =
            AmbientSyncServer(ambientMediaStore) { summary, preferredMode ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    macSyncStatusText = summary
                    settingsMacSyncStatusView?.text = "Mac sync: $summary"
                    // Apply preferredMode only after successful commit. Clear, failure,
                    // interruption, and legacy manifests leave preferredMode null → keep mode.
                    if (preferredMode != null) {
                        val mode = preferredMode.toAmbientMode()
                        prefs.ambientMode = mode
                        syncOpenSettingsAmbientModeUi(mode)
                    }
                    if (privacyModeActive) {
                        applyAmbientContent()
                    }
                }
            }
        ambientSyncServer?.start()

        ambientController =
            AmbientDisplayController(
                activity = this,
                clockView = binding.ambientClock,
                imageContainer = binding.ambientImage,
                imageA = binding.ambientImageA,
                imageB = binding.ambientImageB,
                playerView = binding.ambientVideo,
                contentResolver = contentResolver,
            )
        ambientController.onFallbackToClock = { reason ->
            mainDiag("Ambient fallback to CLOCK: $reason")
            val message = userFacingAmbientFallback(reason)
            ambientFallbackStatus = message
            settingsAmbientStatusView?.let { tv ->
                tv.text = message
                tv.visibility = View.VISIBLE
            }
        }
        // Settings button sits under the overlay; tap catcher opens settings while
        // the streamed frame remains fully covered by the black ambient container.
        val openAmbientSettings = View.OnClickListener { showSettingsDialog() }
        binding.privacyOverlay.setOnClickListener(openAmbientSettings)
        binding.ambientTapCatcher.setOnClickListener(openAmbientSettings)

        // Apply fullscreen mode immediately
        enableFullscreenMode()

        // Enable performance mode for gaming (after binding is initialized)
        enablePerformanceMode()

        setupSurface()
        setupUI()
        setupDraggableOverlay()
        setupSettingsButton()
        restoreOverlayPosition()
        restoreSettingsButtonPosition()
        startChecklistUpdates()
        setupModeToggle()
        setupWirelessController()
    }

    private fun setupModeToggle() {
        // Restore previous mode and reflect in toggle.
        val saved = prefs.connectionMode
        binding.modeToggleGroup.check(if (saved == ConnectionMode.WIRELESS) R.id.modeWireless else R.id.modeUSB)
        applyModeVisibility(saved)

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.modeWireless) ConnectionMode.WIRELESS else ConnectionMode.USB
            prefs.connectionMode = mode
            applyModeVisibility(mode)
            if (mode == ConnectionMode.WIRELESS) {
                wirelessController.show()
            }
        }
    }

    private fun applyModeVisibility(mode: ConnectionMode) {
        binding.usbModeContent.visibility = if (mode == ConnectionMode.USB) View.VISIBLE else View.GONE
        binding.wirelessModeContent.visibility = if (mode == ConnectionMode.WIRELESS) View.VISIBLE else View.GONE
        // USB checklist polls 127.0.0.1:port every 2s via adb-reverse to verify Mac
        // server reachability. While in Wireless mode that probe creates loopback
        // connections that fight the wireless session for the Mac's single client
        // slot — kicking the wireless client off seconds after it auths. Pause
        // checklist updates whenever Wireless is the active tab.
        if (mode == ConnectionMode.WIRELESS) {
            stopChecklistUpdates()
        } else {
            startChecklistUpdates()
        }
    }

    private fun setupWirelessController() {
        wirelessController =
            WirelessTabController(
                activity = this,
                views =
                    WirelessTabController.Views(
                        connecting = binding.wirelessConnecting,
                        firstTime = binding.wirelessFirstTime,
                        connected = binding.wirelessConnected,
                        pairedIdle = binding.wirelessPairedIdle,
                        repair = binding.wirelessTokenMismatch,
                        permDenied = binding.wirelessPermDenied,
                        scanButton = binding.wirelessScanButton,
                        rescanButton = binding.wirelessRescanButton,
                        enterCodeButton = binding.wirelessEnterCodeButton,
                        repairEnterCodeButton = binding.wirelessRepairEnterCodeButton,
                        permDeniedEnterCodeButton = binding.wirelessPermDeniedEnterCodeButton,
                        disconnectButton = binding.wirelessDisconnectButton,
                        forgetButton = binding.wirelessForgetButton,
                        reconnectButton = binding.wirelessReconnectButton,
                        idleForgetButton = binding.wirelessIdleForgetButton,
                        openSettingsButton = binding.wirelessOpenSettingsButton,
                        connectedMacName = binding.connectedMacName,
                        connectedMacIp = binding.connectedMacIp,
                        connectingLabel = binding.connectingLabel,
                        connectingSubtitle = binding.connectingSubtitle,
                        idleMacName = binding.idleMacName,
                        idleMacIp = binding.idleMacIp,
                        repairTitle = binding.repairTitle,
                        repairMessage = binding.repairMessage,
                    ),
                storage = pairedHostStorage,
                cameraPerm = cameraPerm,
                onConnectRequested = { host, port, token, deviceName, macName ->
                    connectWireless(host, port, token, deviceName, macName)
                },
                onCodePairRequested = { showCodePairingDialog() },
            )
        wirelessController.bind()
        binding.wirelessDisconnectButton.setOnClickListener { disconnect() }
        if (prefs.connectionMode == ConnectionMode.WIRELESS) {
            wirelessController.show()
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WirelessTabController.REQ_SCAN && resultCode == RESULT_OK) {
            val url = data?.getStringExtra(QRScannerActivity.EXTRA_URL) ?: return
            wirelessController.onScanResult(url)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WirelessTabController.REQ_CAMERA) {
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            wirelessController.onCameraPermissionResult(granted)
        }
    }

    /**
     * Enable performance mode for streaming
     * NOTE: setSustainedPerformanceMode is DISABLED - it causes thermal throttling
     * which makes the entire device laggy. Normal power management is more efficient.
     */
    private fun enablePerformanceMode() {
        try {
            // REMOVED: setSustainedPerformanceMode(true)
            // Sustained performance mode forces max CPU/GPU clocks which causes
            // thermal throttling on extended use, making the device laggy.
            // Let the SoC manage power efficiently instead.

            // Use PARTIAL_WAKE_LOCK with timeout to prevent battery drain
            // Screen is already kept on via FLAG_KEEP_SCREEN_ON
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SideScreen::PerformanceMode",
                )
            // 30 minute timeout instead of infinite acquire
            wakeLock?.acquire(30 * 60 * 1000L)

            log("🎮 Performance mode ENABLED (balanced)")
        } catch (e: Exception) {
            log("⚠️ Performance mode failed: ${e.message}")
        }
    }

    /**
     * Enable fullscreen immersive mode
     * Uses modern WindowInsets API on Android R+ for better system compatibility
     * Also handles display cutout (notch) to use full screen area
     */
    private fun enableFullscreenMode() {
        // Ensure we draw behind the cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    /**
     * Disable fullscreen mode (when disconnected)
     */
    private fun disableFullscreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSurface() {
        binding.surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    mainDiag("surfaceCreated")
                    log("Surface created")
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("surfaceChanged: ${width}x$height")
                    log("Surface changed: ${width}x$height")
                    currentSurfaceHolder = holder
                    initializeDecoderForCurrentSurface()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    mainDiag("surfaceDestroyed")
                    log("Surface destroyed")
                    if (!decoderUsingTextureView) {
                        videoDecoder?.release()
                        videoDecoder = null
                    }
                    currentSurfaceHolder = null
                }
            },
        )

        binding.textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("textureAvailable: ${width}x$height")
                    currentTextureSurface = Surface(surface)
                    initializeDecoderForCurrentSurface()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("textureSizeChanged: ${width}x$height")
                    applyTextureTransform()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    mainDiag("textureDestroyed")
                    if (decoderUsingTextureView) {
                        videoDecoder?.release()
                        videoDecoder = null
                    }
                    currentTextureSurface?.release()
                    currentTextureSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }

        if (binding.textureView.isAvailable && currentTextureSurface == null) {
            binding.textureView.surfaceTexture?.let { currentTextureSurface = Surface(it) }
        }

        binding.surfaceView.setOnTouchListener { view, event ->
            if (demoActive) showDemoTouchHint(event) else handleTouch(view, event)
            true
        }
        binding.textureView.setOnTouchListener { view, event ->
            if (demoActive) showDemoTouchHint(event) else handleTouch(view, event)
            true
        }
    }

    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            var host =
                binding.hostInput.text
                    .toString()
                    .ifEmpty { "127.0.0.1" }
            val port =
                binding.portInput.text
                    .toString()
                    .toIntOrNull() ?: 54321

            // Convert localhost to 127.0.0.1 for better Android compatibility
            if (host.equals("localhost", ignoreCase = true)) {
                host = "127.0.0.1"
            }

            // Validate input
            if (host.isBlank()) {
                showError("Please enter a host address")
                return@setOnClickListener
            }

            updateStatus("Connecting...")
            connect(host, port)
        }

        binding.disconnectButton.setOnClickListener {
            disconnect()
        }

        // Advanced settings toggle
        var advancedVisible = false
        binding.showAdvanced.setOnClickListener {
            advancedVisible = !advancedVisible
            binding.advancedSettings.visibility = if (advancedVisible) View.VISIBLE else View.GONE
            binding.showAdvanced.text = if (advancedVisible) "Hide Advanced Settings" else "Advanced Settings"
        }

        binding.demoButton.setOnClickListener { startDemo() }
        binding.watchVideoButton.setOnClickListener { showHowItWorksVideo() }
        binding.getMacAppLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(MAC_APP_URL)))
            } catch (_: Exception) {
                showError("Open $MAC_APP_URL in a browser to download the Mac app.")
            }
        }

        // Initial status
        updateStatus("Ready to connect")
    }

    // ==================== Demo Mode ====================

    private fun startDemo() {
        if (demoActive || isConnected) return
        val player =
            try {
                DemoPlayer(applicationContext) { videoDecoder }
            } catch (e: Exception) {
                mainDiag("Demo load failed: ${e.message}")
                showError("Could not start the demo: ${e.message}")
                return
            }
        mainDiag("Demo started")
        demoActive = true
        demoTouchHintShown = false
        // Cancel any in-flight connection attempt (USB connect or wireless
        // auto-reconnect) so its callbacks can't feed real frames into the demo
        // decoder or flip the UI to "connected" mid-demo.
        streamClient?.disconnect()
        streamClient = null
        videoDecoder?.release()
        videoDecoder = null

        displayWidth = DemoPlayer.WIDTH
        displayHeight = DemoPlayer.HEIGHT
        desktopWidth = 0
        desktopHeight = 0
        displayRotation = 0
        displayFlipHorizontal = false
        displayFlipVertical = false

        binding.settingsPanel.visibility = View.GONE
        applySettingsButtonVisibility()
        restoreSettingsButtonPosition()
        enableFullscreenMode()
        applyRotation(0, false, false)
        // The clip is a real 16:10 extended display: letterbox it instead of stretching
        // UI text on phones (a real session sizes the Mac display to the tablet).
        setSurfaceAspect("${DemoPlayer.WIDTH}:${DemoPlayer.HEIGHT}")
        updateResolutionOverlay()
        binding.resolutionText.append(" (demo)")
        binding.latencyText.text = "—"
        updateOverlayVisibility(prefs.showStatsOverlay)
        initializeDecoderForCurrentSurface()

        player.onStats = { fps, mbps ->
            runOnUiThread {
                binding.fpsText.text = String.format("%.1f", fps)
                binding.bitrateText.text = String.format("%.1f Mbps", mbps)
            }
        }
        demoPlayer = player
        player.start()
        android.widget.Toast
            .makeText(this, "Demo mode — open Settings (⚙) or press Back to exit", android.widget.Toast.LENGTH_LONG)
            .show()
    }

    private fun stopDemo() {
        if (!demoActive) return
        mainDiag("Demo stopped")
        demoPlayer?.stop()
        demoPlayer = null
        demoActive = false
        videoDecoder?.release()
        videoDecoder = null
        displayWidth = 0
        displayHeight = 0

        binding.settingsButton.visibility = View.GONE
        binding.statusBar.visibility = View.GONE
        binding.settingsPanel.visibility = View.VISIBLE
        setSurfaceAspect(null)
        // Recreate the surface so the last demo frame doesn't linger behind the panel
        binding.surfaceView.visibility = View.GONE
        binding.surfaceView.post { binding.surfaceView.visibility = View.VISIBLE }
        disableFullscreenMode()
        resetOrientationToSensor()
    }

    private fun setSurfaceAspect(ratio: String?) {
        val params = binding.surfaceView.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = ratio
        binding.surfaceView.layoutParams = params
    }

    /** Plays the bundled recording of a real Mac + phone session, looping, full screen. */
    private fun showHowItWorksVideo() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_how_it_works)
        val video = dialog.findViewById<android.widget.VideoView>(R.id.howItWorksVideo)
        video.setVideoURI(android.net.Uri.parse("android.resource://$packageName/${R.raw.how_it_works}"))
        video.setOnPreparedListener { player ->
            player.isLooping = true
            video.start()
        }
        dialog.findViewById<View>(R.id.closeVideoButton).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.show()
    }

    /** Touch has nothing to control in demo mode — say what it would do instead. */
    private fun showDemoTouchHint(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP || demoTouchHintShown) return
        demoTouchHintShown = true
        android.widget.Toast
            .makeText(
                this,
                "Demo mode — when connected, touching here clicks, drags and scrolls on your Mac.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
    }

    private fun showError(message: String) {
        runOnUiThread {
            android.app.AlertDialog
                .Builder(this)
                .setTitle("Connection Error")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupDraggableOverlay() {
        binding.statusBar.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingOverlay = true
                    overlayDx = view.x - event.rawX
                    overlayDy = view.y - event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingOverlay) {
                        // Calculate new position
                        var newX = event.rawX + overlayDx
                        var newY = event.rawY + overlayDy

                        // Get screen bounds
                        val parent = view.parent as View
                        val maxX = parent.width - view.width.toFloat()
                        val maxY = parent.height - view.height.toFloat()

                        // Constrain to screen bounds
                        newX = newX.coerceIn(0f, maxX)
                        newY = newY.coerceIn(0f, maxY)

                        view
                            .animate()
                            .x(newX)
                            .y(newY)
                            .setDuration(0)
                            .start()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDraggingOverlay) {
                        // Save position
                        prefs.overlayX = view.x
                        prefs.overlayY = view.y
                        isDraggingOverlay = false
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

    private fun restoreOverlayPosition() {
        val x = prefs.overlayX
        val y = prefs.overlayY

        if (x >= 0 && y >= 0) {
            binding.statusBar.post {
                binding.statusBar.x = x
                binding.statusBar.y = y
            }
        }

        // Apply opacity to both overlay and settings button
        val opacity = prefs.overlayOpacity
        updateOverlayOpacity(opacity)
        updateSettingsButtonOpacity(opacity)

        // Apply visibility
        updateOverlayVisibility(prefs.showStatsOverlay)
    }

    private fun updateOverlayOpacity(opacity: Float) {
        binding.statusBar.alpha = opacity
    }

    private fun updateOverlayVisibility(show: Boolean) {
        if ((streamClient != null || demoActive) && show) {
            binding.statusBar.visibility = View.VISIBLE
            // Restore position when showing
            val x = prefs.overlayX
            val y = prefs.overlayY
            if (x >= 0 && y >= 0) {
                binding.statusBar.post {
                    binding.statusBar.x = x
                    binding.statusBar.y = y
                }
            }
        } else {
            binding.statusBar.visibility = View.GONE
        }
    }

    /**
     * When settings is open, mirror Mac-applied preferredMode into radios and
     * allow-screen-off availability without double-writing prefs.
     */
    private fun syncOpenSettingsAmbientModeUi(mode: AmbientMode) {
        val group = settingsAmbientModeGroup ?: return
        val radioId =
            when (mode) {
                AmbientMode.BLACK -> R.id.ambientModeBlack
                AmbientMode.CLOCK -> R.id.ambientModeClock
                AmbientMode.PHOTOS -> R.id.ambientModePhotos
                AmbientMode.VIDEO -> R.id.ambientModeVideo
            }
        group.setOnCheckedChangeListener(null)
        group.check(radioId)
        val allowEnabled = mode == AmbientMode.BLACK
        settingsAllowScreenOffSwitch?.isEnabled = allowEnabled
        settingsAllowScreenOffHint?.text =
            if (allowEnabled) {
                "When Mac display sleeps, let tablet screen turn off"
            } else {
                "Only available in Black mode (clock/photos/video keep the screen on)"
            }
        group.setOnCheckedChangeListener { _, checkedId ->
            val selected =
                when (checkedId) {
                    R.id.ambientModeClock -> AmbientMode.CLOCK
                    R.id.ambientModePhotos -> AmbientMode.PHOTOS
                    R.id.ambientModeVideo -> AmbientMode.VIDEO
                    else -> AmbientMode.BLACK
                }
            prefs.ambientMode = selected
            val enabled = selected == AmbientMode.BLACK
            settingsAllowScreenOffSwitch?.isEnabled = enabled
            settingsAllowScreenOffHint?.text =
                if (enabled) {
                    "When Mac display sleeps, let tablet screen turn off"
                } else {
                    "Only available in Black mode (clock/photos/video keep the screen on)"
                }
            if (privacyModeActive) {
                applyAmbientContent()
            }
        }
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = dialog.findViewById<View>(android.R.id.content)
        val showStatsSwitch = view.findViewById<SwitchMaterial>(R.id.showStatsSwitch)
        val allowScreenOffSwitch = view.findViewById<SwitchMaterial>(R.id.allowScreenOffOnHostSleepSwitch)
        val allowScreenOffHint = view.findViewById<TextView>(R.id.allowScreenOffHint)
        val ambientModeGroup = view.findViewById<RadioGroup>(R.id.ambientModeGroup)
        val photoFolderName = view.findViewById<TextView>(R.id.photoFolderName)
        val videoFileName = view.findViewById<TextView>(R.id.videoFileName)
        val ambientFallbackStatusView = view.findViewById<TextView>(R.id.ambientFallbackStatus)
        val macSyncStatusView = view.findViewById<TextView>(R.id.macSyncStatus)
        val selectPhotoFolderButton = view.findViewById<MaterialButton>(R.id.selectPhotoFolderButton)
        val selectVideoButton = view.findViewById<MaterialButton>(R.id.selectVideoButton)
        val advancedFallbackHeader = view.findViewById<View>(R.id.advancedFallbackHeader)
        val advancedFallbackContent = view.findViewById<View>(R.id.advancedFallbackContent)
        val advancedFallbackChevron = view.findViewById<TextView>(R.id.advancedFallbackChevron)
        val nightWindowSwitch = view.findViewById<SwitchMaterial>(R.id.nightWindowSwitch)
        val nightStartButton = view.findViewById<MaterialButton>(R.id.nightStartButton)
        val nightEndButton = view.findViewById<MaterialButton>(R.id.nightEndButton)
        val hideSettingsSwitch = view.findViewById<SwitchMaterial>(R.id.hideSettingsSwitch)
        val opacitySlider = view.findViewById<Slider>(R.id.opacitySlider)
        val opacityValue = view.findViewById<TextView>(R.id.opacityValue)
        val resetButton = view.findViewById<View>(R.id.resetPositionButton)
        val resetSettingsBtn = view.findViewById<View>(R.id.resetSettingsButton)
        val disconnectButton = view.findViewById<View>(R.id.disconnectSettingsButton)
        val closeButton = view.findViewById<View>(R.id.closeButton)

        settingsPhotoNameView = photoFolderName
        settingsVideoNameView = videoFileName
        settingsAmbientStatusView = ambientFallbackStatusView
        settingsMacSyncStatusView = macSyncStatusView
        settingsAmbientModeGroup = ambientModeGroup
        settingsAllowScreenOffSwitch = allowScreenOffSwitch
        settingsAllowScreenOffHint = allowScreenOffHint
        macSyncStatusView.text = "Mac sync: $macSyncStatusText"

        // Advanced: tablet-local fallback — collapsed by default (UI-only, not persisted).
        advancedFallbackContent.visibility = View.GONE
        advancedFallbackChevron.text = "▸"
        advancedFallbackHeader.setOnClickListener {
            val expand = advancedFallbackContent.visibility != View.VISIBLE
            advancedFallbackContent.visibility = if (expand) View.VISIBLE else View.GONE
            advancedFallbackChevron.text = if (expand) "▾" else "▸"
        }

        dialog.setOnDismissListener {
            if (settingsPhotoNameView === photoFolderName) settingsPhotoNameView = null
            if (settingsVideoNameView === videoFileName) settingsVideoNameView = null
            if (settingsAmbientStatusView === ambientFallbackStatusView) {
                settingsAmbientStatusView = null
            }
            if (settingsMacSyncStatusView === macSyncStatusView) {
                settingsMacSyncStatusView = null
            }
            if (settingsAmbientModeGroup === ambientModeGroup) {
                settingsAmbientModeGroup = null
            }
            if (settingsAllowScreenOffSwitch === allowScreenOffSwitch) {
                settingsAllowScreenOffSwitch = null
            }
            if (settingsAllowScreenOffHint === allowScreenOffHint) {
                settingsAllowScreenOffHint = null
            }
        }

        // Only show Disconnect when actually streaming. Otherwise the button is
        // a no-op and confuses users into clicking it twice.
        disconnectButton.visibility = if (isStreamingUi) View.VISIBLE else View.GONE
        if (demoActive) (disconnectButton as TextView).text = "Exit demo"

        // Position buttons (8 directions)
        val cornerTopLeft = view.findViewById<MaterialButton>(R.id.cornerTopLeft)
        val cornerTopRight = view.findViewById<MaterialButton>(R.id.cornerTopRight)
        val cornerBottomLeft = view.findViewById<MaterialButton>(R.id.cornerBottomLeft)
        val cornerBottomRight = view.findViewById<MaterialButton>(R.id.cornerBottomRight)
        val positionTopCenter = view.findViewById<MaterialButton>(R.id.positionTopCenter)
        val positionBottomCenter = view.findViewById<MaterialButton>(R.id.positionBottomCenter)
        val positionCenterLeft = view.findViewById<MaterialButton>(R.id.positionCenterLeft)
        val positionCenterRight = view.findViewById<MaterialButton>(R.id.positionCenterRight)

        // Load current settings
        showStatsSwitch.isChecked = prefs.showStatsOverlay
        allowScreenOffSwitch.isChecked = prefs.allowScreenOffOnHostSleep
        hideSettingsSwitch.isChecked = prefs.hideSettingsButton
        opacitySlider.value = prefs.overlayOpacity
        opacityValue.text = "${(prefs.overlayOpacity * 100).toInt()}%"
        photoFolderName.text = prefs.photoTreeDisplayName ?: "None selected"
        videoFileName.text = prefs.videoDisplayName ?: "None selected"
        val lastFallback = ambientFallbackStatus
        if (lastFallback.isNullOrBlank()) {
            ambientFallbackStatusView.visibility = View.GONE
            ambientFallbackStatusView.text = ""
        } else {
            ambientFallbackStatusView.text = lastFallback
            ambientFallbackStatusView.visibility = View.VISIBLE
        }
        nightWindowSwitch.isChecked = prefs.nightWindowEnabled
        nightStartButton.text = prefs.nightStart
        nightEndButton.text = prefs.nightEnd

        fun radioIdForMode(mode: AmbientMode): Int =
            when (mode) {
                AmbientMode.BLACK -> R.id.ambientModeBlack
                AmbientMode.CLOCK -> R.id.ambientModeClock
                AmbientMode.PHOTOS -> R.id.ambientModePhotos
                AmbientMode.VIDEO -> R.id.ambientModeVideo
            }

        fun modeForRadioId(id: Int): AmbientMode =
            when (id) {
                R.id.ambientModeClock -> AmbientMode.CLOCK
                R.id.ambientModePhotos -> AmbientMode.PHOTOS
                R.id.ambientModeVideo -> AmbientMode.VIDEO
                else -> AmbientMode.BLACK
            }

        fun updateAllowScreenOffEnabled(mode: AmbientMode) {
            val enabled = mode == AmbientMode.BLACK
            allowScreenOffSwitch.isEnabled = enabled
            if (enabled) {
                allowScreenOffHint.text = "When Mac display sleeps, let tablet screen turn off"
            } else {
                allowScreenOffHint.text =
                    "Only available in Black mode (clock/photos/video keep the screen on)"
            }
        }

        fun attachAmbientModeListener() {
            ambientModeGroup.setOnCheckedChangeListener { _, checkedId ->
                val mode = modeForRadioId(checkedId)
                prefs.ambientMode = mode
                updateAllowScreenOffEnabled(mode)
                if (privacyModeActive) {
                    applyAmbientContent()
                }
            }
        }

        ambientModeGroup.check(radioIdForMode(prefs.ambientMode))
        updateAllowScreenOffEnabled(prefs.ambientMode)
        attachAmbientModeListener()

        selectPhotoFolderButton.setOnClickListener {
            pickPhotoTree.launch(null)
        }

        selectVideoButton.setOnClickListener {
            pickVideoFile.launch(arrayOf("video/*"))
        }

        nightWindowSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.nightWindowEnabled = isChecked
            if (privacyModeActive) {
                applyAmbientContent()
            }
        }

        nightStartButton.setOnClickListener {
            val minutes = NightWindow.parseHhMm(prefs.nightStart)
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    val formatted = NightWindow.formatHhMm(hourOfDay * 60 + minute)
                    prefs.nightStart = formatted
                    nightStartButton.text = formatted
                    if (privacyModeActive) {
                        applyAmbientContent()
                    }
                },
                minutes / 60,
                minutes % 60,
                true,
            ).show()
        }

        nightEndButton.setOnClickListener {
            val minutes = NightWindow.parseHhMm(prefs.nightEnd)
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    val formatted = NightWindow.formatHhMm(hourOfDay * 60 + minute)
                    prefs.nightEnd = formatted
                    nightEndButton.text = formatted
                    if (privacyModeActive) {
                        applyAmbientContent()
                    }
                },
                minutes / 60,
                minutes % 60,
                true,
            ).show()
        }

        // Highlight current position selection (8 positions)
        // 0=BottomRight, 1=BottomLeft, 2=TopRight, 3=TopLeft
        // 4=TopCenter, 5=BottomCenter, 6=CenterLeft, 7=CenterRight
        fun updatePositionSelection(selectedPosition: Int) {
            val buttons =
                listOf(
                    cornerBottomRight,
                    cornerBottomLeft,
                    cornerTopRight,
                    cornerTopLeft,
                    positionTopCenter,
                    positionBottomCenter,
                    positionCenterLeft,
                    positionCenterRight,
                )
            buttons.forEachIndexed { index, button ->
                if (index == selectedPosition) {
                    button.backgroundTintList =
                        android.content.res.ColorStateList
                            .valueOf(0x334CAF50)
                } else {
                    button.backgroundTintList = null
                }
            }
        }
        updatePositionSelection(prefs.settingsButtonCorner)

        // Setup listeners
        showStatsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.showStatsOverlay = isChecked
            updateOverlayVisibility(isChecked)
        }

        allowScreenOffSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.allowScreenOffOnHostSleep = isChecked
            // Re-apply effective ambient + flags without re-entering privacy mode.
            if (privacyModeActive) {
                applyAmbientContent()
            }
        }

        hideSettingsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.hideSettingsButton = isChecked
            if (isStreamingUi) {
                applySettingsButtonVisibility()
            }
            if (isChecked) {
                android.widget.Toast
                    .makeText(
                        this,
                        "Settings icon hidden — use the back gesture to reveal it",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
            }
        }

        opacitySlider.addOnChangeListener { _, value, _ ->
            prefs.overlayOpacity = value
            updateOverlayOpacity(value)
            updateSettingsButtonOpacity(value)
            opacityValue.text = "${(value * 100).toInt()}%"
        }

        resetButton.setOnClickListener {
            prefs.overlayX = -1f
            prefs.overlayY = -1f
            // Use displayMetrics for reliable positioning
            val dm = resources.displayMetrics
            binding.statusBar
                .animate()
                .x(dm.widthPixels - binding.statusBar.width - 48f)
                .y(48f)
                .setDuration(300)
                .start()
        }

        // Position button listeners (8 directions)
        cornerBottomRight.setOnClickListener {
            prefs.settingsButtonCorner = 0
            updatePositionSelection(0)
            updateSettingsButtonPosition(0)
        }

        cornerBottomLeft.setOnClickListener {
            prefs.settingsButtonCorner = 1
            updatePositionSelection(1)
            updateSettingsButtonPosition(1)
        }

        cornerTopRight.setOnClickListener {
            prefs.settingsButtonCorner = 2
            updatePositionSelection(2)
            updateSettingsButtonPosition(2)
        }

        cornerTopLeft.setOnClickListener {
            prefs.settingsButtonCorner = 3
            updatePositionSelection(3)
            updateSettingsButtonPosition(3)
        }

        positionTopCenter.setOnClickListener {
            prefs.settingsButtonCorner = 4
            updatePositionSelection(4)
            updateSettingsButtonPosition(4)
        }

        positionBottomCenter.setOnClickListener {
            prefs.settingsButtonCorner = 5
            updatePositionSelection(5)
            updateSettingsButtonPosition(5)
        }

        positionCenterLeft.setOnClickListener {
            prefs.settingsButtonCorner = 6
            updatePositionSelection(6)
            updateSettingsButtonPosition(6)
        }

        positionCenterRight.setOnClickListener {
            prefs.settingsButtonCorner = 7
            updatePositionSelection(7)
            updateSettingsButtonPosition(7)
        }

        resetSettingsBtn.setOnClickListener {
            prefs.settingsButtonCorner = 0
            updatePositionSelection(0)
            updateSettingsButtonPosition(0)
        }

        disconnectButton.setOnClickListener {
            dialog.dismiss()
            if (demoActive) stopDemo() else disconnect()
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        // Cap dialog height to 85% of screen so content scrolls on smaller screens / landscape
        dialog.window?.let { win ->
            val maxH = (resources.displayMetrics.heightPixels * 0.85).toInt()
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxH)
        }
    }

    private fun updateSettingsButtonOpacity(opacity: Float) {
        binding.settingsButton.alpha = opacity
    }

    private fun setupSettingsButton() {
        // Simple click to show settings dialog
        // Position can be changed via corner buttons in settings
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        // Escape hatch for the hidden icon: the back gesture briefly reveals it
        // instead of leaving the app. Back is not forwarded to the Mac, so this
        // cannot conflict with streamed touch input.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isStreamingUi && prefs.hideSettingsButton &&
                        binding.settingsButton.visibility != View.VISIBLE
                    ) {
                        revealSettingsButtonTemporarily()
                    } else if (demoActive) {
                        stopDemo()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    /** Streaming-time visibility of the settings icon, honoring the hide preference. */
    private fun applySettingsButtonVisibility() {
        binding.settingsButton.visibility =
            if (prefs.hideSettingsButton) View.GONE else View.VISIBLE
    }

    private val revealHandler = Handler(Looper.getMainLooper())
    private val hideSettingsButtonRunnable =
        Runnable {
            if (isStreamingUi && prefs.hideSettingsButton) {
                binding.settingsButton.visibility = View.GONE
            }
        }

    private fun revealSettingsButtonTemporarily() {
        binding.settingsButton.visibility = View.VISIBLE
        revealHandler.removeCallbacks(hideSettingsButtonRunnable)
        revealHandler.postDelayed(hideSettingsButtonRunnable, 5_000L)
    }

    private fun restoreSettingsButtonPosition() {
        updateSettingsButtonPosition(prefs.settingsButtonCorner)
    }

    /**
     * Use ConstraintSet to position settings button - most reliable method
     * Works correctly with orientation changes
     * Supports 8 positions: 4 corners + 4 edges
     */
    private fun updateSettingsButtonPosition(position: Int) {
        val constraintLayout = binding.root as ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        val buttonId = binding.settingsButton.id
        val marginDp = (24 * resources.displayMetrics.density).toInt()

        // Clear all constraints first
        constraintSet.clear(buttonId, ConstraintSet.TOP)
        constraintSet.clear(buttonId, ConstraintSet.BOTTOM)
        constraintSet.clear(buttonId, ConstraintSet.START)
        constraintSet.clear(buttonId, ConstraintSet.END)

        when (position) {
            0 -> { // Bottom Right (default)
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }

            1 -> { // Bottom Left
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    marginDp,
                )
            }

            2 -> { // Top Right
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginDp)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }

            3 -> { // Top Left
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginDp)
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    marginDp,
                )
            }

            4 -> { // Top Center
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginDp)
                constraintSet.connect(buttonId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            }

            5 -> { // Bottom Center
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(buttonId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            }

            6 -> { // Center Left
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
                constraintSet.connect(buttonId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    marginDp,
                )
            }

            7 -> { // Center Right
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
                constraintSet.connect(buttonId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }

            else -> { // Default to bottom right
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }
        }

        // Reset any absolute positioning that might have been set
        binding.settingsButton.translationX = 0f
        binding.settingsButton.translationY = 0f

        constraintSet.applyTo(constraintLayout)
    }

    /**
     * Display config from a new Mac always arrives AFTER codecSelected, so a
     * missing negotiation at this point proves the Mac app predates H.264
     * support — surface that instead of a silent black screen.
     */
    private fun warnIfAvcOnlyWithoutNegotiation() {
        if (!CodecCapabilities.hasHevcDecoder && streamClient?.codecNegotiated != true) {
            mainDiag("AVC-only device but Mac did not negotiate codec — Mac app too old")
            runOnUiThread {
                updateStatus("This device has no HEVC decoder. Update the SideScreen Mac app to enable H.264 support.")
            }
        }
    }

    /**
     * Recreate the decoder when the negotiated stream codec doesn't match the
     * decoder's mime. Display config and codecSelected can arrive in either
     * order on reconnect; without this, a decoder created with the default
     * HEVC mime keeps consuming the H.264 stream and never outputs a frame —
     * a permanent black screen on AVC-only devices (e.g. Unisoc tablets).
     */
    private fun onStreamCodecSelected(isHevc: Boolean) {
        val expectedMime =
            if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        runOnUiThread {
            val dec = videoDecoder
            when {
                dec == null -> {
                    mainDiag("Codec selected ($expectedMime) — initializing deferred decoder")
                    initializeDecoderForCurrentSurface()
                }
                dec.mime != expectedMime -> {
                    mainDiag("Stream codec is $expectedMime but decoder is ${dec.mime} — recreating")
                    dec.release()
                    videoDecoder = null
                    initializeDecoderForCurrentSurface()
                }
            }
        }
    }

    /**
     * Stream size first, since that is what the decoder and every other stat in this overlay
     * describe. The desktop size is appended only when it differs, so a scaled HiDPI desktop is
     * visible rather than silently standing in for the resolution actually being sent.
     */
    private fun updateResolutionOverlay() {
        if (displayWidth <= 0 || displayHeight <= 0) return
        val stream = "${displayWidth}x$displayHeight"
        val differs = desktopWidth > 0 && (desktopWidth != displayWidth || desktopHeight != displayHeight)
        binding.resolutionText.text =
            if (differs) "$stream (desktop ${desktopWidth}x$desktopHeight)" else stream
    }

    private fun shouldUseTextureView(): Boolean = displayFlipHorizontal || displayFlipVertical

    private fun activeVideoSurface(): Pair<Surface, Boolean>? {
        return if (shouldUseTextureView()) {
            currentTextureSurface?.takeIf { it.isValid }?.let { it to true }
        } else {
            currentSurfaceHolder?.surface?.takeIf { it.isValid }?.let { it to false }
        }
    }

    private fun initializeDecoderForCurrentSurface() {
        if (displayWidth <= 0 || displayHeight <= 0) {
            mainDiag("initializeDecoder skipped — no display config yet")
            return
        }
        // AVC-only device: an HEVC decoder can never decode the H.264 stream
        // the Mac will send — defer until codecSelected arrives, then
        // onStreamCodecSelected initializes with the correct mime.
        if (!demoActive && !CodecCapabilities.hasHevcDecoder && streamClient?.codecNegotiated != true) {
            mainDiag("initializeDecoder deferred — AVC-only device awaiting codec negotiation")
            return
        }

        val (surface, useTextureView) =
            activeVideoSurface() ?: run {
                val kind = if (shouldUseTextureView()) "TextureView" else "SurfaceView"
                mainDiag("initializeDecoder skipped — no valid $kind surface")
                return
            }

        if (videoDecoder != null && decoderUsingTextureView == useTextureView) {
            videoDecoder?.updateResolution(displayWidth, displayHeight)
            return
        }

        videoDecoder?.release()
        videoDecoder = null
        decoderUsingTextureView = useTextureView

        mainDiag(
            "initializeDecoder called, surface=$surface, valid=${surface.isValid}, " +
                "res=${displayWidth}x$displayHeight, texture=$useTextureView",
        )
        try {
            val displayObj =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
            val mime =
                if (demoActive || streamClient?.streamCodecIsHevc == false) {
                    MediaFormat.MIMETYPE_VIDEO_AVC
                } else {
                    MediaFormat.MIMETYPE_VIDEO_HEVC
                }
            videoDecoder = VideoDecoder(surface, displayObj, displayWidth, displayHeight, mime)
            videoDecoder?.onFrameDecoded = { buffer ->
                streamClient?.releaseBuffer(buffer)
            }
            videoDecoder?.onKeyframeRequired = { force, reason ->
                streamClient?.requestKeyframe(force = force, reason = reason)
            }
            videoDecoder?.onDecoderStalled = {
                // Black screen with live stats: tell the user why instead of
                // staying silent (issue #41). Toast renders above the (black)
                // SurfaceView; the settings panel is hidden while streaming.
                // The sustainable size, not the decoder's advertised one — that is typically far
                // above anything it can really output, which made this message actively misleading.
                val panel = PanelGeometry.of(displayObj)
                val cap =
                    panel?.let {
                        CodecCapabilities.maxStreamSize(
                            mime,
                            it.width,
                            it.height,
                            CodecCapabilities.REFERENCE_FPS,
                        )
                    }
                runOnUiThread {
                    val capText = cap?.let { " (max ~${it.first}×${it.second})" } ?: ""
                    android.widget.Toast
                        .makeText(
                            this,
                            "No video output — the stream resolution may exceed " +
                                "this tablet's decoder limit$capText. " +
                                "Lower the resolution or refresh rate, or disable HiDPI, on the Mac.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                }
            }
            streamClient?.requestKeyframe(force = true, reason = "decoder initialized")
            mainDiag("Decoder initialized OK ${displayWidth}x$displayHeight mime=$mime, texture=$useTextureView")
            log("✅ Decoder initialized ${displayWidth}x$displayHeight $mime (${displayObj?.refreshRate ?: 60f}Hz)")
        } catch (e: Exception) {
            decoderUsingTextureView = false
            mainDiag("Decoder init FAILED: ${e.message}")
            log("❌ Failed to initialize decoder: ${e.message}")
            runOnUiThread {
                updateStatus("Video decoder failed: ${e.message}")
            }
        }
    }

    /**
     * Wire up all StreamClient callbacks. Used by both USB connect() and wireless connectWireless().
     * USB path must also call this — do not re-inline callbacks there.
     */
    private fun setupStreamClientCallbacks() {
        streamClient?.onFrameReceived = { frameData, frameSize, timestamp, isKeyframe ->
            val dec = videoDecoder
            if (dec != null) {
                dec.decode(frameData, frameSize, timestamp, isKeyframe)
            } else {
                streamClient?.releaseBuffer(frameData)
                mainDiag("FRAME DROPPED: videoDecoder is null!")
            }
        }

        videoDecoder?.onFrameDecoded = { buffer ->
            streamClient?.releaseBuffer(buffer)
        }

        videoDecoder?.onKeyframeRequired = { force, reason ->
            streamClient?.requestKeyframe(force = force, reason = reason)
        }

        streamClient?.onLatencyMeasured = { rttMs ->
            runOnUiThread {
                binding.latencyText.text = String.format("%.1f ms", rttMs)
            }
        }

        streamClient?.onHostDisplayPower = { sleeping ->
            runOnUiThread {
                setPrivacyMode(sleeping)
            }
        }

        streamClient?.onConnectionStatus = { connected ->
            runOnUiThread {
                isConnected = connected
                if (connected) {
                    updateStatus("Connected - Streaming active")
                } else {
                    updateStatus("Disconnected")
                }
                binding.connectButton.isEnabled = !connected
                binding.disconnectButton.isEnabled = connected
                binding.statusIndicator.setBackgroundResource(
                    if (connected) android.R.color.holo_green_light else android.R.color.holo_red_light,
                )
                if (connected) {
                    startPingTimer()
                    stopChecklistUpdates()
                    enableFullscreenMode()
                    binding.settingsPanel.visibility = View.GONE
                    applySettingsButtonVisibility()
                    restoreSettingsButtonPosition()
                    updateOverlayVisibility(prefs.showStatsOverlay)
                    // For wireless mode, transition controller to CONNECTED here —
                    // not in MainActivity.connectWireless's coroutine after the
                    // receive loop returns (that runs AFTER disconnect, causing
                    // a stale CONNECTED transition that hides the PAIRED_IDLE UI).
                    if (prefs.connectionMode == ConnectionMode.WIRELESS) {
                        val entry = pairedHostStorage.load()
                        wirelessController.onConnectSuccess(
                            entry?.macName ?: "Mac",
                            entry?.host ?: "—",
                        )
                    }
                } else {
                    // Hide privacy overlay and restore KEEP_SCREEN_ON so
                    // "allow screen off" mode cannot leave the tablet dimmed.
                    setPrivacyMode(false)
                    stopPingTimer()
                    disableFullscreenMode()
                    resetOrientationToSensor()
                    binding.settingsPanel.visibility = View.VISIBLE
                    binding.settingsButton.visibility = View.GONE
                    binding.statusBar.visibility = View.GONE
                    val mode = prefs.connectionMode
                    val willTransition = mode == ConnectionMode.WIRELESS
                    android.util.Log.i(
                        "MainActivity",
                        "onConnectionStatus(false) — mode=$mode, willTransition=$willTransition",
                    )
                    if (mode == ConnectionMode.WIRELESS) {
                        // Don't restart checklist (it conflicts with wireless on Mac).
                        // Tell wireless controller to show the idle/reconnect UI.
                        wirelessController.onStreamDisconnected()
                    } else {
                        log("📋 Restarting checklist updates")
                        startChecklistUpdates()
                    }
                }
            }
        }

        streamClient?.onDesktopSize = { w, h ->
            desktopWidth = w
            desktopHeight = h
            runOnUiThread { updateResolutionOverlay() }
        }

        streamClient?.onCodecSelected = { isHevc -> onStreamCodecSelected(isHevc) }

        streamClient?.onDisplaySize = { width, height, rotation, flipHorizontal, flipVertical ->
            mainDiag("onDisplaySize: ${width}x$height @ $rotation°, h=$flipHorizontal, v=$flipVertical")
            warnIfAvcOnlyWithoutNegotiation()
            displayWidth = width
            displayHeight = height
            displayRotation = rotation
            displayFlipHorizontal = flipHorizontal
            displayFlipVertical = flipVertical
            runOnUiThread {
                updateResolutionOverlay()
                applyRotation(rotation, flipHorizontal, flipVertical)
                initializeDecoderForCurrentSurface()
            }
            log("Display: ${width}x$height @ $rotation°")
        }

        streamClient?.onStats = { fps, mbps ->
            runOnUiThread {
                binding.fpsText.text = String.format("%.1f", fps)
                binding.bitrateText.text = String.format("%.1f Mbps", mbps)
            }
        }
    }

    /**
     * Single entry for host-display privacy mode. Idempotent: repeating the
     * same sleeping value has no side effects (fast sleep/wake loops stay clean).
     *
     * sleeping=true  → show ambient container; apply effective content + flags
     * sleeping=false → hide container; stop media; restore KEEP_SCREEN_ON
     */
    private fun setPrivacyMode(sleeping: Boolean) {
        if (privacyModeActive == sleeping) return
        privacyModeActive = sleeping
        if (sleeping) {
            binding.privacyOverlay.visibility = View.VISIBLE
            // Clear sticky turn-screen-on so a later Activity resume does not
            // unexpectedly relight the display after host sleep.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(false)
            }
            applyAmbientContent()
            mainDiag(
                "Privacy mode ON (mode=${prefs.ambientMode}, " +
                    "allowScreenOff=${prefs.allowScreenOffOnHostSleep}, " +
                    "night=${prefs.nightWindowEnabled})",
            )
        } else {
            cancelNightBoundary()
            ambientController.stopAll()
            binding.privacyOverlay.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true)
            }
            mainDiag("Privacy mode OFF")
        }
    }

    /**
     * Apply priority rules while privacy is active (or no-op when inactive):
     *  - night window → effective BLACK + clear KEEP_SCREEN_ON
     *  - else selected ambient mode; KEEP_SCREEN_ON cleared only for
     *    BLACK + allowScreenOffOnHostSleep
     * Setting changes call this without re-entering privacy mode.
     */
    private fun applyAmbientContent() {
        if (!privacyModeActive) return

        // New apply clears any prior fallback banner; async failures re-populate it.
        clearAmbientFallbackStatus()

        val inNight =
            if (prefs.nightWindowEnabled) {
                val cal = Calendar.getInstance()
                val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                NightWindow.isInside(
                    nowMinutes,
                    NightWindow.parseHhMm(prefs.nightStart),
                    NightWindow.parseHhMm(prefs.nightEnd),
                )
            } else {
                false
            }

        val selected = prefs.ambientMode
        val effective = if (inNight) AmbientMode.BLACK else selected

        val releaseKeepScreenOn =
            when {
                inNight -> true
                effective == AmbientMode.BLACK && prefs.allowScreenOffOnHostSleep -> true
                else -> false
            }
        if (releaseKeepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val syncedBatch =
            if (::ambientMediaStore.isInitialized) {
                ambientMediaStore.loadBatch()
            } else {
                null
            }

        ambientController.apply(
            mode = effective,
            photoTreeUri = prefs.photoTreeUri,
            videoUri = prefs.videoUri,
            syncedPhotoUris = syncedBatch?.photoUris ?: emptyList(),
            syncedVideoUri = syncedBatch?.videoUri,
        )

        if (prefs.nightWindowEnabled) {
            scheduleNightBoundary()
        } else {
            cancelNightBoundary()
        }

        mainDiag(
            "Ambient applied: selected=$selected effective=$effective " +
                "inNight=$inNight releaseKeepOn=$releaseKeepScreenOn",
        )
    }

    /** Clear in-memory ambient fallback text and any open-dialog status view. */
    private fun clearAmbientFallbackStatus() {
        ambientFallbackStatus = null
        settingsAmbientStatusView?.let { tv ->
            tv.text = ""
            tv.visibility = View.GONE
        }
    }

    /**
     * User-visible wording for ambient PHOTOS/VIDEO failures.
     * Always tells the user to select the source again; diagnostic detail stays in logs.
     */
    private fun userFacingAmbientFallback(reason: String): String {
        val lower = reason.lowercase()
        return when {
            lower.contains("photo") ->
                "Could not use the photo folder ($reason). Select the photo folder again."
            lower.contains("video") ->
                "Could not play the video ($reason). Select a video again."
            else ->
                "Ambient media failed ($reason). Select the source again."
        }
    }

    private fun scheduleNightBoundary() {
        cancelNightBoundary()
        if (!privacyModeActive || !prefs.nightWindowEnabled) return

        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val delay =
            NightWindow
                .millisUntilNextBoundary(
                    nowMinutes = nowMinutes,
                    nowSecondsInMinute = cal.get(Calendar.SECOND),
                    nowMillisInSecond = cal.get(Calendar.MILLISECOND),
                    startMinutes = NightWindow.parseHhMm(prefs.nightStart),
                    endMinutes = NightWindow.parseHhMm(prefs.nightEnd),
                ).coerceAtLeast(1_000L)

        nightBoundaryRunnable =
            Runnable {
                if (privacyModeActive) {
                    applyAmbientContent()
                }
            }
        nightBoundaryHandler.postDelayed(nightBoundaryRunnable!!, delay)
        mainDiag("Night boundary scheduled in ${delay}ms")
    }

    private fun cancelNightBoundary() {
        nightBoundaryRunnable?.let { nightBoundaryHandler.removeCallbacks(it) }
        nightBoundaryRunnable = null
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Code pairing (issue #35): manual host/port/code entry for tablets without
     * a camera. On success the Mac issues the real token and we connect through
     * the same path as a QR scan.
     */
    private fun showCodePairingDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_code_pairing, null)
        val hostField = view.findViewById<android.widget.EditText>(R.id.codePairHost)
        val portField = view.findViewById<android.widget.EditText>(R.id.codePairPort)
        val codeField = view.findViewById<android.widget.EditText>(R.id.codePairCode)
        val errorText = view.findViewById<android.widget.TextView>(R.id.codePairError)

        // Re-pair convenience: prefill the last paired Mac so only the fresh
        // code needs typing.
        pairedHostStorage.load()?.let { cached ->
            hostField.setText(cached.host)
            portField.setText(cached.port.toString())
        }

        val dialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Pair with code")
                .setView(view)
                .setPositiveButton("Pair", null) // listener installed below so it doesn't auto-dismiss
                .setNegativeButton("Cancel", null)
                .create()

        dialog.setOnShowListener {
            val pairButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            pairButton.setOnClickListener {
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull()
                val code = codeField.text.toString().filter { it.isDigit() }

                errorText.visibility = View.GONE
                when {
                    host.isEmpty() -> {
                        errorText.text = "Enter the Mac's address (shown as \"Listening\" under the QR)."
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    port == null || port !in 1..65535 -> {
                        errorText.text = "Port must be between 1 and 65535."
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    code.length != AuthHandshake.PAIR_CODE_LEN -> {
                        errorText.text = "The code is ${AuthHandshake.PAIR_CODE_LEN} digits."
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                }
                pairButton.isEnabled = false
                pairButton.text = "Pairing…"

                fun showPairError(message: String) {
                    runOnUiThread {
                        pairButton.isEnabled = true
                        pairButton.text = "Pair"
                        errorText.text = message
                        errorText.visibility = View.VISIBLE
                    }
                }

                val deviceName = WirelessTabController.defaultDeviceName()
                pairingJob =
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val client = StreamClient(host, port!!, applicationContext)
                            val result = client.pairWithCode(code, deviceName)
                            // Dialog dismissed mid-flight (Cancel/back): drop the
                            // token instead of silently connecting.
                            ensureActive()
                            runOnUiThread {
                                dialog.dismiss()
                                wirelessController.onCodePairComplete(host, port, result.token, result.macName)
                            }
                        } catch (e: StreamClient.PairingError) {
                            showPairError(
                                when (e) {
                                    is StreamClient.PairingError.CodeRejected ->
                                        "Code not accepted. Codes change after each use — " +
                                            "check the Mac for the current one."
                                    is StreamClient.PairingError.HostTooOld ->
                                        "Update Side Screen on the Mac to use code pairing."
                                },
                            )
                        } catch (e: StreamClient.WirelessConnectError) {
                            showPairError(
                                when (e) {
                                    is StreamClient.WirelessConnectError.NetworkUnreachable ->
                                        "No response from $host:$port. Check both devices are " +
                                            "on the same WiFi and the Mac app is running."
                                    else -> "Connection error — try again."
                                },
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("Code pairing failed unexpectedly: ${e.message}")
                            showPairError("Pairing failed — try again.")
                        }
                    }
            }
        }
        dialog.setOnDismissListener {
            pairingJob?.cancel()
            pairingJob = null
        }
        dialog.show()
    }

    private fun connectWireless(
        host: String,
        port: Int,
        token: ByteArray,
        deviceName: String,
        macName: String,
    ) {
        stopDemo() // a real connection (incl. wireless auto-reconnect) takes over the decoder
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Connecting wirelessly to $host:$port...")
                streamClient = StreamClient(host, port, applicationContext)
                setupStreamClientCallbacks()
                streamClient?.connectWireless(token, deviceName)
                // NOTE: onConnectSuccess is fired from the onConnectionStatus(true)
                // listener (above) right after handshake OK — not here. This line
                // would otherwise run AFTER the receive loop exits, i.e. AFTER
                // disconnect, incorrectly transitioning back to CONNECTED.
            } catch (e: StreamClient.WirelessConnectError) {
                runOnUiThread {
                    wirelessController.onConnectError(e)
                }
            } catch (e: Exception) {
                log("Wireless connect failed: ${e.message}")
                runOnUiThread {
                    wirelessController.onConnectError(StreamClient.WirelessConnectError.NetworkUnreachable)
                }
            }
        }
    }

    private fun connect(
        host: String,
        port: Int,
    ) {
        stopDemo()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Connecting to $host:$port...")
                // Shared callback path with wireless — includes onHostDisplayPower.
                streamClient = StreamClient(host, port, applicationContext)
                setupStreamClientCallbacks()
                streamClient?.connect()
            } catch (e: Exception) {
                val errorMessage =
                    when {
                        e.message?.contains("ECONNREFUSED") == true -> {
                            "Mac server is not running.\n\nPlease start Side Screen.app on your Mac first."
                        }

                        e.message?.contains("Network is unreachable") == true -> {
                            "Cannot reach Mac.\n\n" +
                                "Make sure both devices are connected via USB cable and ADB reverse is configured."
                        }

                        e.message?.contains("timeout") == true -> {
                            "Connection timeout.\n\nCheck if Mac firewall is blocking port $port."
                        }

                        else -> {
                            "Connection failed: ${e.message}\n\n" +
                                "Try:\n• Start Side Screen.app on Mac\n" +
                                "• Check USB connection\n• Run: adb reverse tcp:$port tcp:$port"
                        }
                    }
                updateStatus("Connection failed")
                showError(errorMessage)
            }
        }
    }

    private fun disconnect() {
        stopPingTimer()
        streamClient?.disconnect()
        // Reset display config so next connect defers decoder init until config arrives
        displayWidth = 0
        displayHeight = 0
        desktopWidth = 0
        desktopHeight = 0
        displayFlipHorizontal = false
        displayFlipVertical = false
        runOnUiThread {
            binding.textureView.visibility = View.GONE
            applyTextureTransform()
        }
        log("Disconnected")
    }

    private fun startPingTimer() {
        stopPingTimer()
        pingJob =
            lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(1000) // Ping every 1 second
                    streamClient?.sendPing()
                }
            }
    }

    private fun stopPingTimer() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun cleanup() {
        try {
            cancelNightBoundary()
            ambientSyncServer?.stop()
            ambientSyncServer = null
            if (::ambientController.isInitialized) {
                ambientController.stopAll()
            }
            stopDemo()
            disconnect()
            videoDecoder?.release()
            videoDecoder = null
            currentTextureSurface?.release()
            currentTextureSurface = null

            // Release wake lock safely
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (e: Exception) {
                // Ignore wake lock release errors
            }
            wakeLock = null
            log("🎮 Performance mode DISABLED")
        } catch (e: Exception) {
            log("⚠️ Cleanup error: ${e.message}")
        }
    }

    private fun handleTouch(
        view: View,
        event: MotionEvent,
    ) {
        val rawX = event.x / view.width.toFloat()
        val rawY = event.y / view.height.toFloat()
        val x = if (displayFlipHorizontal) 1f - rawX else rawX
        val y = if (displayFlipVertical) 1f - rawY else rawY
        val pointerCount = event.pointerCount.coerceAtMost(2)

        var x2 = 0f
        var y2 = 0f
        if (pointerCount >= 2) {
            val rawX2 = event.getX(1) / view.width.toFloat()
            val rawY2 = event.getY(1) / view.height.toFloat()
            x2 = if (displayFlipHorizontal) 1f - rawX2 else rawX2
            y2 = if (displayFlipVertical) 1f - rawY2 else rawY2
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inputPredictor.reset()
                inputPredictor.addSample(x, y)
                streamClient?.sendTouch(x, y, 0, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                streamClient?.sendTouch(x, y, 0, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    inputPredictor.addSample(x, y)
                    val (px, py) = inputPredictor.predictPosition(12f)
                    streamClient?.sendTouch(px, py, 1, 1)
                } else {
                    streamClient?.sendTouch(x, y, 1, pointerCount, x2, y2)
                }
            }

            MotionEvent.ACTION_UP -> {
                inputPredictor.reset()
                streamClient?.sendTouch(x, y, 2, 1)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                streamClient?.sendTouch(x, y, 2, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_CANCEL -> {
                inputPredictor.reset()
                streamClient?.sendTouch(x, y, 2, 1)
            }
        }
    }

    private fun applyRotation(
        rotation: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
    ) {
        requestedOrientation =
            when (rotation) {
                90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

        binding.surfaceView.rotation = 0f
        binding.surfaceView.visibility = View.VISIBLE
        binding.textureView.visibility = if (flipHorizontal || flipVertical) View.VISIBLE else View.GONE
        applyTextureTransform()

        log(
            "🔄 Orientation: ${when (rotation) {
                90 -> "Portrait"
                180 -> "Landscape (flipped)"
                270 -> "Portrait (flipped)"
                else -> "Landscape"
            }}${if (flipHorizontal || flipVertical) " mirrored" else ""}",
        )
    }

    private fun applyTextureTransform() {
        val view = binding.textureView
        val matrix = Matrix()
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        matrix.postScale(
            if (displayFlipHorizontal) -1f else 1f,
            if (displayFlipVertical) -1f else 1f,
            centerX,
            centerY,
        )
        view.setTransform(matrix)
    }

    /**
     * Reset orientation to follow device sensor (when disconnected)
     */
    private fun resetOrientationToSensor() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun log(message: String) {
        runOnUiThread {
            val current = binding.logText.text.toString()
            val lines = current.split("\n").takeLast(5)
            binding.logText.text = (lines + message).joinToString("\n")
        }
    }

    override fun onResume() {
        super.onResume()
        // Only nudge about updates while idle — never over a live stream or the demo.
        if (!isStreamingUi) appUpdater.checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopChecklistUpdates()
        cleanup()
    }

    // ==================== Connection Checklist ====================

    private fun startChecklistUpdates() {
        // Stop any existing runnable first to prevent duplicates
        checklistRunnable?.let {
            checklistHandler.removeCallbacks(it)
        }

        checklistRunnable =
            object : Runnable {
                override fun run() {
                    updateChecklist()
                    checklistHandler.postDelayed(this, 2000) // Update every 2 seconds
                }
            }
        checklistHandler.post(checklistRunnable!!)
    }

    private fun stopChecklistUpdates() {
        checklistRunnable?.let {
            checklistHandler.removeCallbacks(it)
            checklistRunnable = null
        }
    }

    private fun updateChecklist() {
        // Skip if connected (to prevent socket conflicts)
        if (isConnected) return

        // Check Developer Mode (if we can run this app with USB debugging, dev mode is enabled)
        val isDeveloperModeEnabled =
            Settings.Secure.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1
        updateChecklistItem(binding.checkDeveloperMode, isDeveloperModeEnabled)

        // Check USB Debugging (ADB enabled)
        val isAdbEnabled =
            Settings.Secure.getInt(
                contentResolver,
                Settings.Global.ADB_ENABLED,
                0,
            ) == 1
        updateChecklistItem(binding.checkUsbDebugging, isAdbEnabled)

        // Check USB connected (check if any USB device is connected)
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val isUsbConnected = usbManager.deviceList.isNotEmpty() || isCharging()
        updateChecklistItem(binding.checkUsbConnected, isUsbConnected)

        // Check Mac Server (try to connect to port)
        lifecycleScope.launch(Dispatchers.IO) {
            // Double-check connection state before socket test
            if (isConnected) return@launch

            val port =
                binding.portInput.text
                    .toString()
                    .toIntOrNull() ?: 54321
            val isServerRunning = checkServerRunning("127.0.0.1", port)
            runOnUiThread {
                // Final check before updating UI
                if (isConnected) return@runOnUiThread

                updateChecklistItem(binding.checkMacServer, isServerRunning)

                // Update main status indicator based on all checklist items
                val allReady = isDeveloperModeEnabled && isAdbEnabled && isUsbConnected && isServerRunning
                updateMainStatus(allReady)
            }
        }
    }

    private fun updateMainStatus(allReady: Boolean) {
        binding.statusIndicator.setBackgroundResource(
            if (allReady) {
                R.drawable.status_indicator_green
            } else {
                R.drawable.status_indicator_red
            },
        )
        binding.statusText.text = if (allReady) "Ready to connect" else "Not ready to connect"
    }

    private fun updateChecklistItem(
        indicator: View,
        isOk: Boolean,
    ) {
        indicator.setBackgroundResource(
            if (isOk) {
                R.drawable.status_indicator_green
            } else {
                R.drawable.status_indicator_red
            },
        )
    }

    private fun isCharging(): Boolean {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, intentFilter)
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Check if Mac server is actually running (not just ADB reverse)
     *
     * Problem: When `adb reverse tcp:8888 tcp:8888` is active, ADB daemon listens on port 8888.
     * A simple socket connect will succeed to ADB daemon, not the actual Mac server.
     *
     * Solution: After connecting, try to read data with a short timeout.
     * Mac server sends display config (type=1) immediately upon connection.
     * ADB daemon doesn't send anything, so read will timeout → false.
     */
    private fun checkServerRunning(
        host: String,
        port: Int,
    ): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 300) // 300ms connect timeout
            socket.soTimeout = 200 // 200ms read timeout

            // Try to read - Mac server sends display config immediately
            // ADB daemon doesn't send anything, so read will timeout
            val input = socket.getInputStream()
            val firstByte = input.read() // Blocks up to soTimeout

            // If we got data (>= 0), it's the real Mac server
            // -1 means EOF (connection closed), anything else is data
            firstByte >= 0
        } catch (e: Exception) {
            // Timeout, connection refused, or other error = server not running
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
