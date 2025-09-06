apply(from = "versions.gradle.kts")

val kotlinVersion: String by extra

val activityVersion: String by extra
val appcompatVersion: String by extra
val composeUiVersion: String by extra
val constraintLayoutVersion: String by extra
val coroutinesVersion: String by extra
val fragmentVersion: String by extra
val lifecycleVersion: String by extra
val materialVersion: String by extra
val recyclerviewVersion: String by extra
val roomVersion: String by extra

val gsonVersion: String by extra
val readiumVersion: String by extra
val retrofitVersion: String by extra

plugins {
    id("com.android.library") version "8.12.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

group = "com.simplereader"
version = "1.1.0"

android {
    namespace = "com.simplereader"
    compileSdk = 36

    defaultConfig {
        minSdk    =  28
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
            assets.srcDirs("src/main/assets")
        }
        getByName("test") {
            java.srcDirs("src/test/java")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true       // because I'm a masochist
//        lintConfig file("lint.xml")
    }

}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    //Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}")

    // core
    implementation("androidx.appcompat:appcompat:${appcompatVersion}")

    //constraint, recycler and material
    implementation("androidx.constraintlayout:constraintlayout:${constraintLayoutVersion}")
    implementation("androidx.recyclerview:recyclerview:${recyclerviewVersion}")
    implementation("com.google.android.material:material:${materialVersion}")

    // composeview
    implementation("androidx.compose.ui:ui:${composeUiVersion}")

    // activity and fragments
    implementation("androidx.activity:activity-ktx:${activityVersion}")
    implementation("androidx.fragment:fragment-ktx:${fragmentVersion}")

    // Lifecycle components
    // - ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${lifecycleVersion}")
    // - LiveData
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:${lifecycleVersion}")

    // Room runtime and Kotlin extensions
    implementation("androidx.room:room-runtime:${roomVersion}")
    implementation("androidx.room:room-ktx:${roomVersion}")

    // Annotation processor for Room
    ksp("androidx.room:room-compiler:${roomVersion}")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${coroutinesVersion}")

    // test environment
    testImplementation("junit:junit:4.13.2")

    //json
    implementation("com.google.code.gson:gson:${gsonVersion}")

    // readium kotlin toolkit
    implementation("org.readium.kotlin-toolkit:readium-shared:${readiumVersion}")
    implementation("org.readium.kotlin-toolkit:readium-streamer:${readiumVersion}")
    implementation("org.readium.kotlin-toolkit:readium-navigator:${readiumVersion}")
    implementation("org.readium.kotlin-toolkit:readium-adapter-pdfium:${readiumVersion}")

    // retrofit for calls to dictionaryapi.dev
    implementation("com.squareup.retrofit2:retrofit:${retrofitVersion}")
    implementation("com.squareup.retrofit2:converter-gson:${retrofitVersion}")

}

// setup JVM toolchain (for kotlin and java)
// see: https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-toolchains-support
// also see our settings.gradle to add toolchain resolver plugin
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
