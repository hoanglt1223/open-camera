/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.opencamera.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.opencamera.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-beta2"
    }

    // Release signing comes from ~/.gradle/gradle.properties (openCamera.release.*); the keystore
    // never lives in the repository. Without those properties the release APK is unsigned.
    val releaseStore = providers.gradleProperty("openCamera.release.storeFile")
    signingConfigs {
        if (releaseStore.isPresent) {
            create("release") {
                storeFile = file(releaseStore.get())
                storePassword = providers.gradleProperty("openCamera.release.storePassword").get()
                keyAlias = providers.gradleProperty("openCamera.release.keyAlias").get()
                keyPassword = providers.gradleProperty("openCamera.release.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            // Phones only: OpenCV, ONNX Runtime and LiteRT ship ~60 MB of native code per ABI.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            // Enables code shrinking, obfuscation, and optimization.
            isMinifyEnabled = true

            // Enables resource shrinking.
            isShrinkResources = true

            signingConfig = signingConfigs.findByName("release")
            // The embedded git state differs between a clone and a worktree; keep it out so the
            // APK only depends on the sources.
            vcsInfo.include = false

            // Includes the default ProGuard rules files.
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
        }

        // Macrobenchmark target: release code (R8, non-debuggable) but profileable, debug-signed and
        // installed next to the signed release package.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            isProfileable = true
            matchingFallbacks += listOf("release")
            // scripts/bench/run.sh --profile: baseline profile rules need unobfuscated names.
            if (providers.gradleProperty("openCamera.benchNoMinify").isPresent) {
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }

        debug {
            // Append .dev to package name so we won't conflict with AOSP build. Test builds of
            // parallel branches can pick their own suffix (-PopenCamera.debugSuffix=.p11).
            applicationIdSuffix = providers.gradleProperty("openCamera.debugSuffix").getOrElse(".dev")
        }
    }

    // Bundled ONNX models (MI-GAN) are memory-mapped from the APK; compression would force a copy.
    androidResources {
        noCompress += listOf("onnx", "tflite")
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

    buildFeatures {
        compose = true
    }

    // Reproducible builds (F-Droid): the dependency metadata block is encrypted with a random key,
    // so two builds of the same commit would differ.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
    }
}

// Device profile database: writes build/generated/profiles-assets/profiles.bundle.json.
apply(from = "../gradle/bundle-profiles.gradle")
android.sourceSets.getByName("main").assets.srcDir("build/generated/profiles-assets")
tasks.named("preBuild").configure { dependsOn("bundleDeviceProfiles") }

dependencies {
    // gradle-generatebp cannot resolve project dependencies; Soong gets them via filegroups.
    if (gradle.startParameter.taskNames.none { it.endsWith("generateBp") }) {
        implementation(project(":profiles"))
        implementation(project(":capture"))
        implementation(project(":pipeline"))
        implementation(project(":vendor-bridge"))
        implementation(project(":ml"))
    }
    // :ml runtimes, listed here as well so gradle-generatebp vendors them for Soong.
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.activity.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.viewfinder.core)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.heifwriter)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.opencv)
    implementation(libs.androidx.preference)
    // Installs the baseline profile (src/main/baseline-prof.txt) on sideloaded installs too.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.tracing.ktx)
    implementation(libs.material)
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.zxing.core)
    implementation(libs.zxing.cpp.android)

    // Heap leak detection in debug builds only (Apache-2.0); never shipped in release.
    debugImplementation(libs.leakcanary.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// The Android.bp generator comes from a repository outside Maven Central and Google, so it is
// loaded only when that task is requested and never takes part in an APK build.
if (gradle.startParameter.taskNames.any { it.endsWith("generateBp") }) {
    apply(from = "../gradle/soong.gradle")
}
