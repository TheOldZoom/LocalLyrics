plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun gitValue(vararg arguments: String): String? = runCatching {
    val process = ProcessBuilder("git", *arguments)
        .directory(rootDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output else null
}.getOrNull()
val releaseTag = gitValue("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
    ?.removePrefix("v") ?: "0.1.0"
// Every push has a distinct APK and must have a higher version code than the
// previous push. CI fetches full Git history so this count is stable there.
val releaseVersionCode = gitValue("rev-list", "--count", "HEAD")?.toIntOrNull()
    ?.coerceAtLeast(1) ?: 1
val releaseStore = providers.environmentVariable("LYRICS_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("LYRICS_KEYSTORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("LYRICS_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LYRICS_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.music.lyricsapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.music.lyricsapp"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = "$releaseTag-build.$releaseVersionCode"
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(requireNotNull(releaseStore))
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":lyricsCore"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
