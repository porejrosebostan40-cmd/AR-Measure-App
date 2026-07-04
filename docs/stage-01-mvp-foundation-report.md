# Stage 01 MVP Foundation Report

This stage implements the first working MVP path: home screen -> permission and ARCore checks -> AR camera screen -> detect planes -> tap two points -> show approximate distance.

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
  - Added tap handling, anchor creation, distance calculation, Undo, and Reset.

- `app/src/main/java/com/example/ARMeasure/ar/BackgroundRenderer.kt`
  - Added a small OpenGL renderer for drawing the live AR camera feed onto `GLSurfaceView`.

- `app/src/main/java/com/example/ARMeasure/ar/MeasurementOverlayView.kt`
  - Added a 2D overlay that draws point markers and a line between the two selected AR points.

- `app/src/main/res/layout/activity_ar_measure.xml`
  - Added AR screen layout: camera surface, overlay, status text, distance label, Undo, and Reset.

- `app/src/main/res/values/strings.xml`
  - Added user-facing text for home status, AR scanning, tracking, no-hit messages, distance, Undo, and Reset.

## How It Works

`MainActivity` is now the entry gate. When the user taps Start Measuring, it first checks camera permission. If permission is granted, it checks whether ARCore is supported and installed. If ARCore needs installation or update, it requests that through `ArCoreApk.requestInstall()`. Only after these checks does it open `ARMeasureActivity`.

`ARMeasureActivity` creates an ARCore `Session` and configures plane detection with:

```kotlin
Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
```

This allows the app to detect floor/table-like surfaces and wall-like surfaces.

The AR frame loop does four main tasks:

1. Updates the ARCore session.
2. Draws the camera image through `BackgroundRenderer`.
3. Processes queued taps.
4. Updates the overlay markers and status text.

Tap handling uses ARCore hit testing. When the user taps, the app searches for the first hit result whose trackable is a detected `Plane`, is currently tracking, and contains the hit pose inside the plane polygon. If the hit is valid, the app creates an `Anchor`.

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
6. Status asks the user to move slowly until a plane is detected.
7. User taps a detected horizontal or vertical plane.
8. First marker appears.
9. User taps a second valid point.
10. Second marker appears, a line is drawn, and approximate distance is shown.
11. Undo removes the last point.
12. Reset clears both anchors and the displayed measurement.

## Current Limits

- Depth API is not included yet.
- Save/export/history is not included yet.
- Area mode and height mode are not included yet.
- Only one two-point measurement segment is supported.
- Markers and the line are 2D overlay projections, not real 3D AR objects.
- The app should be verified manually in Android Studio and on a real ARCore-supported phone.
