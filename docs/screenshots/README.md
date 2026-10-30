# Screenshots

PNGs of every screen, rendered on the JVM (Robolectric native graphics + Roborazzi) from the real `AppRoot` and a real
`AppViewModel` seeded with fixture data (`app/src/test/kotlin/com/calum/traininglog/Fixtures.kt`). Pixel 6a-like
411x914 dp at xxhdpi, dark theme, SDK 34. Scrollable screens show only the first viewport.

Regenerate (record only, nothing is compared, so UI changes never fail a build):

    gradle :app:recordRoborazziDebug

Files are written here (`docs/screenshots`). CI runs the same task and uploads this folder as the `screenshots` artifact.

Add a screen: append a `Shot("name") { ...setup using vm... }` to the `shots` list in `ScreenshotTest.kt`.

Robolectric downloads `android-all` jars (SDK 34 and 15) from Maven Central on first run. If Maven returns HTTP 429, download
`android-all-instrumented-14-robolectric-10818077-i7.jar` and `android-all-instrumented-15-robolectric-12650502-i7.jar`
into a folder and run with `ROBOLECTRIC_DEPENDENCY_DIR=<folder>`.

Fixtures use dates relative to today, so streak/week content varies a little by weekday. See `REVIEW.md` for a design review.
