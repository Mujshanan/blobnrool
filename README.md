# Blob'n'Roll — Android survival prototype

Blob'n'Roll is a native Android arcade survival game prototype built with Kotlin and Android Canvas. Cute white blobs try to escape a pursuing evil ink blob inside a small obstacle-filled arena.

## Current prototype
- Six white survivors with simple stroke-drawn faces.
- An ink hunter that pursues the nearest survivor.
- Touch and drag a white blob to guide it away from the hunter.
- Solid walls and rounded obstacles block movement.
- Survivors panic and try to flee when the ink approaches.
- A survival timer, capture/game-over state, and restart button.
- Lightweight native rendering with no third-party runtime libraries.

This is an early gameplay prototype. Obstacle-aware pathfinding, dash attacks, splatter effects, temporary ink puddles, improved procedural blob contours, sound, and persistent high scores are planned follow-up work.

## Build an APK
The repository includes a GitHub Actions workflow at `.github/workflows/android.yml`. It uses Java 17 and Gradle 8.9 to run `assembleDebug`, checks that the APK exists, and uploads it as the `blobnroll-debug-apk` artifact.

1. Open the **Actions** tab.
2. Select **Android APK**.
3. Open the latest run and wait for it to finish.
4. Download the APK from the run's **Artifacts** section.

The expected output is `app/build/outputs/apk/debug/app-debug.apk`. This is a debug build, not a signed Play Store release.

## Project
- Kotlin and Android Canvas; no third-party runtime libraries.
- `MeatballGameView.kt` contains the game loop, drawing, touch handling, simple AI, collisions, timer, and obstacle layout.
- `MainActivity.kt` hosts the full-screen game view.
