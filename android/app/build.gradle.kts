import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pawneyes.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.pawneyes.app"
        minSdk = 26
        targetSdk = 35
        // En CI se pasan con -PversionCode=… -PversionName=… (ver .github/workflows/release.yml).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1"
        // Solo ARM64: es la única arquitectura para la que se incluye Stockfish, y la
        // de casi todos los móviles actuales (y del emulador en Mac con Apple Silicon).
        ndk { abiFilters += "arm64-v8a" }
    }
    // Clave de firma local (no se versiona): android/release.jks + android/keystore.properties.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildFeatures { resValues = true }  // app_name distinto para la versión de depuración
    buildTypes {
        debug {
            // Así la versión de depuración convive con la release en el mismo móvil.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Pawneyes debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libstockfish.so"
            // Las librerías nativas se extraen a disco al instalar: Stockfish se ejecuta desde ahí.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":feature:board"))
    implementation(project(":vision"))
    implementation(project(":feature:camera"))
    implementation(project(":engine"))
    implementation(project(":maia"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
