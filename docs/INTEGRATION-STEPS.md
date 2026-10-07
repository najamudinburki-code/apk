Historical snapshot: kept because it records how the separate original bundles were merged. The
live procedure is `../START-HERE.md` and `../RENDER-SETUP.md`; current architecture is in
`docs/FEATURE-STATUS.md`.

# Current integration steps

Follow ../START-HERE.md for building in Android Studio, server/dashboard setup, phone enrollment, and optional capture consent.

The five ScreenMonitorService compiler errors are resolved. The screen monitor is included in the complete build. MainActivity already exists inside CoreService.kt; ParentalCapture already exists inside AccessibilityHelperService.kt.

Current wiring and remaining prototypes are documented in CONNECTOR-REVIEW.md and FEATURE-STATUS.md. Earlier integration notes describe historical snapshots.
