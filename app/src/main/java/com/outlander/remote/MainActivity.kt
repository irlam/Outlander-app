package com.outlander.remote

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.outlander.remote.databinding.ActivityMainBinding
import com.outlander.remote.databinding.DialogSettingsBinding
import com.outlander.remote.databinding.SheetLogsBinding
import phev.CarClient
import phev.CarStatus
import phev.apply
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object {
        private const val NETWORK_REQUEST_TIMEOUT_MS = 25000
        private const val SOCKET_CONNECT_TIMEOUT_MS = 2500
        private const val RECONNECT_DELAY_START_MS = 900L
        private const val RECONNECT_DELAY_MAX_MS = 2600L
        private const val BATTERY_ANIM_MS = 420L
    }

    private lateinit var binding: ActivityMainBinding
    private val status = CarStatus()
    private val log = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var client: CarClient? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var carNetwork: Network? = null
    @Volatile private var generation = 0

    private var activeLogSheetBinding: SheetLogsBinding? = null
    private var updatingSwitchesFromTelemetry = false
    private var statusDotPulseAnimator: AnimatorSet? = null
    private var introPlayed = false
    private var lastRenderedBatteryPct: Int? = null
    private val easeOut = FastOutSlowInInterpolator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadPreferences()
        render()
        binding.root.post { setupEntranceAnimations() }
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnRefresh.setOnClickListener {
            animateTap(binding.btnRefresh)
            if (client?.running == true) {
                client?.refresh()
                Toast.makeText(this, "Requesting status refresh…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Not connected to vehicle", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLogs.setOnClickListener {
            animateTap(binding.btnLogs)
            showLogsBottomSheet()
        }

        binding.btnSettings.setOnClickListener {
            animateTap(binding.btnSettings)
            showSettingsDialog()
        }

        binding.btnConnect.setOnClickListener {
            animateTap(binding.btnConnect)
            if (client?.running == true || callback != null) {
                stopCar()
            } else {
                startCar()
            }
        }

        binding.btnStartClimate.setOnClickListener {
            animateTap(binding.btnStartClimate)
            if (client?.running != true) {
                Toast.makeText(this, "Connect to car first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mode = when (binding.groupClimateMode.checkedChipId) {
                R.id.chipModeCool -> 1
                R.id.chipModeDefrost -> 3
                else -> 2 // Heat default
            }
            val duration = when (binding.groupClimateDuration.checkedChipId) {
                R.id.chipDur20 -> 1
                R.id.chipDur30 -> 2
                else -> 0 // 10 min default
            }
            client?.startAirCon(mode = mode, duration = duration)
            val modeName = if (mode == 1) "Cool" else if (mode == 2) "Heat" else "Defrost"
            val durName = if (duration == 1) "20m" else if (duration == 2) "30m" else "10m"
            Toast.makeText(this, "Climate requested: $modeName ($durName)", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopClimate.setOnClickListener {
            animateTap(binding.btnStopClimate)
            if (client?.running != true) {
                Toast.makeText(this, "Connect to car first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            client?.stopAirCon()
            Toast.makeText(this, "Stopping Climate…", Toast.LENGTH_SHORT).show()
        }

        binding.switchHeadlights.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingSwitchesFromTelemetry) {
                if (client?.running == true) {
                    client?.setHeadlights(isChecked)
                } else {
                    Toast.makeText(this, "Connect to car first", Toast.LENGTH_SHORT).show()
                    binding.switchHeadlights.isChecked = !isChecked
                }
            }
        }

        binding.switchParkingLights.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingSwitchesFromTelemetry) {
                if (client?.running == true) {
                    client?.setParkingLights(isChecked)
                } else {
                    Toast.makeText(this, "Connect to car first", Toast.LENGTH_SHORT).show()
                    binding.switchParkingLights.isChecked = !isChecked
                }
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("car", MODE_PRIVATE)
        val ssid = prefs.getString("ssid", "REMOTE75rszr") ?: "REMOTE75rszr"
        binding.tvCarSubtitle.text = "Mitsubishi PHEV • $ssid"
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        }
    }

    private fun startCar() {
        stopCar()
        val prefs = getSharedPreferences("car", MODE_PRIVATE)
        val ssid = prefs.getString("ssid", "REMOTE75rszr")?.trim() ?: "REMOTE75rszr"
        val pass = prefs.getString("pass", "cCGbCSdFrVGnex")?.trim() ?: "cCGbCSdFrVGnex"

        updateConnectionUi(
            text = "Connecting to $ssid…",
            dotColorRes = R.color.ev_amber,
            buttonText = "Cancel",
            isConnecting = true,
        )

        addLog("START requesting network $ssid")
        findReusableCarNetwork(ssid)?.let { reusable ->
            addLog("FAST PATH reusing active Wi-Fi network")
            carNetwork = reusable
            connect(reusable)
            return
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                addLog("WIFI car network available: $network")
                carNetwork = network
                connect(network)
            }

            override fun onUnavailable() {
                addLog("WIFI network not found/refused (out of range, wrong password, or ECU occupied)")
                callback = null
                carNetwork = null
                runOnUiThread {
                    updateConnectionUi(
                        text = "Could not join $ssid",
                        dotColorRes = R.color.ev_rose,
                        buttonText = "Connect",
                        isConnecting = false,
                    )
                }
            }

            override fun onLost(network: Network) {
                addLog("WIFI lost $network")
                generation++
                carNetwork = null
                client?.running = false
                client = null
                runOnUiThread {
                    updateConnectionUi(
                        text = "Wi-Fi lost, rejoining…",
                        dotColorRes = R.color.ev_amber,
                        buttonText = "Cancel",
                        isConnecting = true,
                    )
                }
            }
        }
        callback = cb
        cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
    }

    private fun findReusableCarNetwork(targetSsid: String): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return null
        val activeSsid = wifiInfo.ssid?.trim('"') ?: return null
        if (activeSsid.equals("<unknown ssid>", ignoreCase = true)) return null

        return if (activeSsid == targetSsid) activeNetwork else null
    }

    private fun stopCar() {
        generation++
        client?.running = false
        callback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        callback = null
        carNetwork = null
        client = null

        updateConnectionUi(
            text = "Disconnected",
            dotColorRes = R.color.text_tertiary,
            buttonText = "Connect",
            isConnecting = false,
        )
    }

    private fun connect(network: Network) {
        val gen = ++generation
        Thread {
            var retryDelayMs = RECONNECT_DELAY_START_MS
            while (gen == generation) {
                val c = CarClient(
                    onRegister = { reg, data ->
                        status.apply(reg, data)
                        runOnUiThread { render() }
                    },
                    onLog = ::addLog,
                )
                client = c
                try {
                    addLog("TCP connecting 192.168.8.46:8080")
                    runOnUiThread {
                        updateConnectionUi(
                            text = "Connecting TCP socket…",
                            dotColorRes = R.color.ev_amber,
                            buttonText = "Cancel",
                            isConnecting = true,
                        )
                    }
                    val sock = network.socketFactory.createSocket()
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.connect(java.net.InetSocketAddress("192.168.8.46", 8080), SOCKET_CONNECT_TIMEOUT_MS)
                    addLog("TCP connected")
                    runOnUiThread {
                        updateConnectionUi(
                            text = "Connected to vehicle",
                            dotColorRes = R.color.ev_emerald,
                            buttonText = "Disconnect",
                            isConnecting = false,
                        )
                        celebrateConnection()
                    }
                    retryDelayMs = RECONNECT_DELAY_START_MS
                    c.run(sock)
                    addLog("TCP closed by car")
                } catch (e: Exception) {
                    addLog("ERROR ${e.javaClass.simpleName}: ${e.message}")
                }
                if (gen != generation) break
                runOnUiThread {
                    updateConnectionUi(
                        text = "Reconnecting in ${"%.1f".format(Locale.US, retryDelayMs / 1000f)}s…",
                        dotColorRes = R.color.ev_amber,
                        buttonText = "Cancel",
                        isConnecting = true,
                    )
                }
                try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { break }
                retryDelayMs = min(retryDelayMs + 450L, RECONNECT_DELAY_MAX_MS)
            }
        }.start()
    }

    private fun setupEntranceAnimations() {
        if (introPlayed) return
        introPlayed = true

        val sections = listOf<View>(
            binding.headerBar,
            binding.cardConnection,
            binding.cardBattery,
            binding.cardSecurity,
            binding.cardClimate,
            binding.cardLights,
            binding.cardTelemetry,
        )
        val offset = 18f * resources.displayMetrics.density
        sections.forEach { section ->
            section.alpha = 0f
            section.translationY = offset
            section.scaleX = 0.985f
            section.scaleY = 0.985f
        }
        sections.forEachIndexed { index, section ->
            section.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 55L))
                .setDuration(420L)
                .setInterpolator(easeOut)
                .start()
        }
    }

    private fun animateTap(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(70L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(170L)
                    .setInterpolator(easeOut)
                    .start()
            }
            .start()
    }

    private fun updateConnectionUi(
        text: String,
        dotColorRes: Int,
        buttonText: String,
        isConnecting: Boolean,
    ) {
        binding.tvConnectionStatus.animate().cancel()
        binding.tvConnectionStatus.text = text
        binding.tvConnectionStatus.alpha = 0.72f
        binding.tvConnectionStatus.animate().alpha(1f).setDuration(150L).start()

        binding.viewStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))
        binding.btnConnect.text = buttonText

        if (isConnecting) {
            startStatusDotPulse()
        } else {
            stopStatusDotPulse()
        }
    }

    private fun startStatusDotPulse() {
        if (statusDotPulseAnimator?.isRunning == true) return
        val target = binding.viewStatusDot

        val scaleX = ObjectAnimator.ofFloat(target, View.SCALE_X, 1f, 1.28f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = easeOut
        }
        val scaleY = ObjectAnimator.ofFloat(target, View.SCALE_Y, 1f, 1.28f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = easeOut
        }
        val alpha = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.58f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = easeOut
        }

        statusDotPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun stopStatusDotPulse() {
        statusDotPulseAnimator?.cancel()
        statusDotPulseAnimator = null
        binding.viewStatusDot.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
    }

    private fun celebrateConnection() {
        binding.cardConnection.animate().cancel()
        binding.cardConnection.animate()
            .scaleX(1.015f)
            .scaleY(1.015f)
            .setDuration(150L)
            .withEndAction {
                binding.cardConnection.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190L)
                    .setInterpolator(easeOut)
                    .start()
            }
            .start()
    }

    private fun renderBatteryPct(newPct: Int) {
        val clampedPct = newPct.coerceIn(0, 100)
        val from = lastRenderedBatteryPct ?: clampedPct
        if (from == clampedPct) {
            binding.tvBatteryPct.text = "$clampedPct%"
            binding.progressBattery.progress = clampedPct
            lastRenderedBatteryPct = clampedPct
            return
        }

        ValueAnimator.ofInt(from, clampedPct).apply {
            duration = BATTERY_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                binding.tvBatteryPct.text = "$value%"
                binding.progressBattery.progress = value
            }
            start()
        }

        binding.tvBatteryPct.animate().cancel()
        binding.tvBatteryPct.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(130L)
            .withEndAction {
                binding.tvBatteryPct.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(easeOut)
                    .start()
            }
            .start()

        lastRenderedBatteryPct = clampedPct
    }

    private fun render() {
        // Battery & Charging
        val pct = status.batteryPct
        if (pct != null) {
            renderBatteryPct(pct)

            val progressColor = when {
                pct <= 15 -> ContextCompat.getColor(this, R.color.ev_rose)
                pct <= 25 -> ContextCompat.getColor(this, R.color.ev_amber)
                else -> ContextCompat.getColor(this, R.color.ev_emerald)
            }
            binding.progressBattery.setIndicatorColor(progressColor)
        } else {
            lastRenderedBatteryPct = null
            binding.tvBatteryPct.text = "--%"
            binding.progressBattery.progress = 0
        }

        // Charging Badge
        if (status.charging == true) {
            binding.chipChargingBadge.text = "Charging"
            binding.chipChargingBadge.setChipIconResource(R.drawable.ic_flash)
            binding.chipChargingBadge.setChipIconTintResource(R.color.ev_emerald)
            binding.chipChargingBadge.setTextColor(ContextCompat.getColor(this, R.color.ev_emerald))
            binding.tvChargeRemaining.text = status.chargeRemainingMin?.let { "${it}m left" } ?: ""
        } else if (status.charging == false) {
            binding.chipChargingBadge.text = "Not Charging"
            binding.chipChargingBadge.setChipIconResource(R.drawable.ic_flash)
            binding.chipChargingBadge.setChipIconTintResource(R.color.text_secondary)
            binding.chipChargingBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvChargeRemaining.text = ""
        } else {
            binding.chipChargingBadge.text = "Status: --"
            binding.tvChargeRemaining.text = ""
        }

        // Plug Status & Raw Hex
        val plugText = when (status.plugged) {
            true -> status.plugType ?: "Plugged in"
            false -> "Unplugged"
            null -> "Plug: Unknown"
        }
        binding.tvPlugStatus.text = plugText
        val hex1E = status.rawPlugHex ?: "-- --"
        val hex1F = status.rawChargeHex ?: "-- -- --"
        binding.tvDiagnosticsHex.text = "[1E: $hex1E | 1F: $hex1F]"

        // Security & Lock Status
        when (status.locked) {
            true -> {
                binding.chipLockStatus.text = "LOCKED"
                binding.chipLockStatus.setChipIconResource(R.drawable.ic_lock)
                binding.chipLockStatus.setChipIconTintResource(R.color.ev_emerald)
                binding.chipLockStatus.setTextColor(ContextCompat.getColor(this, R.color.ev_emerald))
            }
            false -> {
                binding.chipLockStatus.text = "UNLOCKED"
                binding.chipLockStatus.setChipIconResource(R.drawable.ic_lock_open)
                binding.chipLockStatus.setChipIconTintResource(R.color.ev_amber)
                binding.chipLockStatus.setTextColor(ContextCompat.getColor(this, R.color.ev_amber))
            }
            null -> {
                binding.chipLockStatus.text = "Doors: --"
                binding.chipLockStatus.setChipIconTintResource(R.color.text_secondary)
                binding.chipLockStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        // Individual Doors
        renderDoor(binding.chipDoorBonnet, "Bonnet (Hood)", status.bonnetOpen)
        renderDoor(binding.chipDoorDriver, "Driver", status.doorDriverOpen)
        renderDoor(binding.chipDoorPassenger, "Passenger", status.doorPassengerOpen)
        renderDoor(binding.chipDoorRearLeft, "Rear Left", status.doorRearLeftOpen)
        renderDoor(binding.chipDoorRearRight, "Rear Right", status.doorRearRightOpen)
        renderDoor(binding.chipDoorBoot, "Boot (Trunk)", status.bootOpen)

        // Climate Control
        if (status.acOperating == true) {
            val modeStr = status.acMode ?: "AirCon"
            binding.chipClimateStatus.text = "Running • $modeStr"
            binding.chipClimateStatus.setTextColor(ContextCompat.getColor(this, R.color.ev_cyan))
        } else if (status.acOperating == false) {
            binding.chipClimateStatus.text = "Stopped"
            binding.chipClimateStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.chipClimateStatus.text = "Climate: --"
            binding.chipClimateStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Lights
        updatingSwitchesFromTelemetry = true
        status.headlights?.let {
            binding.switchHeadlights.isChecked = it
            binding.tvHeadlightsStatus.text = if (it) "Active" else "Off"
        }
        status.parkingLights?.let {
            binding.switchParkingLights.isChecked = it
            binding.tvParkingLightsStatus.text = if (it) "Active" else "Off"
        }
        updatingSwitchesFromTelemetry = false

        // Vehicle Telemetry
        binding.tvVin.text = status.vin ?: "--"
        binding.tvCarClock.text = status.carTime ?: "--"
        binding.tvEcuVersion.text = status.ecuVersion ?: "--"
    }

    private fun renderDoor(view: TextView, label: String, isOpen: Boolean?) {
        when (isOpen) {
            true -> {
                view.text = "$label: OPEN"
                view.setTextColor(ContextCompat.getColor(this, R.color.ev_rose))
                view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ev_rose_bg))
            }
            false -> {
                view.text = "$label: Closed"
                view.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_screen))
            }
            null -> {
                view.text = "$label: --"
                view.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_screen))
            }
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("car", MODE_PRIVATE)
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)

        dialogBinding.editSsid.setText(prefs.getString("ssid", "REMOTE75rszr"))
        dialogBinding.editPass.setText(prefs.getString("pass", "cCGbCSdFrVGnex"))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.setOnShowListener {
            dialogBinding.root.alpha = 0f
            dialogBinding.root.translationY = 16f * resources.displayMetrics.density
            dialogBinding.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(easeOut)
                .start()
        }

        dialogBinding.btnCancelSettings.setOnClickListener {
            animateTap(dialogBinding.btnCancelSettings)
            dialog.dismiss()
        }

        dialogBinding.btnSaveSettings.setOnClickListener {
            animateTap(dialogBinding.btnSaveSettings)
            val ssid = dialogBinding.editSsid.text.toString().trim()
            val pass = dialogBinding.editPass.text.toString().trim()
            if (ssid.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "SSID and Password cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString("ssid", ssid).putString("pass", pass).apply()
            binding.tvCarSubtitle.text = "Mitsubishi PHEV • $ssid"
            dialog.dismiss()

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            startCar()
        }

        dialog.show()
    }

    private fun showLogsBottomSheet() {
        val sheetBinding = SheetLogsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)
        activeLogSheetBinding = sheetBinding

        dialog.setOnShowListener {
            sheetBinding.root.alpha = 0f
            sheetBinding.root.translationY = 18f * resources.displayMetrics.density
            sheetBinding.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(easeOut)
                .start()
        }

        sheetBinding.tvLogContent.text = logText()
        sheetBinding.logScrollView.post { sheetBinding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN) }

        sheetBinding.btnCopyLogs.setOnClickListener {
            animateTap(sheetBinding.btnCopyLogs)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("phev log", logText()))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnShareLogs.setOnClickListener {
            animateTap(sheetBinding.btnShareLogs)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Outlander PHEV Log")
                putExtra(Intent.EXTRA_TEXT, logText())
            }
            startActivity(Intent.createChooser(i, "Share log"))
        }

        sheetBinding.btnClearLogs.setOnClickListener {
            animateTap(sheetBinding.btnClearLogs)
            synchronized(log) { log.setLength(0) }
            sheetBinding.tvLogContent.text = ""
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnCloseLogs.setOnClickListener {
            animateTap(sheetBinding.btnCloseLogs)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            activeLogSheetBinding = null
        }

        dialog.show()
    }

    private fun addLog(line: String) {
        val s = "${fmt.format(Date())} $line\n"
        synchronized(log) {
            log.append(s)
            if (log.length > 500000) {
                log.delete(0, 100000)
            }
        }
        try {
            File(getExternalFilesDir(null), "phev_log.txt").appendText(s)
        } catch (_: Exception) {}

        runOnUiThread {
            activeLogSheetBinding?.let { sheet ->
                sheet.tvLogContent.append(s)
                sheet.logScrollView.post { sheet.logScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    private fun logText(): String = synchronized(log) { log.toString() }

    override fun onDestroy() {
        super.onDestroy()
        stopCar()
    }
}
