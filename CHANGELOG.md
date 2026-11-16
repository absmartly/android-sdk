# Changelog

## Unreleased

The Android SDK has never been published: `com.absmartly.sdk:android-sdk` is not on Maven Central and the repository has no tags. The version is taken from the release tag (`v<version>`, see `.github/workflows/publish.yml`). The intended first tag is `v1.0.0`. After it, a release that changes behaviour customers can see gets a new minor version, with its notes in a new section here; released sections are not rewritten.

The notes below compare this release with `main` before it, which built against `com.absmartly.sdk:core-api:1.6.0`. Anyone who built `main` themselves should read them.

### Release gate

- This release depends on `com.absmartly.sdk:core-api-java6:1.9.0`, the core release of [absmartly/java-sdk#22](https://github.com/absmartly/java-sdk/pull/22). Until that artifact is on Maven Central, CI builds it from the #22 head (a temporary step in `android-ci.yml`), and the publish workflow cannot resolve it, so no Android release can be tagged before it.

### Known issue

- After you set an attribute, a context re-checks the audience and assignment rules of every cached assignment on each later read, not just once, until the assignment is rebuilt. Results are correct, but such a read costs about 1 µs instead of about 60 ns (HotSpot, one strict-audience experiment; not measured on ART). This is in the core (`core-api-java6` 1.9.0 and `core-api` 1.7.0 and later) and will be fixed there; the Android SDK picks the fix up with the core release that has it.

### Dependencies

- The SDK now depends on `com.absmartly.sdk:core-api-java6` instead of `com.absmartly.sdk:core-api`, and exposes it as an `api` dependency. `core-api` 1.8.0 and later need Android API 24; `core-api-java6` has the same API with the streamsupport future types and supports the SDK's `minSdk` 21.
  - Who moves: apps that also declare `com.absmartly.sdk:core-api` themselves. `core-api` and `core-api-java6` contain the same classes, so keep only one: remove your own `core-api` dependency (the SDK provides the core API), or exclude it with `exclude group: 'com.absmartly.sdk', module: 'core-api'`. Mixing versions also fails at run time (`NoSuchMethodError` on `HTTPClient.get` with code compiled against `core-api` 1.7.0 and run with 1.8.0).
- `androidx.appcompat:appcompat`, `com.google.android.material:material` and `org.conscrypt:conscrypt-android` are no longer dependencies. The SDK never used them. Who moves: apps that used them only through this SDK must declare them themselves. On Android 6.0 and earlier, add Conscrypt and pass `Conscrypt.newProvider()` to `ABSmartlyAndroid.Builder.securityProvider` (see the README).
- `jackson-databind` is 2.13.5, the last line that works below Android API 26.

### Added

- `ABSmartlyAndroid`, the Android entry point, with `create(...)` and `builder()`. It creates the core SDK and a `SqliteAndroidLocalCache`, and closes both, its HTTP transport and its scheduler in `close()`.
- `ABSmartlyAndroid.Builder.applicationVersion`, sent as the `X-Application-Version` header when it is a whole number (1 to 18 digits after trimming) and as `0` otherwise, because the collector only accepts whole numbers.
- `ABSmartlyAndroid.Builder.securityProvider` for Android 6.0 and earlier.
- Custom assignment rules and the `semver_eq`, `semver_gt`, `semver_gte`, `semver_lt` and `semver_lte` operators, from the core.
- Consumer ProGuard/R8 rules for the JSON model classes and Jackson.

### Changed

- **Assignment can change for running experiments.** These come from the core and match the JavaScript SDK:
  - Audience filters and assignment-rule conditions always evaluate with the configured `application` and `environment`, whatever `ContextConfig.setIncludeSystemAttributes` says. An attribute you set with the same name takes precedence. Before, a condition on `application` or `environment` saw a missing value unless you set the attribute yourself, so with a strict audience (`audienceStrict`) a unit whose application or environment matched got variant 0; it is now assigned normally. A condition that matches a missing value, such as a `null` check, may stop matching. The SDK does not provide `app_version` to conditions; set it as an attribute to target on it.
  - Audiences are re-evaluated when you set an attribute after a treatment was read. A unit that now matches (or stops matching) a strict audience moves between variant 0 and its assigned variant and is exposed again; with a non-strict audience the variant is kept but the exposure is queued again with the new `audienceMismatch`.
  - Unit UIDs with supplementary characters (emoji and other code points outside the Basic Multilingual Plane), for example `😀user` or `👨‍👩‍👧`, are hashed as standard UTF-8, like the other SDKs. They may be assigned a different variant than with `core-api` 1.6.0. ASCII and other Basic Multilingual Plane UIDs are unaffected.
- **Publish payload.** Every exposure carries `ruleOverride` (`true` for a rule-assigned exposure, `false` otherwise), as in the JavaScript SDK. With `setIncludeSystemAttributes(true)`, each publish also carries `sdk_name` (`absmartly-android-sdk`) and `sdk_version`; the configured application and environment are not published (the collector adds them), and no `app_version` is published unless you set it.
- **Agent identity.** Requests carry `X-Agent: absmartly-android-sdk/<version>` instead of the core's `absmartly-java-sdk`. The collector stores this value in the exported `agent` column, so a filter on `agent = 'absmartly-java-sdk'` no longer matches Android traffic. Contexts created through the core `ABSmartly` class directly still send `absmartly-java-sdk/<version>`.
- **Lifecycle (from the core).** `refresh()` on a context whose initial fetch failed is a successful no-op instead of fetching again; create a new context to retry. If the publish during `close()` fails, the events are kept and the context stays open so `close()` can be retried. A publish that fails keeps its events for the next publish.
- `SqliteAndroidLocalCache` no longer implements `com.absmartly.sdk.cache.LocalCache`. That interface does not exist in any published core release, so `main` did not compile as declared.
- `SqliteAndroidLocalCache.retrievePublishEvents()` returns events in insertion order and deletes them in the same transaction; `writeContextData` replaces the stored context in one transaction instead of adding a row, and `getContextData()` returns the newest one. A row that cannot be parsed is skipped (and logged without its content) instead of failing the read. The public `serializeEvent`, `deserializeEvent`, `serializeContext` and `deserializeContext` keep their contract: `null` round-trips through `"null"`, invalid JSON throws a `RuntimeException` whose cause is the Jackson exception, and a database error reaches the caller as the original `SQLiteException`.
