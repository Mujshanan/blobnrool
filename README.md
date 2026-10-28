# Blob'n'Roll — Android arcade prototype

Blob'n'Roll is a native Android arcade game prototype built with Kotlin and Android Canvas. Guide a growing black ink blob through an obstacle-filled arena and capture white blobs by drawing closed loops with its ink trail.

## Gameplay

- **Manual movement:** use the on-screen joystick near the bottom of the screen to move the black blob. It does not chase targets automatically.
- **Draw-to-capture:** move the black blob to leave an ink trail, then close a valid loop around white blobs to capture them.
- **Grow on capture:** the black blob starts about the same size as a white blob and grows when captures succeed.
- **Clean blob styling:** white blobs have a white fill and black outline, without the extra decorative outer rings.
- **Arena obstacles:** walls and rounded obstacles affect movement.
- **Survival gameplay:** includes a timer, capture/game-over state, and a restart button.
- **Native Android rendering:** implemented with Kotlin and Android Canvas, with no third-party runtime libraries.

This is an early prototype. Gameplay and capture behavior should be tested on an Android device or emulator; additional effects, sound, high scores, and further gameplay improvements may be added later.

## Build a debug APK

The repository includes a GitHub Actions workflow at `.github/workflows/android.yml`. It uses Java 17 and Gradle 8.9 to run the Android unit tests, build the debug APK, verify that the APK exists, and upload it as the `blobnroll-debug-apk` artifact.

1. Open the repository's **Actions** tab.
2. Select **Android APK**.
3. Open a completed workflow run.
4. Download `blobnroll-debug-apk` from the run's **Artifacts** section.

The expected output is `app/build/outputs/apk/debug/app-debug.apk`. This is a debug APK, not a signed Google Play release.

## Project structure

- **Kotlin + Android Canvas:** game implementation and rendering.
- `app/src/main/java/com/example/meatballmerge/MeatballGameView.kt`: game loop, drawing, joystick input, ink trail/capture behavior, collisions, timer, and arena layout.
- `app/src/main/java/com/example/meatballmerge/MainActivity.kt`: hosts the full-screen game view.
- `.github/workflows/android.yml`: automated test and APK build workflow.
