import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Stockfish no se versiona (100 MB): se descarga de la release oficial y se
// comprueba su huella. Se empaqueta como libstockfish.so porque Android solo
// permite ejecutar binarios desde la carpeta de librerías nativas de la app.
val stockfishUrl = "https://github.com/official-stockfish/Stockfish/releases/download/sf_19/" +
    "stockfish-android-arm64-universal.tar.gz"
val stockfishSha256 = "ebb24051aa4a222b4daaf049b882ecf1163d370c128fe02316602643f4d5e426"
val stockfishArchive = layout.buildDirectory.file("stockfish/stockfish-sf_19-android-arm64.tar.gz")
val stockfishJniLibs = layout.buildDirectory.dir("generated/jniLibs/stockfish")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

val downloadStockfish by tasks.registering {
    outputs.file(stockfishArchive)
    doLast {
        val file = stockfishArchive.get().asFile
        if (file.exists() && sha256(file) == stockfishSha256) return@doLast
        file.parentFile.mkdirs()
        logger.lifecycle("Descargando Stockfish de $stockfishUrl")
        URI(stockfishUrl).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        check(sha256(file) == stockfishSha256) { "La huella SHA-256 de Stockfish no coincide" }
    }
}

val extractStockfish by tasks.registering(Copy::class) {
    dependsOn(downloadStockfish)
    from(tarTree(resources.gzip(stockfishArchive))) {
        include("**/stockfish-android-arm64-universal")
        eachFile { path = "arm64-v8a/libstockfish.so" }
        includeEmptyDirs = false
    }
    into(stockfishJniLibs)
}

tasks.named("preBuild") { dependsOn(extractStockfish) }

android {
    namespace = "com.pawneyes.engine"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets { getByName("main").jniLibs.srcDir(stockfishJniLibs) }
    packaging {
        jniLibs {
            // Stockfish es un ejecutable, no una librería: que no se toque al empaquetar…
            keepDebugSymbols += "**/libstockfish.so"
            // …y que se extraiga a disco al instalar, para poder ejecutarlo.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:chess"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
