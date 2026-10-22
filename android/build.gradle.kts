plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// El lint del AGP 8.7 falla por dentro al analizar código de Kotlin 2.4
// (IncompatibleClassChangeError en sus detectores). No revisa nada útil así, de
// modo que no se ejecuta al compilar la release. Reactivar al actualizar el AGP.
subprojects {
    plugins.withType<com.android.build.gradle.api.AndroidBasePlugin> {
        extensions.configure<com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>>("android") {
            lint { checkReleaseBuilds = false }
        }
    }
}
