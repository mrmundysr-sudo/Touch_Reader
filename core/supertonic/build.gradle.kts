plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val ort = "1.23.1"

dependencies {
    api(project(":core:audio"))
    // The Android app supplies onnxruntime-android (same ai.onnxruntime API); JVM tests use the desktop build.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ort")
    compileOnly("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ort")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    // Make the env-guarded real-model test re-run when a model directory is supplied.
    inputs.property("modelDir", System.getenv("SUPERTONIC_DIR") ?: "")
}
