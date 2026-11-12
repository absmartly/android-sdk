# ABsmartly Android SDK

A/B Smartly - Android SDK

## Architecture

The A/B Smartly Android SDK is a **thin wrapper** around the [A/B Smartly Java SDK](https://github.com/absmartly/java-sdk). All core functionality -- including experiment evaluation, variant assignment, context management, event tracking, and HTTP client operations -- is delegated to the Java SDK.

The Android SDK provides Android-specific components:
- **SqliteAndroidLocalCache**: An Android-optimized local cache implementation using SQLite for persisting context data and publish events
- Android-specific dependency management, ProGuard/R8 consumer rules, and configuration

For the full core API reference, please refer to the [Java SDK documentation](https://github.com/absmartly/java-sdk).

## Compatibility

The A/B Smartly Android SDK is compatible with Android 5.0 and later (API level 21+).

The `android.permission.INTERNET` permission is required. To add this permission to your application, ensure the following line is present in the `AndroidManifest.xml` file:
```xml
<uses-permission android:name="android.permission.INTERNET"/>
```

If you target Android 6.0 or earlier, a few extra steps are outlined below for installation and initialization.

## Installation

#### Gradle

To install the ABsmartly Android SDK, place the following in your `build.gradle` and replace `{VERSION}` with the latest SDK version available in MavenCentral.

```gradle
dependencies {
    implementation 'com.absmartly.sdk:android-sdk:{VERSION}'
}
```

#### Android 6.0 or earlier

When targeting Android 6.0 or earlier, the default Java Security Provider will not work. Using [Conscrypt](https://github.com/google/conscrypt) is recommended. Follow these [instructions](https://github.com/google/conscrypt/blob/master/README.md) to install it as a dependency.

#### ProGuard / R8 Rules

The Android SDK ships with consumer ProGuard rules that are automatically applied when building your release APK. These rules ensure the JSON model classes used for serialization/deserialization are preserved. If you encounter issues, verify the following rules are active:

```proguard
-keep class com.absmartly.sdk.json.** { *; }
-keep class com.fasterxml.jackson.** { *; }
```

## Getting Started

Please follow the [installation](#installation) instructions before trying the following code.

### Initialization

This example assumes an API Key, an Application, and an Environment have been created in the A/B Smartly web console.

#### Recommended: ABSmartlyAndroid Wrapper (Android Entry Point)

`ABSmartlyAndroid` is the recommended Android entry point. It creates the Java SDK and a `SqliteAndroidLocalCache` in a single step, so you don't need to configure each component separately. The cache is not used by the SDK automatically; access it with `getCache()`.

```java
import com.absmartly.android.sdk.ABSmartlyAndroid;

final ABSmartlyAndroid sdk = ABSmartlyAndroid.builder()
    .endpoint("https://your-company.absmartly.io/v1")
    .apiKey("YOUR-API-KEY")
    .application("android-app")
    .environment("production")
    .applicationVersion(BuildConfig.VERSION_NAME) // optional, e.g. "2.4.1"
    .context(getApplicationContext())
    .build();
```

Or using the static factory method:

```java
final ABSmartlyAndroid sdk = ABSmartlyAndroid.create(
    "https://your-company.absmartly.io/v1",
    "YOUR-API-KEY",
    "android-app",
    "production",
    getApplicationContext()
);
```

`ABSmartlyAndroid` delegates `createContext` and `createContextWith` to the underlying Java SDK, closes both the Java SDK and the cache in `close()`, and exposes `getCache()` to access the `SqliteAndroidLocalCache` directly. Call `getSdk()` to access the underlying `ABSmartly` Java SDK instance for advanced use cases.

`applicationVersion` is optional. It becomes the `app_version` [system attribute](#system-attributes), which audience filters and assignment rules can compare with the [semver operators](#semver-operators). It is also sent as the `X-Application-Version` header when it is a whole number such as `42`; any other version, such as `2.4.1`, is sent as `0` there because the collector only accepts whole numbers.

`ABSmartlyAndroid` identifies itself to the collector with `X-Agent: absmartly-android-sdk/<version>` (`ABSmartlyAndroid.AGENT`), so the backend can tell which SDK and version sent events. Contexts created with the Java SDK directly send the Java SDK's agent, `absmartly-java-sdk/<version>`, instead.

#### Advanced Configuration (Java SDK directly)

For advanced use cases where you need full control over the Client and configuration:

```java
import com.absmartly.sdk.*;

final ClientConfig clientConfig = ClientConfig.create()
    .setEndpoint("https://your-company.absmartly.io/v1")
    .setAPIKey("YOUR-API-KEY")
    .setApplication("android-app")
    .setEnvironment("production");

final Client absmartlyClient = Client.create(clientConfig);

final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
    .setClient(absmartlyClient);

final ABSmartly sdk = ABSmartly.create(sdkConfig);
```

#### Initializing with SqliteAndroidLocalCache manually

If you are using the Java SDK directly, you can create the SQLite cache alongside it. `ABSmartlyConfig` has no cache setting, so the cache is not used by the SDK automatically: the `ContextDataProvider` and `ContextEventHandler` are still the defaults created from the `Client`. Read and write the cache yourself (see [SqliteAndroidLocalCache](#sqliteandroidlocalcache)), or plug it into your own `ContextDataProvider`/`ContextEventHandler` implementations via `setContextDataProvider()`/`setContextEventHandler()`.

```java
import com.absmartly.sdk.*;
import com.absmartly.android.sdk.cache.SqliteAndroidLocalCache;

final SqliteAndroidLocalCache cache = new SqliteAndroidLocalCache(getApplicationContext());

final ClientConfig clientConfig = ClientConfig.create()
    .setEndpoint("https://your-company.absmartly.io/v1")
    .setAPIKey("YOUR-API-KEY")
    .setApplication("android-app")
    .setEnvironment("production");

final Client absmartlyClient = Client.create(clientConfig);

final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
    .setClient(absmartlyClient);

final ABSmartly sdk = ABSmartly.create(sdkConfig);

// When you are done with the SDK, close both. ABSmartly.close() closes the Client;
// the cache is a SQLiteOpenHelper and must be closed separately. Close the cache in a
// finally block so it is released even if sdk.close() throws.
try {
    sdk.close();
} finally {
    cache.close();
}
```

`ABSmartlyAndroid` creates the Java SDK and the cache together and closes both in its `close()` (the cache is closed even if closing the Java SDK fails). It does not plug the cache into the SDK either: the SDK still uses its default `ContextDataProvider` and `ContextEventHandler`, so use `getCache()` to read and write cached data yourself.

#### Android 6.0 or earlier

When targeting Android 6.0 or earlier, set the Java Security Provider for SSL to *Conscrypt*. With `ABSmartlyAndroid`, pass it to the builder:

```java
import org.conscrypt.Conscrypt;

final ABSmartlyAndroid sdk = ABSmartlyAndroid.builder()
    .endpoint("https://your-company.absmartly.io/v1")
    .apiKey("YOUR-API-KEY")
    .application("android-app")
    .environment("production")
    .securityProvider(Conscrypt.newProvider())
    .context(getApplicationContext())
    .build();
```

With the Java SDK directly, create the *Client* instance as follows:

```java
import com.absmartly.sdk.*;
import org.conscrypt.Conscrypt;

final ClientConfig clientConfig = ClientConfig.create()
    .setEndpoint("https://your-company.absmartly.io/v1")
    .setAPIKey("YOUR-API-KEY")
    .setApplication("android-app")
    .setEnvironment("production");

final DefaultHTTPClientConfig httpClientConfig = DefaultHTTPClientConfig.create()
    .setSecurityProvider(Conscrypt.newProvider());

final DefaultHTTPClient httpClient = DefaultHTTPClient.create(httpClientConfig);

final Client absmartlyClient = Client.create(clientConfig, httpClient);

final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
    .setClient(absmartlyClient);

final ABSmartly sdk = ABSmartly.create(sdkConfig);
```

**SDK Options**

| Config                  | Type                              | Required? |   Default   | Description                                                                                                                                                                   |
| :---------------------- | :-------------------------------- | :-------: | :---------: | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| endpoint                | `String`                          |  &#9989;  | `null`      | The URL to your API endpoint. Most commonly `"https://your-company.absmartly.io/v1"`                                                                                          |
| apiKey                  | `String`                          |  &#9989;  | `null`      | Your API key, which can be found on the Web Console.                                                                                                                          |
| environment             | `String`                          |  &#9989;  | `null`      | The environment of the platform where the SDK is installed. Environments are created on the Web Console and should match the available environments in your infrastructure.   |
| application             | `String`                          |  &#9989;  | `null`      | The name of the application where the SDK is installed. Applications are created on the Web Console and should match the applications where your experiments will be running. |
| timeout                 | `int`                             |  &#10060; | `3000`      | HTTP connection timeout in milliseconds                                                                                                                                        |
| retries                 | `int`                             |  &#10060; | `5`         | Maximum number of retry attempts for failed HTTP requests                                                                                                                      |
| contextEventLogger      | `ContextEventLogger`              |  &#10060; | `null`      | Callback to handle SDK events (ready, exposure, goal, etc.)                                                                                                                   |
| contextDataProvider     | `ContextDataProvider`             |  &#10060; | auto        | Custom provider for context data (advanced usage)                                                                                                                             |
| contextEventHandler     | `ContextEventHandler`             |  &#10060; | auto        | Custom handler for publishing events (advanced usage)                                                                                                                         |
| variableParser          | `VariableParser`                  |  &#10060; | auto        | Custom parser for experiment variables (advanced usage)                                                                                                                       |
| audienceDeserializer    | `AudienceDeserializer`            |  &#10060; | auto        | Custom deserializer for audience data (advanced usage)                                                                                                                        |

## Creating a New Context

The examples in this README build maps with `HashMap` rather than `Map.of()`, which is a Java 9 API only available on Android 11 (API 30) and later, while this SDK supports API 21+.

### Synchronously

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("device_id", "device-unique-id");

final Context context = sdk.createContext(contextConfig)
    .waitUntilReady();
```

### Asynchronously

On Android, context creation must happen off the main thread. Use the asynchronous API to avoid blocking the UI:

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("device_id", "device-unique-id");

sdk.createContext(contextConfig)
    .waitUntilReadyAsync()
    .thenAccept(ctx -> {
        runOnUiThread(() -> {
            // safe to update UI here
        });
    });
```

### With Pre-fetched Data

Creating a context involves a round-trip to the A/B Smartly event collector. You can avoid repeating the round-trip by re-using data previously retrieved, for example from a server-side SDK:

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("device_id", "device-unique-id");

final Context context = sdk.createContextWith(contextConfig, prefetchedContextData);
assert(context.isReady()); // no need to wait
```

### Refreshing the Context with Fresh Experiment Data

For long-running contexts, the context is usually created once when the application starts. However, any experiments started after the context was created will not be triggered. To mitigate this, use `setRefreshInterval()` on the context config.

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("device_id", "device-unique-id")
    .setRefreshInterval(TimeUnit.HOURS.toMillis(4)); // every 4 hours
```

Alternatively, call `refresh()` manually:

```java
context.refresh();
```

### Setting Extra Units

You can add additional units to a context by calling `setUnit()` or `setUnits()`. For example, when a user logs in to your application, you may want to add a user-level unit to the context. Note that **you cannot override an already set unit type** as that would be a change of identity, and will throw an exception. In this case, you must create a new context instead.

```java
context.setUnit("db_user_id", "1000013");

final Map<String, String> units = new HashMap<>();
units.put("db_user_id", "1000013");
context.setUnits(units);
```

## Basic Usage

### Selecting a Treatment

```java
if (context.getTreatment("exp_test_experiment") == 0) {
    // user is in control group (variant 0)
} else {
    // user is in treatment group
}
```

### Treatment Variables

```java
final Object variable = context.getVariableValue("my_variable", "default");
```

### Peek at Treatment Variants

Although generally not recommended, it is sometimes necessary to peek at a treatment or variable without triggering an exposure. The SDK provides `peekTreatment()` for that purpose.

```java
if (context.peekTreatment("exp_test_experiment") == 0) {
    // user is in control group (variant 0)
} else {
    // user is in treatment group
}
```

#### Peeking at Variables

```java
final Object variable = context.peekVariableValue("my_variable", "default");
```

### Overriding Treatment Variants

During development, it is useful to force a treatment for an experiment. This can be achieved with `setOverride()` and/or `setOverrides()`. These methods can be called before the context is ready.

```java
context.setOverride("exp_test_experiment", 1);

final Map<String, Integer> overrides = new HashMap<>();
overrides.put("exp_test_experiment", 1);
overrides.put("exp_another_experiment", 0);
context.setOverrides(overrides);
```

## Advanced

### Context Attributes

Attributes can be set before the context is ready.

```java
context.setAttribute("user_agent", "Android/12");

final Map<String, Object> attributes = new HashMap<>();
attributes.put("customer_age", "new_customer");
context.setAttributes(attributes);
```

### System Attributes

With `setIncludeSystemAttributes(true)`, the context makes `application`, `environment` and `app_version` available to audience filters and assignment-rule conditions (an attribute you set yourself with the same name takes precedence), and prepends these attributes to every publish:

| Attribute     | Value                                                              |
|:--------------|:-------------------------------------------------------------------|
| `sdk_name`    | `absmartly-android-sdk`                                            |
| `sdk_version` | The Android SDK version (`ABSmartlyAndroid.SDK_VERSION`)           |
| `application` | The configured application                                         |
| `environment` | The configured environment                                         |
| `app_version` | The configured application version; left out when it is not set or `0` |

Their `setAt` is the time of the earliest exposure or goal in the publish. A system attribute is not sent when you set an attribute with the same name yourself. System attributes are disabled by default.

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")
    .setIncludeSystemAttributes(true);
```

### Assignment Rules

Experiments can carry ordered assignment rules, configured in the Web Console. The first rule whose conditions match the context's attributes (and whose environments, if any, include the configured environment) decides the variant, ahead of audience targeting, traffic allocation and custom assignments:

- an `assign` rule gives a fixed variant;
- a `split` rule randomizes the unit over the rule's own percentages.

Rule-assigned exposures are flagged `ruleOverride` so they can be excluded from analysis. `setOverride()` still wins over every rule. If the first matching rule's variant is out of range, or its split does not have one percentage per variant, normal assignment applies. Rule evaluation needs no SDK configuration; set the attributes the conditions refer to.

```java
context.setAttribute("country", "US");
final int treatment = context.getTreatment("exp_test_experiment"); // the matching rule's variant
```

### Semver Operators

Audience filters and rule conditions can compare versions with `semver_eq`, `semver_gt`, `semver_gte`, `semver_lt` and `semver_lte`, for example `app_version >= 2.4.0`. A leading `v` and build metadata are ignored, missing parts count as `0` (`1.2` equals `1.2.0`), and a pre-release ranks below its release (`2.4.0-beta.1 < 2.4.0`).

Comparison is permissive, matching the JavaScript and Java SDKs: a non-numeric part is compared as text and ranks above any number, so an `app_version` of `"unknown"` or `"1.x"` satisfies `semver_gte` against `"2.4.0"`. Only a missing value, a value that is not a string, number or boolean, or one with an empty version core (`""`, `"v"`, `"+build"`) never matches. Make sure `app_version` holds a real version when targeting on it.

### Tracking Goals

Goals are created in the A/B Smartly web console.

```java
final Map<String, Object> properties = new HashMap<>();
properties.put("item_count", 1);
properties.put("total_amount", 1999.99);
context.track("payment", properties);
```

### Publishing Pending Data

Sometimes it is necessary to ensure all events have been published to the A/B Smartly collector before proceeding. You can explicitly call `publish()` or `publishAsync()`.

```java
context.publish();
```

### Finalizing

The `close()` and `closeAsync()` methods will ensure all events have been published to the A/B Smartly collector, like `publish()`, and will also "seal" the context, throwing an error if any method that could generate an event is called.

`close()` blocks until pending events have been published, so only call it off the main thread. On the main thread (for example in `onDestroy()`), use `closeAsync()` and handle failures on the returned future:

```java
// Background thread
context.close();

// Main thread
context.closeAsync()
    .exceptionally(ex -> {
        Log.e("ABSmartly", "failed to close context", ex);
        return null;
    });
```

### Custom Event Logger

The SDK can be instantiated with an event logger used for all contexts. In addition, an event logger can be specified when creating a particular context in the `ContextConfig`.

```java
public class CustomEventLogger implements ContextEventLogger {
    @Override
    public void handleEvent(Context context, ContextEventLogger.EventType event, Object data) {
        switch (event) {
        case Exposure:
            final Exposure exposure = (Exposure) data;
            Log.d("ABSmartly", "exposed to experiment " + exposure.name);
            break;
        case Goal:
            final GoalAchievement goal = (GoalAchievement) data;
            Log.d("ABSmartly", "goal tracked: " + goal.name);
            break;
        case Error:
            Log.e("ABSmartly", "error: " + data);
            break;
        case Publish:
        case Ready:
        case Refresh:
        case Close:
            break;
        }
    }
}
```

Usage:

```java
// For all contexts, during SDK initialization
final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create();
sdkConfig.setContextEventLogger(new CustomEventLogger());

// OR during a particular context initialization
final ContextConfig contextConfig = ContextConfig.create();
contextConfig.setEventLogger(new CustomEventLogger());
```

**Event Types**

| Event      | When                                                       | Data                                   |
| ---------- | ---------------------------------------------------------- | -------------------------------------- |
| `Error`    | `Context` receives an error                                | `Throwable` object                     |
| `Ready`    | `Context` turns ready                                      | `ContextData` used to initialize       |
| `Refresh`  | `Context.refresh()` method succeeds                        | `ContextData` used to refresh          |
| `Publish`  | `Context.publish()` method succeeds                        | `PublishEvent` sent to collector       |
| `Exposure` | `Context.getTreatment()` succeeds on first exposure        | `Exposure` enqueued for publishing     |
| `Goal`     | `Context.track()` method succeeds                          | `GoalAchievement` enqueued for publishing |
| `Close`    | `Context.close()` method succeeds the first time           | `null`                                 |

## Platform-Specific Examples

### Using with an Application Class

Initialize the SDK once in your `Application` subclass so it is available throughout the app lifecycle:

```java
import android.app.Application;
import com.absmartly.android.sdk.ABSmartlyAndroid;

public class MyApplication extends Application {

    private static ABSmartlyAndroid absmartly;

    @Override
    public void onCreate() {
        super.onCreate();

        absmartly = ABSmartlyAndroid.builder()
            .endpoint("https://your-company.absmartly.io/v1")
            .apiKey("YOUR-API-KEY")
            .application("android-app")
            .environment("production")
            .context(getApplicationContext())
            .build();
    }

    public static ABSmartlyAndroid getAbsmartly() {
        return absmartly;
    }
}
```

### Using with Activities

```java
import android.os.Bundle;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.absmartly.android.sdk.ABSmartlyAndroid;
import com.absmartly.sdk.*;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private Context absmartlyContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ABSmartlyAndroid sdk = MyApplication.getAbsmartly();
        String deviceId = getOrCreateDeviceId();

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("device_id", deviceId);

        absmartlyContext = sdk.createContext(contextConfig);

        absmartlyContext.waitUntilReadyAsync()
            .thenAccept(ctx -> {
                runOnUiThread(() -> {
                    // Readiness may complete while the Activity is being destroyed.
                    // onDestroy runs on the UI thread too, so this check cannot race with it.
                    if (!isDestroyed() && ctx == absmartlyContext) {
                        setupUI(ctx);
                    }
                });
            })
            .exceptionally(throwable -> {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        setupDefaultUI();
                    }
                });
                return null;
            });
    }

    private void setupUI(Context context) {
        int treatment = context.getTreatment("exp_button_color");

        if (treatment == 0) {
            setContentView(R.layout.activity_main_control);
        } else {
            setContentView(R.layout.activity_main_treatment);
        }
    }

    private void setupDefaultUI() {
        setContentView(R.layout.activity_main_control);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (absmartlyContext != null) {
            final Context closing = absmartlyContext;
            // Clear the field first so queued UI callbacks see the context is gone.
            absmartlyContext = null;
            // closeAsync() flushes pending events without blocking the UI thread.
            // Do not call close() here: it waits for the publish request to finish.
            closing.closeAsync()
                .exceptionally(ex -> {
                    Log.e("ABSmartly", "failed to close context", ex);
                    return null;
                });
        }
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences("absmartly", MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }
}
```

### Using with Fragments

```java
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.fragment.app.Fragment;
import com.absmartly.android.sdk.ABSmartlyAndroid;
import com.absmartly.sdk.*;
import java.util.UUID;

public class ProductFragment extends Fragment {

    private Context absmartlyContext;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ABSmartlyAndroid sdk = MyApplication.getAbsmartly();

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("device_id", getDeviceId());

        final Context requested = sdk.createContext(contextConfig);
        absmartlyContext = requested;

        final View view = inflater.inflate(R.layout.fragment_product, container, false);

        requested.waitUntilReadyAsync()
            .thenAccept(ctx -> {
                // Post to this view rather than requireActivity(): the Fragment may
                // already be detached when readiness completes on a background thread.
                view.post(() -> {
                    // Skip if this view was destroyed (its context is closing) or
                    // replaced by a new view with its own context.
                    if (!isAdded() || getView() != view || absmartlyContext != requested) {
                        return;
                    }
                    int treatment = ctx.getTreatment("exp_product_layout");
                    if (treatment == 1) {
                        // apply treatment layout changes
                    }
                });
            });

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (absmartlyContext != null) {
            final Context closing = absmartlyContext;
            // Clear the field first so queued UI callbacks see the context is gone.
            absmartlyContext = null;
            // closeAsync() flushes pending events without blocking the UI thread.
            // Do not call close() here: it waits for the publish request to finish.
            closing.closeAsync()
                .exceptionally(ex -> {
                    Log.e("ABSmartly", "failed to close context", ex);
                    return null;
                });
        }
    }

    private String getDeviceId() {
        SharedPreferences prefs = requireActivity()
            .getSharedPreferences("absmartly", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }
}
```

### Lifecycle-Aware Readiness Cancellation

Cancel the readiness continuation when the Activity or Fragment is destroyed. This cancels the
`waitUntilReadyAsync()` future so its callback no longer runs after teardown; it does not abort
the underlying context-data request already in flight:

```java
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.absmartly.android.sdk.ABSmartlyAndroid;
import com.absmartly.sdk.*;
import java8.util.concurrent.CompletableFuture;

public class MainActivity extends AppCompatActivity {

    private CompletableFuture<Context> contextFuture;
    private Context absmartlyContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ABSmartlyAndroid sdk = MyApplication.getAbsmartly();

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("device_id", getOrCreateDeviceId());

        absmartlyContext = sdk.createContext(contextConfig);
        contextFuture = absmartlyContext.waitUntilReadyAsync();

        contextFuture.thenAccept(ctx -> {
            runOnUiThread(() -> {
                // Cancelling an already-completed future cannot retract this queued
                // runnable, so recheck the lifecycle before touching the context.
                if (!isDestroyed() && ctx == absmartlyContext) {
                    setupUI(ctx);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (contextFuture != null && !contextFuture.isDone()) {
            contextFuture.cancel(true);
        }

        if (absmartlyContext != null) {
            final Context closing = absmartlyContext;
            // Clear the field first so queued UI callbacks see the context is gone.
            absmartlyContext = null;
            // closeAsync() flushes pending events without blocking the UI thread.
            // Do not call close() here: it waits for the publish request to finish.
            closing.closeAsync()
                .exceptionally(ex -> {
                    Log.e("ABSmartly", "failed to close context", ex);
                    return null;
                });
        }
    }
}
```

## Cross-SDK Scenarios

`CrossSdkScenariosTest` replays the assignment-rule, semver and audience re-evaluation scenarios (230-247) from [cross-sdk-tests](https://github.com/absmartly/cross-sdk-tests), recorded from the JavaScript SDK, against `ABSmartlyAndroid`. The scenario file is vendored in `android-sdk/src/test/resources/cross-sdk/` together with the cross-sdk-tests commit it was copied from.

## Android-Specific Components

### SqliteAndroidLocalCache

The Android SDK provides an Android-optimized local cache backed by SQLite for storing context data and pending publish events. Persistence is not automatic: you must integrate the cache into your own read/write and retry flow. Retrieval is destructive — `retrievePublishEvents()` deletes the stored events as it returns them, so if the process terminates after retrieval but before the events are published they are lost. Do not rely on this cache as a crash-safe delivery mechanism.

```java
import com.absmartly.android.sdk.cache.SqliteAndroidLocalCache;

SqliteAndroidLocalCache cache = new SqliteAndroidLocalCache(getApplicationContext());

// Store context data
cache.writeContextData(context.getData());

// Retrieve context data
ContextData cachedData = cache.getContextData();

// Store a publish event for later retry
cache.writePublishEvent(publishEvent);

// Retrieve and clear all pending publish events
List<PublishEvent> pendingEvents = cache.retrievePublishEvents();
```

`serializeEvent`, `deserializeEvent`, `serializeContext` and `deserializeContext` convert events and context data to and from the JSON stored in the database; the two `deserialize` methods throw `CacheSerializationException` on invalid JSON.

The SQLite database is named `absmartly.db` and is automatically created in the application's default database directory.

## About A/B Smartly

**A/B Smartly** is the leading provider of state-of-the-art, on-premises, full-stack experimentation platforms for engineering and product teams that want to confidently deploy features as fast as they can develop them.
A/B Smartly's real-time analytics helps engineering and product teams ensure that new features will improve the customer experience without breaking or degrading performance and/or business metrics.

### Have a look at our growing list of clients and SDKs:
- [Java SDK](https://www.github.com/absmartly/java-sdk)
- [JavaScript SDK](https://www.github.com/absmartly/javascript-sdk)
- [PHP SDK](https://www.github.com/absmartly/php-sdk)
- [Swift SDK](https://www.github.com/absmartly/swift-sdk)
- [Vue2 SDK](https://www.github.com/absmartly/vue2-sdk)
- [Vue3 SDK](https://www.github.com/absmartly/vue3-sdk)
- [React SDK](https://www.github.com/absmartly/react-sdk)
- [Angular SDK](https://www.github.com/absmartly/angular-sdk)
- [Android SDK](https://www.github.com/absmartly/android-sdk) (this package)
- [Python3 SDK](https://www.github.com/absmartly/python3-sdk)
- [Go SDK](https://www.github.com/absmartly/go-sdk)
- [Ruby SDK](https://www.github.com/absmartly/ruby-sdk)
- [.NET SDK](https://www.github.com/absmartly/dotnet-sdk)
- [Dart SDK](https://www.github.com/absmartly/dart-sdk)
- [Flutter SDK](https://www.github.com/absmartly/flutter-sdk)
