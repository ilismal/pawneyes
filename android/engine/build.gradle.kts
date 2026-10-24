import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

/**
 * Stockfish 16 compilado aquí con el NDK, para arm64 genérico (cualquier móvil
 * ARM64) y alineado a páginas de 16 KB. Se empaqueta como libstockfish.so porque
 * Android solo permite ejecutar binarios desde la carpeta de librerías de la app.
 *
 * La red neuronal (40 MB) no va dentro del binario (NNUE_EMBEDDING_OFF) sino como
 * asset, y el motor la carga con EvalFile. Es la de Stockfish 16: las versiones
 * posteriores usan redes de más de 100 MB, que eran casi toda la APK (#12), y para
 * analizar posiciones de un libro la 16 sobra.
 *
 * Fuentes y red se descargan y se comprueban por su huella SHA-256 (el nombre de
 * la red ya es el principio de la suya).
 */
abstract class BuildStockfish @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>
    @get:Input abstract val sourceSha256: Property<String>
    @get:Input abstract val netName: Property<String>
    @get:Input abstract val netSha256: Property<String>
    @get:Input abstract val ndkVersion: Property<String>
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun download(url: String, sha256: String, file: File) {
        if (file.exists() && digest(file) == sha256) return
        file.parentFile.mkdirs()
        logger.lifecycle("Descargando $url")
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        check(digest(file) == sha256) { "La huella SHA-256 de ${file.name} no coincide" }
    }

    @TaskAction
    fun build() {
        val work = workDir.get().asFile
        val tgz = File(work, "stockfish-src.tar.gz")
        val net = File(work, netName.get())
        download(sourceUrl.get(), sourceSha256.get(), tgz)
        download("https://tests.stockfishchess.org/api/nn/${netName.get()}", netSha256.get(), net)

        val srcRoot = File(work, "src")
        files.sync {
            from(archives.tarTree(archives.gzip(tgz)))
            into(srcRoot)
        }
        val src = srcRoot.listFiles()!!.single { it.isDirectory }.resolve("src")
        // El Makefile comprueba la red y, si ya está, no la descarga.
        net.copyTo(File(src, net.name), overwrite = true)

        val os = System.getProperty("os.name").lowercase()
        val host = if ("mac" in os) "darwin-x86_64" else "linux-x86_64"
        val bin = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/$host/bin")
        check(bin.isDirectory) { "No está el NDK ${ndkVersion.get()} (${bin.path})" }
        exec.exec {
            workingDir = src
            environment("PATH", bin.path + File.pathSeparator + System.getenv("PATH"))
            commandLine(
                "make", "-j${Runtime.getRuntime().availableProcessors()}", "build",
                "ARCH=armv8", "COMP=ndk",
                "KERNEL=Linux",  // si no, en un Mac el Makefile añade opciones de macOS
                // Si no, el Makefile toma el commit de git del repositorio de la app (no el de
                // Stockfish) y el binario cambiaría con cada commit nuestro.
                "GIT_SHA=", "GIT_DATE=",
                "EXTRACXXFLAGS=-DNNUE_EMBEDDING_OFF",
                "EXTRALDFLAGS=-Wl,-z,max-page-size=16384",
            )
        }
        val out = jniLibsDir.get().asFile.resolve("arm64-v8a/libstockfish.so")
        out.parentFile.mkdirs()
        exec.exec { commandLine(bin.resolve("llvm-strip").path, "-o", out.path, File(src, "stockfish").path) }
        files.sync {
            from(net)
            into(assetsDir)
        }
    }
}

val buildStockfish = tasks.register<BuildStockfish>("buildStockfish") {
    sourceUrl.set("https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_16.tar.gz")
    sourceSha256.set("a1600ebdaf4e324ba3e10cec2e0c9a810dc64c6f0db5cc955b2fd5e1eefa1cc6")
    netName.set("nn-5af11540bbfe.nnue")
    netSha256.set("5af11540bbfefcb54e38c5dd000cab4b469dfa7599a1d55be5d2722c20a8929b")
    ndkVersion.set(libs.versions.ndk)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    workDir.set(layout.buildDirectory.dir("stockfish"))
    jniLibsDir.set(layout.buildDirectory.dir("generated/jniLibs/stockfish"))
    assetsDir.set(layout.buildDirectory.dir("generated/assets/stockfish"))
}

android {
    namespace = "com.pawneyes.engine"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()
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
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildStockfish, BuildStockfish::jniLibsDir)
        variant.sources.assets?.addGeneratedSourceDirectory(buildStockfish, BuildStockfish::assetsDir)
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

