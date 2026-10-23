import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

/** El modelo se copia de ml/models/ en cada build: una sola fuente de verdad. */
abstract class CopyModel @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputFile abstract val model: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() = files.sync {
        from(model)
        into(outputDir)
    }
}

/**
 * Fixtures para los tests instrumentados (generados por ml/export/fixtures.py):
 * los públicos (sintéticos, en el repositorio) en publicos/ y, si existen, los
 * privados (de fotos propias, no publicados) en privados/.
 */
abstract class CollectFixtures @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory @get:Optional abstract val publicDir: DirectoryProperty
    @get:InputDirectory @get:Optional abstract val privateDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() = files.sync {
        if (publicDir.isPresent) from(publicDir) { into("publicos") }
        if (privateDir.isPresent) from(privateDir) { into("privados") }
        into(outputDir)
    }
}

val copyModel = tasks.register<CopyModel>("copyModel") {
    model.set(rootProject.file("../ml/models/square_net.tflite"))
    outputDir.set(layout.buildDirectory.dir("generated/assets/model"))
}

// Modelo rápido de español de Tesseract (tessdata_fast, Apache 2.0), versionado en ml/models/.
val copyTessdata = tasks.register<CopyModel>("copyTessdata") {
    model.set(rootProject.file("../ml/models/tessdata/spa.traineddata"))
    outputDir.set(layout.buildDirectory.dir("generated/assets/tessdata"))
}

val collectFixtures = tasks.register<CollectFixtures>("collectFixtures") {
    rootProject.file("../fixtures-publicos").takeIf { it.isDirectory }?.let { publicDir.set(it) }
    rootProject.file("../fixtures").takeIf { it.isDirectory }?.let { privateDir.set(it) }
    outputDir.set(layout.buildDirectory.dir("generated/assets/fixtures"))
}

android {
    namespace = "com.pawneyes.vision"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("tflite", "traineddata") }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyModel, CopyModel::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyTessdata, CopyModel::outputDir)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(collectFixtures, CollectFixtures::outputDir)
    }
}

dependencies {
    api(project(":core:chess"))
    implementation(libs.opencv)
    implementation(libs.litert)
    implementation(libs.tesseract4android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
