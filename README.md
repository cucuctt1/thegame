# Tile Solver

Private, offline Android 14–16 solver for the triple-tile game shown in `image.png`.

## Build

1. Install JDK 17+ and Android SDK platform 37.
2. Point `local.properties` at the Android SDK.
3. Download the pinned OpenCV AAR with `powershell -ExecutionPolicy Bypass -File scripts/download-opencv.ps1` (or `pwsh scripts/download-opencv.ps1`).
4. Run `./gradlew assembleDebug` (`gradlew.bat assembleDebug` on Windows).

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Phone setup

1. Sideload the APK.
2. Open Tile Solver and enable its accessibility service.
3. Allow display-over-other-apps for the floating emergency STOP button.
4. Enter the target game's Android package name.
5. Open the game to a playable portrait board.
6. Return to Tile Solver, choose **Approve capture and start**, approve screen capture, then switch back to the game.

Automation is package-locked and board-bounds-locked. It stops on a user touch, unexpected screen/package, invalid geometry, stale capture, a full tray, unconfirmed taps, or the 20-minute limit. It does not request network access or use ads, purchases, boosters, or retries.

## Verification

Run `./gradlew testDebugUnitTest assembleDebug`. Unit tests cover cluster matching, triple prioritization, blocked-tile exclusion, and hard tap safety boundaries. Live board calibration and the ten-level acceptance run require the target phone and game.
