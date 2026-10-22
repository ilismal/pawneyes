import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

/**
 * Stockfish no se versiona (100 MB): se descarga de la release oficial, se
 * comprueba su huella y se empaqueta como libstockfish.so, porque Android solo
 * permite ejecutar binarios desde la carpeta de librerías nativas de la app.
 */
abstract class FetchStockfish @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Internal abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @TaskAction
    fun fetch() {
        val tgz = archive.get().asFile
        if (!tgz.exists() || digest(tgz) != sha256.get()) {
            tgz.parentFile.mkdirs()
            logger.lifecycle("Descargando Stockfish de ${url.get()}")
            URI(url.get()).toURL().openStream().use { input -> tgz.outputStream().use { input.copyTo(it) } }
            check(digest(tgz) == sha256.get()) { "La huella SHA-256 de Stockfish no coincide" }
        }
        files.sync {
            from(archives.tarTree(archives.gzip(tgz))) {
                include("**/stockfish-android-arm64-universal")
                eachFile { path = "arm64-v8a/libstockfish.so" }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
    }
}

val fetchStockfish = tasks.register<FetchStockfish>("fetchStockfish") {
    url.set("https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-arm64-universal.tar.gz")
    sha256.set("ebb24051aa4a222b4daaf049b882ecf1163d370c128fe02316602643f4d5e426")
    archive.set(layout.buildDirectory.file("stockfish/stockfish-sf_19-android-arm64.tar.gz"))
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs/stockfish"))
}

android {
    namespace = "com.pawneyes.engine"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            // Stockfish es un ejecutable, no una librería: que no se toque al empaquetar…
            keepDebugSymbols += "**/libstockfish.so"
            // …y que se extraiga a disco al instalar, para poder ejecutarlo.
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchStockfish, FetchStockfish::outputDir)
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
