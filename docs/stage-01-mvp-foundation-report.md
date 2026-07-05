# Stage 01 MVP Foundation Report

This stage implements the first working MVP path: home screen -> permission and ARCore checks -> AR camera screen -> detect surfaces -> aim with the center reticle -> press Place Point twice -> show approximate distance.

## Files Changed

- `gradle/libs.versions.toml`
  - Added ARCore version catalog entry: `com.google.ar:core:1.54.0`.

- `app/build.gradle.kts`
  - Added `implementation(libs.arcore)` so the app can use Google ARCore APIs.

- `app/src/main/AndroidManifest.xml`
  - Added camera permission.
  - Added ARCore required metadata.
  - Added OpenGL ES requirement.
  - Added AR camera feature.
  - Registered `ARMeasureActivity`.

- `app/src/main/java/com/example/ARMeasure/MainActivity.kt`
  - Replaced the template logic with camera permission handling.
  - Added ARCore support check.
  - Added ARCore install/update request.
  - Opens the AR screen only after required checks pass.

- `app/src/main/res/layout/activity_main.xml`
  - Replaced the template "Hello World" screen with a simple home screen.
  - Added app title, subtitle, Start Measuring button, and status text.

- `app/src/main/java/com/example/ARMeasure/ar/ARMeasureActivity.kt`
  - Added the main AR session lifecycle.
  - Added plane detection configuration.
  - Added reticle-based point placement, anchor creation, distance calculation, Undo, Reset, and Rescan.
  - Added optional Depth API support when the device supports it.

- `app/src/main/java/com/example/ARMeasure/ar/BackgroundRenderer.kt`
  - Added a small OpenGL renderer for drawing the live AR camera feed onto `GLSurfaceView`.

- `app/src/main/java/com/example/ARMeasure/ar/MeasurementOverlayView.kt`
  - Added a 2D overlay that draws point markers and a line between the two selected AR points.

- `app/src/main/res/layout/activity_ar_measure.xml`
  - Added AR screen layout: camera surface, overlay, status text, surface info, center reticle, result panel, Place Point, Undo, Reset, and Rescan.

- `app/src/main/res/values/strings.xml`
  - Added user-facing text for home status, AR scanning, tracking, no-hit messages, distance, Place Point, Undo, Reset, and Rescan.

## How It Works

`MainActivity` is now the entry gate. When the user taps Start Measuring, it first checks camera permission. If permission is granted, it checks whether ARCore is supported and installed. If ARCore needs installation or update, it requests that through `ArCoreApk.requestInstall()`. Only after these checks does it open `ARMeasureActivity`.

`ARMeasureActivity` creates an ARCore `Session` and configures plane detection with:

```kotlin
Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
```

This allows the app to detect floor/table-like surfaces and wall-like surfaces.

If supported by the device, Depth API is enabled with:

```kotlin
Config.DepthMode.AUTOMATIC
```

Depth support is used as an additional hit source for smaller or closer objects, but plane hits remain the stable MVP baseline.

The AR frame loop does four main tasks:

1. Updates the ARCore session.
2. Draws the camera image through `BackgroundRenderer`.
3. Processes queued Place Point requests from the center reticle.
4. Updates the overlay markers and status text.

Point placement uses ARCore hit testing at the center of the screen. The user aims the reticle at the target and presses Place Point. The hit-selection order is intentionally conservative:

1. Valid detected `Plane` hits first, for stable wall/floor/table measurement.
2. `DepthPoint` hits second, when supported by the device.
3. Feature `Point` hits last, as a fallback.

If the hit is valid, the app creates an `Anchor`. Reset clears placed measurement anchors. Rescan restarts the AR measuring activity to create a fresh ARCore session when tracking scale or detected surfaces become unreliable.

## UI/UX Updates

The home screen was improved from the basic template-style layout into a more polished MVP entry screen. It now includes a styled panel, short workflow guidance, and a clearer Start Measuring flow.

The AR screen now uses a center reticle plus a Place Point button instead of arbitrary screen taps. This separates aiming/focusing from measurement placement, so the user can move and align the camera without accidentally placing points. The AR screen also includes:

- top tracking/status text
- surface info text
- larger bottom result panel
- grouped Undo, Place Point, and Reset controls
- Rescan button for restarting the ARCore scan/session

A visible plane-boundary overlay was tried during iteration, but removed from the MVP because it was visually misleading and made the experience feel less reliable. The current MVP keeps surface information textual and prioritizes stable measurement behavior.

## Distance Algorithm

ARCore world coordinates are in meters. After two anchors exist, the app reads both anchor poses and computes the 3D Euclidean distance:

```kotlin
sqrt(dx * dx + dy * dy + dz * dz)
```

Here `dx`, `dy`, and `dz` are the differences between the two anchor positions on the X, Y, and Z axes. The result is shown as:

```text
Approx. X.XX m
```

## Marker and Line Rendering

The current marker and line rendering is intentionally simple for MVP. The app does not render physical 3D objects yet. Instead:

1. Each anchor's 3D position is projected into 2D screen coordinates using the camera view/projection matrices.
2. `MeasurementOverlayView` draws colored point markers on top of the camera feed.
3. When both points exist, the overlay draws a line between them.

This keeps the first version stable and avoids adding complex 3D model rendering before the measurement loop is verified.

## Expected Behavior

On a supported ARCore device:

1. App opens to the home screen.
2. User taps Start Measuring.
3. App requests camera permission if needed.
4. App checks ARCore support/install status.
5. AR screen opens with a live camera feed.
6. Status asks the user to move slowly until surfaces are detected.
7. User aims the reticle at a surface or object edge.
8. User presses Place Point.
9. First marker appears.
10. User aims the reticle at a second valid point and presses Place Point again.
11. Second marker appears, a line is drawn, and approximate distance is shown.
12. Undo removes the last point.
13. Reset clears both anchors and the displayed measurement.
14. Rescan restarts the AR measuring screen with a fresh ARCore session.

## Verification Notes

- Verify the home screen layout on portrait phone sizes.
- Verify the AR flow on a real ARCore-supported device.
- Confirm Place Point places points using the center reticle.
- Test wall, floor, and table measurements first because plane hits are the stable baseline.
- Test small or near objects separately and note whether Depth is reported as enabled.
- Use Rescan when tracking scale or detected surfaces become unreliable.
- Codex should not build or run the app unless explicitly requested.

## Current Limits

- Depth API is conditionally enabled, but support and quality depend on the device.
- Save/export/history is not included yet.
- Area mode and height mode are not included yet.
- Only one two-point measurement segment is supported.
- Markers and the line are 2D overlay projections, not real 3D AR objects.
- True tap-to-focus/manual camera focus is not implemented.
- Plane-boundary visualization is not included in the current MVP because the attempted overlay was misleading.
- Measurement accuracy still depends on ARCore tracking, lighting, surface texture, target distance, camera motion, and device support.
- The app should be verified manually in Android Studio and on a real ARCore-supported phone.
