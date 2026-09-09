# Vendored source

Copied from **https://github.com/libxposed/service**, commit `3318940` (version `102.0.0`).
Local clone: `../../service`

Combines two upstream modules:

| Upstream path                      | Local path          |
| ---------------------------------- | ------------------- |
| `service/src/main/java`            | `src/main/java`     |
| `interface/src/main/aidl`          | `src/main/aidl`     |

## Why vendored instead of a Gradle dependency

Same reason as `libxposed-api`: the published `io.github.libxposed:service:102.0.0` AAR declares
`minCompileSdk=37`. Unlike the API module, this code **is** packaged into the APK — it is what
talks to the Xposed framework — so keep it in step with the framework's expectations.

## Local modifications (diverges from upstream)

- `RemotePreferences.java#newInstance` — the deprecated single-arg `Bundle.getSerializable("map")`
  is now guarded: the typed `getSerializable(String, Class)` on API 33+, the old call below it
  (minSdk is 27). Re-apply this after any re-sync, or the deprecation note returns.

## Re-syncing

    git -C ../../service pull
    cp -r ../../service/service/src/main/java/io libxposed-service/src/main/java/
    cp -r ../../service/interface/src/main/aidl/io libxposed-service/src/main/aidl/

Then update the commit recorded above.
