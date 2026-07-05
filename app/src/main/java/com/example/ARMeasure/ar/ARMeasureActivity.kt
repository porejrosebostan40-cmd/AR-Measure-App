package com.example.ARMeasure.ar

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import kotlin.math.max
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
    private lateinit var distanceUnitText: TextView
    private lateinit var unitButton: MaterialButton
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
    private var selectedUnit = UnitMode.METERS
    private var unitMenuPopup: PopupWindow? = null

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
        distanceUnitText = findViewById(R.id.distanceUnitText)
        unitButton = findViewById(R.id.unitButton)
        undoButton = findViewById(R.id.undoButton)
        placePointButton = findViewById(R.id.placePointButton)
        resetButton = findViewById(R.id.resetButton)
        rescanButton = findViewById(R.id.rescanButton)

        surfaceView.setPreserveEGLContextOnPause(true)
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        unitButton.setOnClickListener {
            showUnitMenu()
        }

        undoButton.setOnClickListener {
            surfaceView.queueEvent {
                undoLastPoint()
            }
        }

        placePointButton.setOnClickListener {
            if (measurePoints.size >= MAX_POINTS) {
                surfaceView.queueEvent {
                    clearMeasurement()
                }
            } else {
                queueCenterPlacement()
            }
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
        unitMenuPopup?.dismiss()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unitMenuPopup?.dismiss()
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
                    SelectedHit(hitResult, "Depth", HIT_PRIORITY_DEPTH)
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
                    SelectedHit(hitResult, "Feature", HIT_PRIORITY_POINT)
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
            placePointButton.isEnabled = true
            placePointButton.setText(
                if (pointCount >= MAX_POINTS) {
                    R.string.ar_new_measurement
                } else {
                    R.string.ar_place_point
                }
            )
            resetButton.isEnabled = pointCount > 0
            distanceText.text = distanceMeters?.let {
                formatDistance(it)
            } ?: getString(R.string.ar_distance_empty)
            distanceUnitText.setText(selectedUnit.symbolResId)
            unitButton.setText(selectedUnit.nameResId)

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

    private fun formatDistance(distanceMeters: Float): String {
        return when (selectedUnit) {
            UnitMode.METERS -> getString(R.string.ar_distance_meters, distanceMeters)
            UnitMode.CENTIMETERS -> getString(
                R.string.ar_distance_centimeters,
                distanceMeters * 100f
            )
            UnitMode.FEET -> getString(R.string.ar_distance_feet, distanceMeters * METERS_TO_FEET)
            UnitMode.INCHES -> getString(
                R.string.ar_distance_inches,
                distanceMeters * METERS_TO_INCHES
            )
        }
    }

    private fun showUnitMenu() {
        unitMenuPopup?.dismiss()

        val popupWidth = dp(292)
        val popupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = getDrawable(R.drawable.bg_unit_dropdown)
            UnitMode.values().forEach { unitMode ->
                addView(createUnitMenuRow(unitMode))
            }
        }

        unitMenuPopup = PopupWindow(
            popupContent,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val anchorLocation = IntArray(2)
        unitButton.getLocationOnScreen(anchorLocation)
        val x = max(dp(12), anchorLocation[0] + unitButton.width - popupWidth)
        val y = max(dp(16), anchorLocation[1] - popupContent.measuredHeight - dp(10))
        unitMenuPopup?.showAtLocation(window.decorView, Gravity.NO_GRAVITY, x, y)
    }

    private fun createUnitMenuRow(unitMode: UnitMode): View {
        val isSelected = unitMode == selectedUnit
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            if (isSelected) {
                background = getDrawable(R.drawable.bg_unit_dropdown_item_selected)
            }
            setOnClickListener {
                selectedUnit = unitMode
                unitMenuPopup?.dismiss()
                unitMenuPopup = null
                postMeasurementUi()
            }
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = getString(unitMode.fullLabelResId)
            setTextColor(
                if (isSelected) {
                    Color.parseColor("#68F08D")
                } else {
                    Color.WHITE
                }
            )
            textSize = 17f
        }

        val check = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setImageResource(R.drawable.ic_check_unit)
            visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        }

        row.addView(label)
        row.addView(check)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply {
            bottomMargin = dp(4)
        }
        return row
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun undoLastPoint() {
        measurePoints.removeLastOrNull()?.anchor?.detach()
        postMeasurementUi()
    }

    private fun clearMeasurement() {
        queuedPlacementRequests.clear()
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
            Plane.Type.HORIZONTAL_UPWARD_FACING -> "Floor/table"
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> "Ceiling"
            Plane.Type.VERTICAL -> "Wall/door"
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

    private enum class UnitMode(
        val symbolResId: Int,
        val nameResId: Int,
        val fullLabelResId: Int
    ) {
        METERS(
            R.string.ar_unit_meters,
            R.string.ar_unit_meters_name,
            R.string.ar_unit_meters_full
        ),
        CENTIMETERS(
            R.string.ar_unit_centimeters,
            R.string.ar_unit_centimeters_name,
            R.string.ar_unit_centimeters_full
        ),
        FEET(
            R.string.ar_unit_feet,
            R.string.ar_unit_feet_name,
            R.string.ar_unit_feet_full
        ),
        INCHES(
            R.string.ar_unit_inches,
            R.string.ar_unit_inches_name,
            R.string.ar_unit_inches_full
        )
    }

    companion object {
        private const val MAX_POINTS = 2
        private const val NEAR_CLIP_METERS = 0.1f
        private const val FAR_CLIP_METERS = 100f
        private const val HIT_PRIORITY_PLANE = 3
        private const val HIT_PRIORITY_DEPTH = 2
        private const val HIT_PRIORITY_POINT = 1
        private const val TRANSIENT_RETICLE_HOLD_MS = 900L
        private const val METERS_TO_FEET = 3.28084f
        private const val METERS_TO_INCHES = 39.3701f
    }
}
