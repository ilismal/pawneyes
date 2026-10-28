pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android solo se publica en JitPack: el repositorio se limita a ese paquete.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "pawneyes"
include(":app", ":core:chess", ":feature:board", ":feature:camera", ":vision", ":engine", ":maia")

// Versiones mínimas de dependencias transitivas con vulnerabilidades conocidas (avisos de
// Dependabot). Se aplican a la compilación (plugin de Android, lint…) y a los módulos, pero
// solo suben la versión: si algo pide una más nueva, se queda la suya.
val minimumVersions = mapOf(
    "com.google.guava:guava" to "33.7.2",  // la única que va en la APK (la trae CameraX)
    "org.bouncycastle:bcprov-jdk18on" to "1.85",
    "org.bouncycastle:bcpkix-jdk18on" to "1.85",
    "org.bouncycastle:bcutil-jdk18on" to "1.85",
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.jdom:jdom2" to "2.0.6.1",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.httpcomponents:httpclient" to "4.5.13",  // ya resolvía 4.5.14; lo pedido era 4.5.6
)

fun versionParts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

fun older(a: String, b: String): Boolean {
    val (x, y) = versionParts(a) to versionParts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d < 0
    }
    return false
}

fun raise(d: DependencyResolveDetails, guava: String) {
    val min = minimumVersions["${d.requested.group}:${d.requested.name}"] ?: return
    val requested = d.requested.version ?: return
    if (!older(requested, min)) return
    // Guava publica cada versión en dos variantes; si no se fija una, ganaría -jre (va después
    // en el orden de versiones) también en la APK. -android en los módulos, -jre al compilar.
    val suffix = if (d.requested.name == "guava") "-$guava" else ""
    d.useVersion(min + suffix)
    d.because("vulnerabilidad conocida en versiones anteriores a $min")
}

gradle.allprojects {
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency { raise(this, "jre") } }
    configurations.configureEach { resolutionStrategy.eachDependency { raise(this, "android") } }
}
