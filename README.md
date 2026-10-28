# Blob'n'Roll — Android prototype

A native Android puzzle game about dragging organic blobs together. The game view samples a compact-support scalar field and uses **Marching Squares** to draw its contour. Drag blobs into one another to remove pairs and clear the 12-blob round as quickly as possible.

## Gameplay
- 12 same-size blobs spawn at random positions.
- Touch and drag a blob; when it reaches another blob, the pair disappears.
- Clear the board to finish the timer. Tap **RESTART** to play again.

## Build an APK
The repository includes a GitHub Actions workflow at `.github/workflows/android.yml`. It uses Java 17 and Gradle 8.9 to run `assembleDebug`, checks that the APK exists, and uploads it as the `blobnroll-debug-apk` artifact.

1. Open the **Actions** tab.
2. Select **Android APK**.
3. Open the latest run and wait for it to finish.
4. Download the APK from the run's **Artifacts** section.

The expected output is `app/build/outputs/apk/debug/app-debug.apk`. This is a debug build, not a signed Play Store release.

## Project
- Kotlin and Android Canvas; no third-party runtime libraries.
- `MeatballGameView.kt` contains the game, touch handling, timer and Marching Squares contour rendering.
- `MainActivity.kt` hosts the full-screen game view.
