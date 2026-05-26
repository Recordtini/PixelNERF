package com.siftedsand.pixelnerf

import android.Manifest
import android.content.pm.PackageManager
import android.media.Image
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var session: Session? = null
    private var captureJob: Job? = null
    private var writer: DatasetWriter? = null
    private var frameIndex = 0

    private lateinit var statusText: TextView
    private lateinit var captureButton: Button

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestCameraPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            ensureSession()
            try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                statusText.text = "Camera not available: ${e.message}"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopCapture()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        session?.close()
        session = null
    }

    private fun buildUi() {
        statusText = TextView(this).apply {
            text = "PixelNERF ready"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        captureButton = Button(this).apply {
            text = "Start capture"
            setOnClickListener {
                if (captureJob == null) startCapture() else stopCapture()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(statusText)
            addView(captureButton)
        }
        setContentView(root)
    }

    private fun requestCameraPermissionIfNeeded() {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            ensureSession()
        } else {
            statusText.text = "Camera permission is required."
        }
    }

    private fun ensureSession() {
        if (session != null) return
        try {
            val arSession = Session(this)
            val config = Config(arSession).apply {
                focusMode = Config.FocusMode.AUTO
                depthMode = if (arSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    Config.DepthMode.RAW_DEPTH_ONLY
                } else {
                    Config.DepthMode.DISABLED
                }
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            arSession.configure(config)
            session = arSession
            statusText.text = "ARCore session ready. Raw depth: ${config.depthMode}"
        } catch (e: UnavailableException) {
            statusText.text = "ARCore unavailable: ${e.message}"
        } catch (e: Exception) {
            statusText.text = "Failed to create ARCore session: ${e.message}"
        }
    }

    private fun startCapture() {
        val arSession = session ?: run {
            statusText.text = "ARCore session not ready."
            return
        }
        val sessionDir = File(getExternalFilesDir(null), "session_${System.currentTimeMillis()}")
        writer = DatasetWriter(sessionDir)
        writer?.writeSessionMetadata(deviceModel = android.os.Build.MODEL)
        frameIndex = 0
        captureButton.text = "Stop capture"

        captureJob = scope.launch {
            while (isActive) {
                captureOneFrame(arSession)
                delay(500L) // 2 fps starter default; tune this later.
            }
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        writer?.close()
        writer = null
        captureButton.text = "Start capture"
        statusText.text = "Capture stopped. Sessions are in app external files."
    }

    private fun captureOneFrame(arSession: Session) {
        try {
            val frame = arSession.update()
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                statusText.text = "Waiting for tracking... ${camera.trackingState}"
                return
            }

            val image = frame.acquireCameraImage()
            image.use { cameraImage ->
                val idx = frameIndex++
                val intrinsics = camera.imageIntrinsics
                val poseMatrix = FloatArray(16)
                camera.pose.toMatrix(poseMatrix, 0)

                val depthResult = tryAcquireRawDepth(frame, idx)
                writer?.writeFrame(
                    index = idx,
                    timestampNs = frame.timestamp,
                    cameraImage = cameraImage,
                    poseMatrix = poseMatrix,
                    fx = intrinsics.focalLength[0],
                    fy = intrinsics.focalLength[1],
                    cx = intrinsics.principalPoint[0],
                    cy = intrinsics.principalPoint[1],
                    width = intrinsics.imageDimensions[0],
                    height = intrinsics.imageDimensions[1],
                    depthPath = depthResult?.first,
                    confidencePath = depthResult?.second
                )
                statusText.text = String.format(Locale.US, "Captured frame %06d", idx)
            }
        } catch (e: Exception) {
            statusText.text = "Capture skipped: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun tryAcquireRawDepth(frame: Frame, index: Int): Pair<String?, String?>? {
        val w = writer ?: return null
        var depthPath: String? = null
        var confPath: String? = null
        try {
            frame.acquireRawDepthImage16Bits().use { depthImage ->
                depthPath = w.writeRawPlane("depth_raw/frame_%06d_depth_u16.raw".format(index), depthImage)
            }
        } catch (_: Exception) {
        }
        try {
            frame.acquireRawDepthConfidenceImage().use { confImage ->
                confPath = w.writeRawPlane("confidence/frame_%06d_conf_u8.raw".format(index), confImage)
            }
        } catch (_: Exception) {
        }
        return if (depthPath != null || confPath != null) Pair(depthPath, confPath) else null
    }
}

private inline fun <T : Image?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this?.close()
    }
}
