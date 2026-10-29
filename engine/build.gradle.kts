plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The ai.onnxruntime API: the app ships onnxruntime-android, the JVM tests run the desktop build
    compileOnly(libs.onnxruntime.jvm)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.onnxruntime.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "6g"

    // Model integration tests run only when CURIE_MODEL_DIR points at a downloaded q4 bundle
    val modelDir = providers.environmentVariable("CURIE_MODEL_DIR")
    if (modelDir.isPresent) {
        systemProperty("curie.modelDir", modelDir.get())
    }
    systemProperty("curie.loraDir", rootProject.file("app/src/main/assets/lora").absolutePath)
}
