# Stage 02 Real App Roadmap

## Summary

The current MVP supports ARCore session startup, reticle-based two-point measurement, plane-first hit testing, optional Depth API, Undo, Reset, Rescan, surface info, and a basic result panel.

The next stage should not chase advanced AR algorithms first. It should make the app easier to use, easier to trust, and more useful through better UI/UX, safer measurement states, and practical user-facing features.

## Implementation Milestones

### 1. Measurement UX Polish

- Add a clearer ready-to-place state near the reticle.
- Improve status messages for scanning, tracking lost, no hit, depth unavailable, and measurement complete.
- Add small visual state changes to the reticle:
  - scanning
  - ready
  - point placed
  - no reliable hit
- Keep plane-boundary overlays out for now because the previous attempt confused reliability.

### 2. Basic Feature Expansion

- Add a unit toggle:
  - meters
  - centimeters
  - feet
  - inches
- Add a small measurement details panel showing:
  - start surface type
  - end surface type
  - selected unit
- Add New Measurement behavior after two points so users can quickly start another two-point measurement without restarting AR.
- Keep one active segment at a time in this stage. Do not jump to full room polygon mode yet.

### 3. Multiple Segments Mode

- Add optional connected-segment measuring:
  - Point 1 -> Point 2
  - Point 2 -> Point 3
  - Point 3 -> Point 4
- Show each segment distance.
- Show total measured length.
- Add Undo Last Point.
- Add Clear All.
- Use this for wall/perimeter-style measurement, but do not calculate area yet.

### 4. Save/Share Lightweight Result

- Add share text summary first, not database storage.
- Share content should include:
  - segment distances
  - total distance
  - selected unit
  - date/time
  - "Approximate AR measurement"
- Keep save/export/history as a later stage after the measurement model stabilizes.

## Accuracy-Supporting UX Rules

- Preserve the stable hit-test order:
  1. Plane
  2. Depth
  3. Feature Point
- Avoid visual plane polygon overlays in this stage.
- Add user guidance that improves accuracy:
  - "Move slowly."
  - "Aim at textured surfaces."
  - "Place both points on the same intended surface/object."
  - "Use Rescan if scale feels wrong."
- Add result wording that avoids overclaiming precision:
  - Always use `Approx.`
  - Use sensible formatting per unit.
  - Consider showing a short confidence note based on tracking state and hit type.
  - Do not invent a numeric confidence score.

## Suggested File Structure Notes

- The roadmap may recommend extracting measurement logic later, but the next implementation does not need a large architecture refactor.
- If multiple segments are added, introduce simple models:
  - `MeasurePoint`
  - `MeasurementSegment`
  - `UnitMode`
- Keep XML Views, Kotlin, and ARCore as the active stack.
- Do not add Compose, Room, area mode, height mode, or full export persistence in Stage 02.

## Test And Acceptance Notes

- Test AR behavior on a real ARCore-supported device.
- Verify reticle states are understandable before measuring.
- Verify unit toggle converts the same stored meter value rather than recalculating anchors.
- Verify Reset, Undo, Rescan, and New Measurement remain distinct:
  - Undo removes last placed point.
  - Reset clears current measurement.
  - Rescan restarts AR tracking/session.
  - New Measurement clears current points without restarting tracking.
- Verify multiple segments show correct per-segment and total distance.
- Codex must not build or run the app unless explicitly requested by the user.

## Assumptions

- This file is documentation only.
- The preferred next direction is combined UI/UX plus practical features.
- Accuracy work in Stage 02 should be mostly user guidance, readiness, state clarity, and conservative hit usage, not major AR algorithm experimentation.
