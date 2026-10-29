import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// key.properties (never committed) names the keystores and their credentials:
//   signingKey=<app signing keystore>  signs the APKs
//   uploadKey=<upload keystore>        signs the Play bundle (-Pcurie.uploadKey=true)
//   storePassword=...  keyPassword=...  keyAlias=...
val keyProperties = Properties()
val keyPropertiesFile = rootProject.file("key.properties")
if (keyPropertiesFile.exists()) {
    keyPropertiesFile.inputStream().use { stream -> keyProperties.load(stream) }
}
val hasSigningKeys = keyProperties.getProperty("signingKey") != null
val signWithUploadKey = providers.gradleProperty("curie.uploadKey").orNull.toBoolean()

// AGP refuses to build a bundle while ABI splits are on (issuetracker 402800800); Play splits bundles by ABI itself
val requestedTasks = gradle.startParameter.taskNames
val buildingBundle = requestedTasks.any { task -> task.contains("bundle", ignoreCase = true) }

// The version code counts the commits, so every CI build of main is installable over the last one
val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { output -> output.trim().toIntOrNull() ?: 1 }

android {
    namespace = "com.danemadsen.curie"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.danemadsen.curie"
        minSdk = 29
        targetSdk = 36
        versionCode = commitCount.get()
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningKeys) {
            create("release") {
                storeFile = rootProject.file(keyProperties.getProperty("signingKey"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
            create("upload") {
                storeFile = rootProject.file(keyProperties.getProperty("uploadKey"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (hasSigningKeys) {
                val signingConfigName = if (signWithUploadKey) "upload" else "release"
                signingConfig = signingConfigs.getByName(signingConfigName)
            }
        }
    }

    // A 3 GB model needs a 64-bit process: no 32-bit ABIs in any APK or bundle
    packaging {
        jniLibs {
            excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**")
        }
    }
    splits {
        abi {
            isEnable = !buildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Stored uncompressed so installing them into the model folder is a plain copy
    androidResources {
        noCompress += listOf("onnx", "onnx_adapter")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.onnxruntime.android)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.materialkolor)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
