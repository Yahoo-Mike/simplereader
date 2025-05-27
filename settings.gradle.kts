pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }

    plugins {
        // required for JVM toolchain
        // see: https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-toolchains-support
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
    }
}

rootProject.name = "simplereader"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
