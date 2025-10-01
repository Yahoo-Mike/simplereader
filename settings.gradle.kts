pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "simplereader"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }  // for readium
    }
}
