import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

/** Las redes de Maia se copian de ml/models/maia/ (las genera ml/maia/export.py). */
abstract class CopyMaia @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory abstract val models: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() = files.sync {
        from(models) { include("*.tflite") }
        into(outputDir.dir("maia"))
    }
}

val copyMaia = tasks.register<CopyMaia>("copyMaia") {
    models.set(rootProject.file("../ml/models/maia"))
    outputDir.set(layout.buildDirectory.dir("generated/assets/maia"))
}

/** Fixtures de paridad con Python (fixtures-publicos/maia.json) para los tests instrumentados. */
abstract class CopyMaiaFixtures @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputFile abstract val fixture: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() = files.sync {
        from(fixture)
        into(outputDir)
    }
}

val copyMaiaFixtures = tasks.register<CopyMaiaFixtures>("copyMaiaFixtures") {
    fixture.set(rootProject.file("../fixtures-publicos/maia.json"))
    outputDir.set(layout.buildDirectory.dir("generated/assets/maia-fixtures"))
}

android {
    namespace = "com.pawneyes.maia"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "tflite" }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyMaia, CopyMaia::outputDir)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyMaiaFixtures, CopyMaiaFixtures::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:chess"))
    implementation(libs.litert)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
