`android.support.multidex`
--------------------------

Library Project including compatibility multi dex loader.

This can be used by an Android project to install classloader
with multiple dex of applications running on API 4+.

### What's this?

This is just a fork of the original [multidex](https://android.googlesource.com/platform/frameworks/multidex/)
repo. I'll maintain this for a while since I needed this to be in a Maven
repo for easy consumption.

### What's it for again?

While dexing classes it is sometimes possible to exceed the maximum (65536) methods
limit (try using Google Play Services and Scaloid for instance):

```
trouble writing output: Too many method references: 70820; max is 65536.
You may try using --multi-dex option.
```

So the suggestion is to use the `--multi-dex` option of the `dx` utility; this
will generate several dex files (`classes.dex`, `classes2.dex`, etc.) that will
be included in the APK. To do so, add the following code to your app's `build.gradle` file:

```
afterEvaluate {
    tasks.matching {
        it.name.startsWith('dex')
    }.each { dx ->
        if (dx.additionalParameters == null) {
            dx.additionalParameters = []
        }
        dx.additionalParameters += '--multi-dex' // enable multidex
        
        // optional
        // dx.additionalParameters += "--main-dex-list=$projectDir/<filename>".toString() // enable the main-dex-list
    }
}
```

By default Dalvik's classloader will look for the `classes.dex` file only, so
it's necessary to patch it so that it can read from multiple dex files. That's
what this project provides.

If you are unlucky enough, the multidex classes will not be included in the
`classes.dex` file (the first one read by the classloader), which in turn
will render all this useless. There's a workaround for this though. Create a file
with this content and a arbitrary name:

```
android/support/multidex/BuildConfig.class
android/support/multidex/MultiDex$V14.class
android/support/multidex/MultiDex$V19.class
android/support/multidex/MultiDex$V4.class
android/support/multidex/MultiDex.class
android/support/multidex/MultiDexApplication.class
android/support/multidex/MultiDexExtractor$1.class
android/support/multidex/MultiDexExtractor.class
android/support/multidex/ZipUtil$CentralDirectory.class
android/support/multidex/ZipUtil.class
```

And pass the path of this file to the `--main-dex-list` option of the `dx` utility. Just uncomment the example from above accordingly by enabling one more item to the list of strings exposed by the `additionalParameters` property.
The `<filename>` is the arbitrary choosed above.

### Usage

Add this project to your classpath:

```groovy
repositories {
  jcenter()
}

dependencies {
  compile 'com.google.android:multidex:0.1'
}
```

Then you have 3 possibilities:

- Declare `android.support.multidex.MultiDexApplication` as the application in
your `AndroidManifest.xml`
- Have your `Application` extends `android.support.multidex.MultiDexApplication`, or...
- Have your `Application` override `attachBaseContext` starting with:

```java
import android.support.multidex.MultiDex;

// ...

@Override
protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    MultiDex.install(this);
}
```

### `build.gradle` example

```groovy
buildscript {
    repositories {
        jcenter()

        maven {
            url 'http://saturday06.github.io/gradle-android-scala-plugin/repository/snapshot'
        }
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:0.12.2'
        classpath 'jp.leafytree.gradle:gradle-android-scala-plugin:1.0-SNAPSHOT'
    }
}

apply plugin: 'com.android.application'
apply plugin: 'android-scala'

repositories {
    jcenter()
}

android {
    compileSdkVersion 19
    buildToolsVersion '20' // tested on 19.x family too

    defaultConfig {
        applicationId 'some.app'
        minSdkVersion 19
        targetSdkVersion 19
        versionCode 1
        versionName '1.0'
    }
}

dependencies {
    compile 'com.google.android:multidex:0.1'
    compile 'com.android.support:support-v4:19.0.1'
    compile 'com.google.android.gms:play-services:5.0.77'
    compile 'org.scala-lang:scala-library:2.11.2'
    compile 'org.scaloid:scaloid_2.11:3.4-10'
}
```

### Cautions

If you extends the `MultiDexApplication` or override the method `attachBaseContext`, you need to remember:

- The static fields in your application class will be loaded before the `MultiDex#install`be called! So the suggestion is to avoid static fields with types that can be placed out of main classes.dex file.
- The methods of your application may not have access to other classes that are loaded after your application class. As workarround for this, you can create another class (any class, in the example above, I use Runnable) and execute the method content inside it. Example:
```java
    @Override
    public void onCreate() {
        super.onCreate();
        
        final Context mContext = this;
        new Runnable() {

            @Override
            public void run() {
                // put your logic here!
                // use the mContext instead of this here
            }
        }.run();
    }
```

### Common problems

If you catch this error:
```
Error:Execution failed for task ':app:dexDebug'.
> com.android.ide.common.internal.LoggedErrorException: Failed to run command:
  	$ANDROID_SDK/build-tools/android-4.4W/dx --dex --num-threads=4 --multi-dex
  	...
  Error Code:
  	2
  Output:
  	UNEXPECTED TOP-LEVEL EXCEPTION:
  	com.android.dex.DexException: Library dex files are not supported in multi-dex mode
  		at com.android.dx.command.dexer.Main.runMultiDex(Main.java:322)
  		at com.android.dx.command.dexer.Main.run(Main.java:228)
  		at com.android.dx.command.dexer.Main.main(Main.java:199)
  		at com.android.dx.command.Main.main(Main.java:103)
```

The `--multi-dex` option to `dx` is incompatible with pre-dexing library projects. So if your app uses library projects, you need to disable pre-dexing before you can use --multi-dex:
````groovy
android {
    // ...
    dexOptions {
        preDexLibraries = false
    }
}
