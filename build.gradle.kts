plugins {
    id("com.android.library") version "8.12.3"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("androidx.room")  version "2.8.4"
    id("com.google.devtools.ksp") version "2.2.20-2.0.3"
}

group = "com.simplereader"
version = "3.4.2"

android {
    namespace = "com.simplereader"
    compileSdk = 36

    defaultConfig {
        minSdk    =  28

        buildFeatures {
            buildConfig = true
        }
        defaultConfig {
            // access in code as:  com.simplereader.BuildConfig.SIMPLEREADER_VERSION
            buildConfigField(
                "String",
                "SIMPLEREADER_VERSION",
                "\"${project.version}\""
            )
        }
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
    val kotlinVersion = "2.2.21"

    val activityVersion = "1.12.1"
    val appcompatVersion = "1.7.1"
    val composeUiVersion = "1.10.0"
    val constraintLayoutVersion = "2.2.1"
    val coroutinesVersion = "1.10.2"
    val cryptoVersion = "1.1.0"
    val fragmentVersion = "1.8.9"
    val lifecycleVersion = "2.10.0"
    val materialVersion = "1.13.0"
    val recyclerviewVersion = "1.4.0"
    val roomVersion = "2.8.4"
    val workVersion= "2.11.0"

    val gsonVersion = "2.13.2"
    val readiumVersion = "3.1.2"
    val retrofitVersion = "3.0.0"

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    //Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}")

    // core
    implementation("androidx.appcompat:appcompat:${appcompatVersion}")

    // crypto (for passwords)
    implementation("androidx.security:security-crypto:${cryptoVersion}")

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

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:${workVersion}")

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

room {
    schemaDirectory("$projectDir/schemas")
}

// setup JVM toolchain (for kotlin and java)
// see: https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-toolchains-support
// also see our settings.gradle to add toolchain resolver plugin
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
