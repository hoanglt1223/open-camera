/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships org.json; the JVM build only needs it to compile and test.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}

// BundledProfilesTest reads the profiles checkout named by openCamera.profiles.dir, if any.
tasks.test {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load)
    }
    ((findProperty("openCamera.profiles.dir") as String?) ?: local.getProperty("openCamera.profiles.dir"))
        ?.let { systemProperty("openCamera.profiles.dir", rootProject.file(it).absolutePath) }
}
