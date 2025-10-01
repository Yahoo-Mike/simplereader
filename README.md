# SimpleReader

SimpleReader is an EPUB and PDF reader written in Java and Kotlin for Android.

### Features

- [x] Custom fonts & font size
- [x] PDF support
- [x] Bookmarks
- [x] Search
- [x] Highlighting
- [x] Dictionary (online only)

### Gradle

First clone the simplereader library to your project:

```
mkdir libs/simplereader
cd libs/simplereader
git clone https://github.com/Yahoo-Mike/simplereader.git .
```

Add following dependency and jitpack.io repo to your project `settings.gradle.kts` file.  Jitpack.io
is required for the latest version of Readium to compile (3.1.2):

```groovy
dependencyResolutionManagement {
    repositories {
        ...
        maven { url = uri("https://jitpack.io") }
    }
}

includeBuild("libs/simplereader")
```

Add following dependency to your app module `build.gradle` file:

```groovy
dependencies {
    ...
    implementation("com.simplereader:simplereader:1.0.0")
    ...
}
```
### Java 17 support
This library uses Java version 17, so make sure all of the *compileOptions* and *kotlinOptions* in your app's **build.gradle.kts** are set to version 17, not version 11.
If you forget to do this, then you might get an error message like this:
```
Invalid build configuration. Attempt to create a global synthetic for 'Record desugaring' without a global-synthetics consumer.
```
### Desugaring
If the Readium libraries insist on being desugared, then in the :app build.gradle.kts:
```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
   }
}

dependencies {
  coreLibraryDesugaring( "com.android.tools:desugar_jdk_libs:2.1.5" )
}
```
or in the :app build.gradle (groovy):
```groovy
android {
    compileOptions {
        coreLibraryDesugaringEnabled true
   }
}

dependencies {
  coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.1.5"
}
```

### Usage

Get singleton object of `SimpleReader`:

```java
SimpleReader reader = SimpleReader.getInstance();
```
or for kotlin
```kotlin
val reader = SimpleReader.getInstance()
```
#### API
*openBook()*
To open a book, call with the fully-qualified filename.
Only *.epub abd *.pdf are supported.

```kotlin
reader.openBook("/data/user/0/com.myapp/files/illiad.epub")
```

*serverSettings()*
Displays a bottom sheet for entering the server settings.
If user successfully changes the settings, a full sync will be kicked off
when user dismisses dialog or presses "Sync Now".
Host should call like this from a Fragment child: 
```kotlin
reader.serverSettings(parentFragmentManager)
```
Or like this from an AppCompatActivity child: 
```kotlin
reader.serverSettings(requireActivity().supportFragmentManager)
```


### Credits
* SimpleReader is based on a fork of the old [FolioReader](https://github.com/FolioReader/FolioReader-Android) project.
* SimpleReader uses the [Readium](https://github.com/readium/kotlin-toolkit) modules to parse and render publications
* SimpleReader uses dictionaryapi.dev for dictionary definitions

## License
SimpleReader is available under the BSD license. See the [LICENSE](https://github.com/FolioReader/FolioReader-Android/blob/master/License.md) file.

