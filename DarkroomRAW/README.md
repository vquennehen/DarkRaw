# DarkroomRAW — Galaxy S22 (`arm64-v8a`) Build Instructions

This archive contains the complete Android Studio & GitHub Actions project for **DarkroomRAW**.

## Option A: 1-Click Cloud APK Compilation (No PC / Android Studio Required)
1. Create a new repository on GitHub and upload or push the contents of this archive.
2. Open the **Actions** tab in your GitHub repository and select **Build DarkroomRAW APK (Galaxy S22 arm64-v8a)** -> **Run workflow**.
3. GitHub's cloud runner automatically clones `LibRaw`, compiles the C++20 `arm64-v8a` JNI shared library (`libdarkroom_raw.so`) with the Android NDK, compiles the Jetpack Compose UI, and outputs the signed, installable **`app-debug.apk`** under the workflow run's **Artifacts** section.

## Option B: Local Build on PC (Android Studio / Command Line)
1. Clone `LibRaw` into the native third-party directory:
   ```bash
   git clone --depth 1 https://github.com/LibRaw/LibRaw.git app/src/main/cpp/third_party/LibRaw
   ```
2. Build the installable debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. The signed, installable APK will be generated at:
   `app/build/outputs/apk/debug/app-debug.apk`
