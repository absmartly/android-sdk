# A/B Smartly Android SDK

A/B Smartly - Android SDK

## Compatibility

The A/B Smartly Android SDK is compatible with Android 5 and later (API level 21+).
It uses [A/B Smartly - Java SDK.](https://github.com/absmartly/java-sdk)

The `android.permission.INTERNET` permission is required. To add this permission to your application ensure the following line is present in the `AndroidManifest.xml` file:
```xml
    <uses-permission android:name="android.permission.INTERNET"/>
```

If you target Android 6.0 or earlier, a few extra steps are outlined below for installation and initialization.

## Installation

#### Gradle

To install the ABSmartly Android SDK, place the following in your `build.gradle` and replace {VERSION} with the latest Android SDK version available in MavenCentral.

```gradle
dependencies {
  implementation 'com.absmartly.sdk:android-sdk:{VERSION}'
}
```

#### Android 6.0 or earlier
When targeting Android 6.0 or earlier, the default Java Security Provider will not work. Using [Conscrypt](https://github.com/google/conscrypt) is recommended. Follow these [instructions](https://github.com/google/conscrypt/blob/master/README.md) to install it as dependency.

## Usage

The usage follows mainly the A/B Smartly Java SDK, but some Android components was created to help integrate faster with out Java SDK in Android Applications.

## Sqllite Local Cache Implementation

The usage of sqllite in Android Application is differente from Java Standard Applications then a specific implementation for Android is provided for this SDK. 

[SqliteAndroidLocalCache.java](https://github.com/absmartly/android-sdk/blob/main/android-sdk/src/main/java/com/absmartly/android/sdk/cache/SqliteAndroidLocalCache.java)

## Memory Local Cache Implementation

The Memory Cache component provide by [A/B Smartly Java SDK](https://github.com/absmartly/java-sdk) is compatible to be used in Android Applications if needed. 

## A/B Smartly Java SDK Usage

All details about how to use the [A/B Smartly Java SDK](https://github.com/absmartly/java-sdk) is in the java-sdk repository. 

## About A/B Smartly
**A/B Smartly** is the leading provider of state-of-the-art, on-premises, full-stack experimentation platforms for engineering and product teams that want to confidently deploy features as fast as they can develop them.
A/B Smartly's real-time analytics helps engineering and product teams ensure that new features will improve the customer experience without breaking or degrading performance and/or business metrics.

### Have a look at our growing list of clients and SDKs:
- [Java SDK](https://www.github.com/absmartly/java-sdk)
- [JavaScript SDK](https://www.github.com/absmartly/javascript-sdk)
- [PHP SDK](https://www.github.com/absmartly/php-sdk)
- [Swift SDK](https://www.github.com/absmartly/swift-sdk)
- [Vue2 SDK](https://www.github.com/absmartly/vue2-sdk)
- [Android SDK](https://www.github.com/absmartly/android-sdk)
