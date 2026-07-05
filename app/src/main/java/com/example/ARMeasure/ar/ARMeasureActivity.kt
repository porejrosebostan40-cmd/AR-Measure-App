package com.example.ARMeasure.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ARMeasure.R
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import android.content.Intent
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ARMeasureActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlayView: MeasurementOverlayView
    private lateinit var statusText: TextView
    private lateinit var surfaceInfoText: TextView
    private lateinit var depthInfoText: TextView
    private lateinit var trackingProgress: ProgressBar
    private lateinit var reticleView: View
    private lateinit var reticleHintText: TextView
    private lateinit var distanceText: TextView
    private lateinit var undoButton: MaterialButton
    private lateinit var placePointButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var rescanButton: MaterialButton

    private val backgroundRenderer = BackgroundRenderer()
    private val queuedPlacementRequests = ArrayBlockingQueue<PlacementRequest>(16)
    private val measurePoints = mutableListOf<MeasurePoint>()

    private var session: Session? = null
    private var depthEnabled = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lastPostedStatus = 0
    private var lastPostedSurfaceInfo = ""
    private var lastPostedDepthInfo = ""
    private var lastReticleState: ReticleState? = null
    private var reticleTransientUntilMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_measure)

        surfaceView = findViewById(R.id.arSurfaceView)
        overlayView = findViewById(R.id.measurementOverlay)
        statusText = findViewById(R.id.arStatusText)
        surfaceInfoText = findViewById(R.id.surfaceInfoText)
        depthInfoText = findViewById(R.id.depthInfoText)
        trackingProgress = findViewById(R.id.trackingProgress)
        reticleView = findViewById(R.id.reticleView)
        reticleHintText = findViewById(R.id.reticleHintText)
        distanceText = findViewById(R.id.distanceText)
        undoButton = findViewById(R.id.undoButton)
        placePointButton = findViewById(R.id.placePointButton)
        resetButton = findViewById(R.id.resetButton)
        rescanButton = findViewById(R.id.rescanButton)

        surfaceView.setPreserveEGLContextOnPause(true)
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        undoButton.setOnClickListener {
            surfaceView.queueEvent {
                undoLastPoint()
            }
        }

        placePointButton.setOnClickListener {
            queueCenterPlacement()
        }

        resetButton.setOnClickListener {
            surfaceView.queueEvent {
                clearMeasurement()
            }
        }

        rescanButton.setOnClickListener {
            restartArScreenForFreshScan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                session = Session(this).also { arSession ->
                    val config = Config(arSession).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        focusMode = Config.FocusMode.AUTO
                        if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                            depthEnabled = true
                        }
                    }
                    arSession.configure(config)
                }
            } catch (_: Exception) {
                statusText.setText(R.string.ar_status_session_error)
                return
            }
        }

        try {
            session?.resume()
            surfaceView.onResume()
        } catch (_: CameraNotAvailableException) {
            statusText.setText(R.string.ar_status_session_error)
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAnchorsOnly()
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val arSession = session ?: return
        if (viewportWidth == 0 || viewportHeight == 0) return

        arSession.setCameraTextureName(backgroundRenderer.textureId)
        arSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)

        val frame = try {
            arSession.update()
        } catch (_: CameraNotAvailableException) {
            postStatus(R.string.ar_status_session_error)
            return
        }

        backgroundRenderer.draw(frame)
        handleQueuedPlacement(frame)
        postFrameStatus(frame, arSession)
        postSurfaceInfo(buildSurfaceInfo(arSession), buildDepthInfo())
        updateOverlay(frame.camera)
    }

    private fun queueCenterPlacement() {
        if (measurePoints.size >= MAX_POINTS) {
            postStatus(R.string.ar_status_measured)
            return
        }
        if (viewportWidth == 0 || viewportHeight == 0) {
            postStatus(R.string.ar_status_scanning)
            postReticleState(ReticleState.SCANNING)
            return
        }
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        queuedPlacementRequests.offer(PlacementRequest(centerX, centerY))
    }

    private fun handleQueuedPlacement(frame: Frame) {
        val request = queuedPlacementRequests.poll() ?: return
        if (measurePoints.size >= MAX_POINTS) {
            postStatus(R.string.ar_status_measured)
            return
        }

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            postStatus(R.string.ar_status_tracking_lost)
            postReticleState(ReticleState.SCANNING)
            return
        }

        val selectedHit = selectBestHit(frame.hitTest(request.x, request.y))
        if (selectedHit == null) {
            postStatus(R.string.ar_status_no_hit)
            postReticleState(ReticleState.NO_HIT, TRANSIENT_RETICLE_HOLD_MS)
            return
        }

        measurePoints.add(
            MeasurePoint(
                anchor = selectedHit.hitResult.createAnchor(),
                surfaceLabel = selectedHit.surfaceLabel
            )
        )
        postMeasurementUi()
        postReticleState(
            if (measurePoints.size >= MAX_POINTS) {
                ReticleState.MEASURED
            } else {
                ReticleState.POINT_PLACED
            },
            TRANSIENT_RETICLE_HOLD_MS
        )
    }

    private fun selectBestHit(hitResults: List<HitResult>): SelectedHit? {
        var bestHit: SelectedHit? = null
        hitResults.forEach { hitResult ->
            val candidate = classifyHit(hitResult) ?: return@forEach
            if (bestHit == null || candidate.priority > bestHit!!.priority) {
                bestHit = candidate
            }
        }
        return bestHit
    }

    private fun classifyHit(hitResult: HitResult): SelectedHit? {
        return when (val trackable = hitResult.trackable) {
            is DepthPoint -> {
                if (trackable.trackingState == TrackingState.TRACKING) {
                    SelectedHit(hitResult, "Depth object surface", HIT_PRIORITY_DEPTH)
                } else {
                    null
                }
            }
            is Plane -> {
                if (
                    trackable.trackingState == TrackingState.TRACKING &&
                    trackable.isPoseInPolygon(hitResult.hitPose)
                ) {
                    SelectedHit(hitResult, planeLabel(trackable), HIT_PRIORITY_PLANE)
                } else {
                    null
                }
            }
            is Point -> {
                if (
                    trackable.trackingState == TrackingState.TRACKING &&
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                ) {
                    SelectedHit(hitResult, "Feature point surface", HIT_PRIORITY_POINT)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun postFrameStatus(frame: Frame, arSession: Session) {
        if (measurePoints.size >= MAX_POINTS) return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            postStatus(R.string.ar_status_tracking_lost)
            postReticleState(ReticleState.SCANNING)
            return
        }

        val hasPlane = arSession.getAllTrackables(Plane::class.java).any { plane ->
            plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null
        }

        val status = when {
            !hasPlane -> R.string.ar_status_scanning
            measurePoints.isEmpty() -> R.string.ar_status_tap_first
            else -> R.string.ar_status_tap_second
        }
        postStatus(status)
        if (SystemClock.uptimeMillis() >= reticleTransientUntilMs) {
            postReticleState(
                when {
                    !hasPlane -> ReticleState.SCANNING
                    measurePoints.isEmpty() -> ReticleState.READY_FIRST
                    else -> ReticleState.READY_SECOND
                }
            )
        }
    }

    private fun buildSurfaceInfo(arSession: Session): String {
        val planes = arSession.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }

        val upward = planes.count { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val vertical = planes.count { it.type == Plane.Type.VERTICAL }
        val downward = planes.count { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }

        return when {
            upward > 0 && vertical == 0 && downward == 0 ->
                getString(R.string.ar_surface_info_floor, upward)
            vertical > 0 && upward == 0 && downward == 0 ->
                getString(R.string.ar_surface_info_wall, vertical)
            planes.isNotEmpty() ->
                getString(R.string.ar_surface_info_mixed, planes.size)
            else -> getString(R.string.ar_surface_info_none)
        }
    }

    private fun buildDepthInfo(): String {
        return if (depthEnabled) {
            getString(R.string.ar_depth_on)
        } else {
            getString(R.string.ar_depth_off)
        }
    }

    private fun updateOverlay(camera: Camera) {
        val start = measurePoints.getOrNull(0)?.anchor
            ?.takeIf { it.trackingState == TrackingState.TRACKING }
            ?.toScreenPoint(camera)
        val end = measurePoints.getOrNull(1)?.anchor
            ?.takeIf { it.trackingState == TrackingState.TRACKING }
            ?.toScreenPoint(camera)

        overlayView.post {
            overlayView.setMeasurement(start, end)
        }
    }

    private fun Anchor.toScreenPoint(camera: Camera): MeasurementOverlayView.ScreenPoint? {
        return projectWorldPoint(floatArrayOf(pose.tx(), pose.ty(), pose.tz()), camera)
    }

    private fun projectWorldPoint(
        worldPoint: FloatArray,
        camera: Camera
    ): MeasurementOverlayView.ScreenPoint? {
        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        val viewProjectionMatrix = FloatArray(16)
        val worldVector = floatArrayOf(worldPoint[0], worldPoint[1], worldPoint[2], 1f)
        val clipVector = FloatArray(4)

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP_METERS, FAR_CLIP_METERS)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMV(clipVector, 0, viewProjectionMatrix, 0, worldVector, 0)

        val clipW = clipVector[3]
        if (clipW <= 0f) return null

        val ndcX = clipVector[0] / clipW
        val ndcY = clipVector[1] / clipW
        val x = (ndcX + 1f) * 0.5f * overlayView.width
        val y = (1f - ndcY) * 0.5f * overlayView.height
        return MeasurementOverlayView.ScreenPoint(x, y)
    }

    private fun postMeasurementUi() {
        val pointCount = measurePoints.size
        val distanceMeters = if (pointCount == MAX_POINTS) {
            distanceMeters(measurePoints[0].anchor, measurePoints[1].anchor)
        } else {
            null
        }

        runOnUiThread {
            undoButton.isEnabled = pointCount > 0
            placePointButton.isEnabled = pointCount < MAX_POINTS
            resetButton.isEnabled = pointCount > 0
            distanceText.text = distanceMeters?.let {
                getString(R.string.ar_distance_meters, it)
            } ?: getString(R.string.ar_distance_empty)

            statusText.setText(
                when (pointCount) {
                    0 -> R.string.ar_status_tap_first
                    1 -> R.string.ar_status_tap_second
                    else -> R.string.ar_status_measured
                }
            )
        }
    }

    private fun distanceMeters(start: Anchor, end: Anchor): Float {
        val a = start.pose
        val b = end.pose
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun undoLastPoint() {
        measurePoints.removeLastOrNull()?.anchor?.detach()
        postMeasurementUi()
    }

    private fun clearMeasurement() {
        clearAnchorsOnly()
        overlayView.post {
            overlayView.clearMeasurement()
        }
        postMeasurementUi()
        postReticleState(ReticleState.SCANNING)
    }

    private fun restartArScreenForFreshScan() {
        startActivity(Intent(this, ARMeasureActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    private fun clearAnchorsOnly() {
        measurePoints.forEach { it.anchor.detach() }
        measurePoints.clear()
    }

    private fun postStatus(messageResId: Int) {
        if (lastPostedStatus == messageResId) return
        lastPostedStatus = messageResId
        runOnUiThread {
            statusText.setText(messageResId)
        }
    }

    private fun postSurfaceInfo(surfaceMessage: String, depthMessage: String) {
        if (lastPostedSurfaceInfo == surfaceMessage && lastPostedDepthInfo == depthMessage) return
        lastPostedSurfaceInfo = surfaceMessage
        lastPostedDepthInfo = depthMessage
        runOnUiThread {
            surfaceInfoText.text = surfaceMessage
            depthInfoText.text = depthMessage
        }
    }

    private fun postReticleState(state: ReticleState, holdMs: Long = 0L) {
        if (lastReticleState == state) return
        lastReticleState = state
        reticleTransientUntilMs = if (holdMs > 0L) {
            SystemClock.uptimeMillis() + holdMs
        } else {
            0L
        }
        runOnUiThread {
            reticleView.setBackgroundResource(state.backgroundResId)
            reticleHintText.setText(state.hintResId)
            trackingProgress.visibility = if (state == ReticleState.SCANNING) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun planeLabel(plane: Plane): String {
        return when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> "Floor/table plane"
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> "Ceiling plane"
            Plane.Type.VERTICAL -> "Wall/door plane"
        }
    }

    private val displayRotation: Int
        get() = display?.rotation ?: Surface.ROTATION_0

    private data class MeasurePoint(
        val anchor: Anchor,
        val surfaceLabel: String
    )

    private data class PlacementRequest(
        val x: Float,
        val y: Float
    )

    private data class SelectedHit(
        val hitResult: HitResult,
        val surfaceLabel: String,
        val priority: Int
    )

    private enum class ReticleState(
        val backgroundResId: Int,
        val hintResId: Int
    ) {
        SCANNING(R.drawable.bg_reticle_scanning, R.string.ar_reticle_scanning),
        READY_FIRST(R.drawable.bg_reticle_ready, R.string.ar_reticle_ready_first),
        READY_SECOND(R.drawable.bg_reticle_ready, R.string.ar_reticle_ready_second),
        POINT_PLACED(R.drawable.bg_reticle_placed, R.string.ar_reticle_placed),
        NO_HIT(R.drawable.bg_reticle_error, R.string.ar_reticle_no_hit),
        MEASURED(R.drawable.bg_reticle_placed, R.string.ar_reticle_measured)
    }

    companion object {
        private const val MAX_POINTS = 2
        private const val NEAR_CLIP_METERS = 0.1f
        private const val FAR_CLIP_METERS = 100f
        private const val HIT_PRIORITY_PLANE = 3
        private const val HIT_PRIORITY_DEPTH = 2
        private const val HIT_PRIORITY_POINT = 1
        private const val TRANSIENT_RETICLE_HOLD_MS = 900L
    }
}
