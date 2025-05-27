# SimpleReader

SimpleReader is an EPUB and PDF reader written in Java and Kotlin for Android.

### Features

- [x] Custom Fonts
- [x] Custom Text Size
- [x] PDF support
- [x] Bookmarks

#### Coming Soon...
- [ ] Book Search
- [ ] In-App Dictionary
- [ ] Text Highlighting
- [ ] List / Edit / Delete Highlights

### Gradle

First clone the folioreader library to your project:

```
git clone -b module --single-branch https://github.com/Yahoo-Mike/FolioReader-Android.git libs/simplereader
```

Add following dependency to your project `settings.gradle.kts` file:

```groovy
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
If the Readium libraries insist on being desugared, then in the :app gradle.build.kts:
```groovy
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
   }
}

dependencies {
  coreLibraryDesugaring( "com.android.tools:desugar_jdk_libs:2.1.5" )
}
```


### Usage

Get singleton object of `FolioReader`:

```java
FolioReader folioReader = FolioReader.get();
```
or for kotlin
```kotlin
val folioReader = FolioReader.get()
```

Call the function `openBook()`:

##### opening book from assets -

```java
folioReader.openBook("/data/user/0/com.myapp/files/illiad.epub");
```


### Credits
* SimpleReader is based on a fork of the old [FolioReader](https://github.com/FolioReader/FolioReader-Android) project.
* SimpleReader uses the [Readium](https://github.com/readium/kotlin-toolkit) modules to parse and render publications

#### Coming soon...
* <a href="http://developer.pearson.com/apis/dictionaries">Pearson Dictionaries</a>

## License
SimpleReader is available under the BSD license. See the [LICENSE](https://github.com/FolioReader/FolioReader-Android/blob/master/License.md) file.

