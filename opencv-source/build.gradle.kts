/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

// OpenCV built from source by scripts/fdroid/build-opencv-android.sh; only included when
// -PopenCamera.opencvDir points at its output (absolute, or relative to the repository root).
plugins {
    alias(libs.plugins.android.library)
}

val opencvDir = rootProject.file(providers.gradleProperty("openCamera.opencvDir").get())

android {
    namespace = "org.opencv"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        java.srcDir(opencvDir.resolve("java"))
        jniLibs.srcDir(opencvDir.resolve("jniLibs"))
    }

    lint {
        abortOnError = false
    }
}
