plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mundymorning.touchreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mundymorning.touchreader"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core:document"))
    implementation(project(":core:reader"))
    implementation(project(":core:tts"))
    implementation(project(":core:voicepacks"))
    implementation(project(":core:audio"))
    implementation(project(":core:supertonic"))
    implementation(project(":core:voicestudio"))
    implementation(project(":core:export"))
    implementation(project(":core:pockettts"))
    implementation(project(":core:art"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.1")
    implementation("org.json:json:20240303") { because("none: platform org.json is used at runtime") }

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.media:media:1.7.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // PDF text extraction (Apache-2.0).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation(kotlin("test"))
}

// Artwork: copies the pictures in art/<page>/<slot>.png|webp into the app as art_<slot> (see docs/ASSETS.md). Only files named after a
// slot in art/slots.json are copied, so a stray or misnamed file can never break the build; slots without a picture use the built-in look.
val syncArt = tasks.register<Copy>("syncArt") {
    val known = Regex("\"id\"\\s*:\\s*\"([a-z][a-z0-9_]*)\"").findAll(rootProject.file("art/slots.json").readText()).map { it.groupValues[1] }.toSet()
    from(rootProject.file("art")) {
        include("**/*.png", "**/*.webp")
        eachFile {
            val stem = name.substringBeforeLast('.')
            if (stem in known && name == name.lowercase()) path = "art_$name" else exclude()
        }
    }
    includeEmptyDirs = false
    into(layout.projectDirectory.dir("src/main/res/drawable-nodpi"))
}
tasks.named("preBuild") { dependsOn(syncArt) }
