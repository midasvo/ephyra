# :baselineprofile-tv

Macrobenchmark module for `app/tv` (#286): records the TV baseline profile and measures startup.
It is the TV counterpart of `:baselineprofile` (phone, #249): a `com.android.test` module with the
`androidx.baselineprofile` plugin, targeting the `libre` flavour. A test module can only target one
app, so phone and TV each have their own.

Generation never runs as part of a normal build (`automaticGenerationDuringBuild = false`).
There is no Gradle managed device (those are phone images): the tasks always run on the attached
Android TV device or emulator. Android 13 or later needs no root; the profile has been recorded on
the `android-tv` emulator image (Android 14, a production build).

## The journey

`Journey.kt` drives the app with the D-pad only (TV has no touch) and waits after every key until
focus or the text on screen changes, because the screens have no test tags and text is translated.

- **Logged in:** along Home's first row and back, down every row, open the focused card of the
  last row, down to the row under its buttons and open that card, down its list, then Back to Home.
- **Not logged in:** only the welcome screen is profiled.

The full journey (Home → a show → a season → back) needs **a shows row as the last row on Home**,
which is the case on a server whose shows library comes last (the local test server: "Latest
Shows"). With a movie or anything else there, the journey opens that card and at most the first
card below its buttons, goes back the same number of steps and ends on Home: the run passes, it
just profiles less.

## Sign in once

The profile and benchmark builds (`nonMinifiedRelease`, `benchmarkRelease`) are
`nl.midasvo.findroid.ce` signed with your local debug key: a separate install from the debug app,
without a login. `SignIn` signs that install in once. It is skipped unless the user and password
are passed as instrumentation arguments, so put them in shell variables and never in a file:

```
./gradlew :baselineprofile-tv:connectedLibreNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.jdtech.jellyfin.baselineprofile.tv.SignIn \
  -Pandroid.testInstrumentationRunnerArguments.jfServer=http://10.0.2.2:8096 \
  -Pandroid.testInstrumentationRunnerArguments.jfUser="$JF_USER" \
  -Pandroid.testInstrumentationRunnerArguments.jfPassword="$JF_PASS" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

`jfServer` defaults to `http://10.0.2.2:8096` (the host, seen from an emulator). Add
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to every run below as well: Gradle
otherwise uninstalls the app, and its login, when a run ends. The profile and benchmark builds
replace each other without losing the login, since they share the package and the key. The release
package can't be installed over a GitHub or Play install of it.

## Generate the profile

```
./gradlew :app:tv:generateLibreReleaseBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

With more than one device attached, set `ANDROID_SERIAL=<serial>` first: connected tasks otherwise
run on every device. A run takes about 8 minutes on the emulator with software rendering.

The profile is written to `app/tv/src/libreRelease/generated/baselineProfiles/`; commit it.
`profileinstaller` then installs it on first run (the TV manifest keeps its initializer).

### Windows: path too long

The test writes its profile on the device and Gradle pulls it into
`baselineprofile-tv/build/outputs/connected_android_test_additional_output/…/<device name>/…`.
`adb.exe` can't write paths over 260 characters, even with long paths enabled in Windows, and
the build then ends with "No baseline profile rules were generated". From a checkout in a
`.claude/worktrees/…` folder the path is about 280 characters. Run Gradle from a short drive
letter for the run and remove it afterwards:

```
subst Q: C:\path\to\the\checkout
cd /d Q:\
gradlew :app:tv:generateLibreReleaseBaselineProfile …
subst Q: /d
```

The phone module (`:baselineprofile`) has the same limit from a worktree.

## Benchmark

`StartupBenchmark` runs the journey cold, with `CompilationMode.None()` and with the baseline
profile (`Partial`, which needs a committed profile). On an emulator, suppress the emulator error;
its numbers only show a direction (see `docs/performance.md`):

```
./gradlew :baselineprofile-tv:connectedLibreBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Results are in `baselineprofile-tv/build/outputs/connected_android_test_additional_output/`.
