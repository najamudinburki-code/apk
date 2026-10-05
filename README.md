# System Health service integration

CoreService now starts and stops the primary network/sync managers and shares basic system-health telemetry with an enrolled HTTPS backend after the user configures the device and taps Start.

Read docs/REVIEW.md for changes, verification limits, and remaining work. Follow docs/INTEGRATION-STEPS.md for the build and device checks. docs/ORIGINAL-REVIEW.md is the earlier pre-integration assessment. Original nested ZIPs remain under archives/ and alternate sync implementations remain under variants/.

This is an updated source bundle, not a compiled APK. Android Gradle project setup and an actual HTTPS backend/device enrollment are still required.
