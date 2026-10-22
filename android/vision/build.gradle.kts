import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// El modelo se copia de ml/models/ en cada build: una sola fuente de verdad.
val modelAssets = layout.buildDirectory.dir("generated/assets/model")
val copyModel by tasks.registering(Copy::class) {
    from(rootProject.file("../ml/models/square_net.tflite"))
    into(modelAssets)
}

android {
    namespace = "com.pawneyes.vision"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main").assets.srcDir(modelAssets)
        // Los tests instrumentados leen los fixtures generados por ml/export/fixtures.py.
        getByName("androidTest").assets.srcDir(rootProject.file("../fixtures"))
    }
    androidResources { noCompress += "tflite" }
}

tasks.named("preBuild") { dependsOn(copyModel) }

dependencies {
    api(project(":core:chess"))
    implementation(libs.opencv)
    implementation(libs.litert)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
