plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Branch L (jarvis/l-app-atlas): App Atlas — an on-device index of how to
// operate every launchable app (deep links, shortcuts, in-app search, UI
// vocabulary, learned element paths). Everything stays on the device
// (database under noBackupFilesDir); nothing about installed apps is ever
// committed or sent anywhere.

// Branch N: export the Room schema (playbook tables, migration test reads it).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "io.androllm.core.appatlas"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(project(":core:accessibility"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    // Branch U it.2: AppOpenActionTool exclusion test (mocked repository / context).
    testImplementation(libs.mockk)
    // Real XmlPullParser for the manifest / shortcut XML parser tests.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // Branch N: runs the Room migration SQL against a real SQLite in AtlasMigrationTest.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
