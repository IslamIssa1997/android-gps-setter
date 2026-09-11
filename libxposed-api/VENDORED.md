# Vendored source

Copied from **https://github.com/libxposed/api**, commit `79b75b4` (tag `102.0.0`).
Local clone: `../../api`

## Why vendored instead of a Gradle dependency

The published `io.github.libxposed:api:102.0.0` AAR declares `minCompileSdk=37`, which would
force this project onto AGP 9 / compileSdk 37. Building the same sources locally avoids that
gate while staying on AGP 8.7 / compileSdk 36.

This module is consumed with `compileOnly` — the Xposed framework provides these classes at
runtime, so none of it is packaged into the APK.

## Re-syncing

    git -C ../../api pull
    cp -r ../../api/api/src/main/java/io libxposed-api/src/main/java/

Then update the commit recorded above. Verify with:

    diff -rq ../api/api/src/main/java libxposed-api/src/main/java
