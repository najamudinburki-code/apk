Historical snapshot: the file counts below were true for 0.3.x. Since then the socket stack
(`SyncManager.kt`, `NetworkMonitor.kt`) and the duplicated `EnvironmentScanner.kt` were replaced, and
`MainActivity.kt` became its own file — see the annotated rows in `docs/FILE-MAP.json` and the
0.5.0 entry in `docs/CHANGES.md`.

# Source preservation

All 16 previous active Kotlin files are retained. The final active Android source set has 23 Kotlin files. Comparing function names in each previous file with its updated counterpart found no removed function names.

Original archive bytes and alternative sync implementations remain in the full source bundle. Original prototype dashboard components remain alongside the new integrated panels. Existing authenticated health, enrollment, telemetry, status and legacy command-dispatch APIs remain; new feature routes extend them.

This inventory demonstrates source preservation, not identical behavior or physical-device validation. Password fields remain redacted, permissions and explicit sharing approvals remain, and Android's protected/private app data is respected. See FEATURE-STATUS.md for supported workflows and device conditions.

For 0.2.1, all 19 active Kotlin files from 0.2.0 and their existing function names were retained. Only mandatory package-ID entry was replaced as requested; selected-only app sharing remains available by display name. The existing system/own-app exclusions and explicit activation controls were preserved.

For 0.3.0, all 20 active Kotlin files from 0.2.1 and their existing function names remain. Manual connection settings and backend/dashboard manual enrollment remain available alongside automatic connection. Pending registration cannot upload or enable sharing. Existing phone credentials are retained across upgrades; Stop, approval controls, password redaction and all existing tools are preserved.


For 0.3.1, all 22 active Kotlin files from 0.3.0 and all their prior function names remain. Added InstallationEnrollment.kt and backend/installation.cjs. Removed the mandatory dashboard approval step for this APK as requested; retained legacy approval/decline and custom/manual enrollment for compatibility. Every previous tool panel remains. Android permissions, visible sharing disclosure and Stop controls remain. Original archives/alternative implementations remain byte-for-byte in the full source ZIP.
