/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.lineageos.generatebp.GenerateBpPluginExtension
import org.lineageos.generatebp.models.Module

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.lineageos.generatebp)
}

android {
    namespace = "dev.opencamera.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.opencamera.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

            // Includes the default ProGuard rules files.
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
        }

        debug {
            // Append .dev to package name so we won't conflict with AOSP build.
            applicationIdSuffix = providers.gradleProperty("openCamera.debugIdSuffix").getOrElse(".dev")
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

    buildFeatures {
        compose = true
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
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.zxing.core)
    implementation(libs.zxing.cpp.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

configure<GenerateBpPluginExtension> {
    targetSdk.set(android.defaultConfig.targetSdk!!)
    minSdk.set(android.defaultConfig.minSdk!!)
    versionCode.set(android.defaultConfig.versionCode!!)
    versionName.set(android.defaultConfig.versionName!!)
    availableInAOSP.set { module: Module ->
        when {
            module.group == "androidx.core" -> {
                // We provide our own core-backported-fixes
                module.name != "core-backported-fixes"
            }

            module.group.startsWith("androidx") -> {
                // We provide our own androidx.{camera,media3}
                !module.group.startsWith("androidx.camera") &&
                        !module.group.startsWith("androidx.media3")
            }

            module.group.startsWith("org.jetbrains") -> {
                // We provide our own atomicfu
                !module.name.startsWith("atomicfu")
            }

            module.group == "com.google.android.material" -> true
            module.group == "com.google.auto.value" -> true
            module.group == "com.google.guava" -> true
            module.group == "javax.inject" -> true
            else -> false
        }
    }
}
