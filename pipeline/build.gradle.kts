/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.opencamera.pipeline"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        // Compiles against :ml's shared API; the app picks the :ml flavor that gets packaged.
        missingDimensionStrategy("distribution", "full")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
dependencies {
    implementation(project(":ml"))
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.exifinterface)
    // Optional at runtime: the app packages OpenCV (see OpenCv.available). With
    // -PopenCamera.opencvDir it is the copy built from source instead of the Maven binary.
    val opencv: Any = if (providers.gradleProperty("openCamera.opencvDir").isPresent) project(":opencv-source") else libs.opencv
    compileOnly(opencv)
    androidTestImplementation(opencv)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
