// File: app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.darkroom.raw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.darkroom.raw"
        minSdk = 33 // Android 13+ (Tiramisu) through Android 16
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-s22"

        ndk {
            // Optimized strictly for Samsung Galaxy S22 64-bit ARMv8.2-A architecture
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3", "-ffast-math", "-fopenmp", "-fexceptions")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Jetpack Compose BOM & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Coroutines & Serialization for Curve Points / Presets
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Jetpack Room 2.8.5 (room-ktx is merged directly into room-runtime in 2.8+)
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Jetpack WorkManager 2.12.0 for thermal-safe background Batch Printing
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // AndroidX ExifInterface 1.4.2 for reading .CR2/.DNG/.NEF/.ARW EXIF & writing to output
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
