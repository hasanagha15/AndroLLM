import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Branch C (jarvis/c-tools): the app's own agent tools for the owner's Jarvis
// infrastructure — tesla-bridge, the WhatsApp bot and Even G2 glasses.

// The owner's tailnet MagicDNS host for the WhatsApp bot is NOT committed
// (public fork): set `jarvis.whatsappHost=pi-vision.<tailnet>.ts.net` in the
// git-ignored local.properties. The placeholder keeps builds and tests working.
val jarvisWhatsAppHost: String = run {
    val f = rootProject.file("local.properties")
    val p = Properties()
    if (f.exists()) f.inputStream().use { p.load(it) }
    p.getProperty("jarvis.whatsappHost")?.trim()?.takeIf { it.isNotEmpty() } ?: "pi-vision.example.ts.net"
}

android {
    namespace = "io.androllm.core.jarvistools"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "WHATSAPP_TAILNET_HOST", "\"$jarvisWhatsAppHost\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = false
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        targetSdk = 36
    }
    lint {
        targetSdk = 36
    }
}

dependencies {
    implementation(project(":core:tools"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.core)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
}
