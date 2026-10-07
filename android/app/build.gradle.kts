import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Read a git-ignored properties file from the android/ folder; absent values simply stay null. */
fun keyValues(name: String): Properties = Properties().apply {
    val source = rootProject.file(name)
    if (source.exists()) source.inputStream().use { load(it) }
}

// One file decides the version, so the APK, the docs and any release check cannot disagree.
val appVersion = keyValues("version.properties")

/** The four values a signed release needs. Absent together, never half-configured. */
data class SigningKey(val storeFile: String, val storePassword: String, val alias: String, val password: String)

// Signing material never enters the repository: it comes from android/keystore.properties locally
// or from environment variables in CI.
val keystoreFile = keyValues("keystore.properties")
val signingKey = SigningKey(
    System.getenv("RELEASE_STORE_FILE") ?: keystoreFile.getProperty("storeFile") ?: "",
    System.getenv("RELEASE_STORE_PASSWORD") ?: keystoreFile.getProperty("storePassword") ?: "",
    System.getenv("RELEASE_KEY_ALIAS") ?: keystoreFile.getProperty("keyAlias") ?: "",
    System.getenv("RELEASE_KEY_PASSWORD") ?: keystoreFile.getProperty("keyPassword") ?: "",
).takeIf { key -> listOf(key.storeFile, key.storePassword, key.alias, key.password).none(String::isBlank) }

android {
    namespace = "com.example.systemhealth"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.systemhealth"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion.getProperty("versionCode")?.toIntOrNull()
            ?: error("version.properties needs a numeric versionCode")
        versionName = appVersion.getProperty("versionName")
            ?: error("version.properties needs a versionName")
        buildConfigField("String", "API_BASE_URL", "\"https://apk-obeb.onrender.com\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        signingKey?.let { key ->
            create("release") {
                storeFile = file(key.storeFile)
                storePassword = key.storePassword
                keyAlias = key.alias
                keyPassword = key.password
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a key the release APK stays unsigned and installs nowhere, which is honest;
            // docs/ANDROID-BUILD.md explains how to create one.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:3000\"")
        }
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
