package com.example.ARMeasure

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.ARMeasure.ar.ARMeasureActivity
import com.google.android.material.button.MaterialButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    private lateinit var startMeasuringButton: MaterialButton
    private lateinit var statusText: TextView

    private var installRequested = false
    private var pendingStart = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                checkArCoreAndStart()
            } else {
                pendingStart = false
                setReadyForRetry(R.string.home_status_camera_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        startMeasuringButton = findViewById(R.id.startMeasuringButton)
        statusText = findViewById(R.id.homeStatusText)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startMeasuringButton.setOnClickListener {
            pendingStart = true
            startMeasuringButton.isEnabled = false
            ensureCameraPermissionThenStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && hasCameraPermission()) {
            checkArCoreAndStart()
        }
    }

    private fun ensureCameraPermissionThenStart() {
        if (hasCameraPermission()) {
            checkArCoreAndStart()
        } else {
            statusText.setText(R.string.home_status_requesting_camera)
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun checkArCoreAndStart() {
        statusText.setText(R.string.home_status_checking_arcore)

        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            mainHandler.postDelayed({
                if (pendingStart) {
                    checkArCoreAndStart()
                }
            }, ARCORE_CHECK_RETRY_MS)
            return
        }

        if (!availability.isSupported) {
            pendingStart = false
            setReadyForRetry(R.string.home_status_arcore_unsupported)
            return
        }

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    statusText.setText(R.string.home_status_installing_arcore)
                }
                ArCoreApk.InstallStatus.INSTALLED -> launchMeasureActivity()
            }
        } catch (_: UnavailableUserDeclinedInstallationException) {
            pendingStart = false
            setReadyForRetry(R.string.home_status_arcore_declined)
        } catch (_: UnavailableDeviceNotCompatibleException) {
            pendingStart = false
            setReadyForRetry(R.string.home_status_arcore_unsupported)
        } catch (_: Exception) {
            pendingStart = false
            setReadyForRetry(R.string.home_status_arcore_error)
        }
    }

    private fun launchMeasureActivity() {
        pendingStart = false
        startMeasuringButton.isEnabled = true
        startActivity(Intent(this, ARMeasureActivity::class.java))
    }

    private fun setReadyForRetry(messageResId: Int) {
        statusText.setText(messageResId)
        startMeasuringButton.isEnabled = true
    }

    companion object {
        private const val ARCORE_CHECK_RETRY_MS = 200L
    }
}
