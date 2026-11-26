# Building StillReader

Use Android Studio’s JDK 25 or another local JDK 25 installation. Set JAVA_HOME for the terminal. Install the Android API 37 SDK platform (platforms;android-37.0), Build Tools 36.0.0, and Platform Tools using Android Studio’s SDK Manager. Use an Android 15 system image for API 35 emulator verification.

Use the checked-in Gradle wrapper. On Windows PowerShell, replace bash ./gradlew in the following examples with .\gradlew.bat.

```sh
pwsh -File scripts/verify-dependencies.ps1
bash ./gradlew --dependency-verification=strict :app:assembleDebug :app:test :app:assembleDebugAndroidTest :app:lint :app:assembleRelease
pwsh -File scripts/verify-release-manifest.ps1
```

The initial build needs verified artifacts from the allowlisted repositories. Keep the known-good cache for offline rebuilds. A checksum or missing age record is a review failure. See [Dependencies](DEPENDENCIES.md).

Install the debug APK using Android Studio or adb install app/build/outputs/apk/debug/app-debug.apk. Launch StillReader and choose a device-local PDF. On an Android 15 device or emulator, enable airplane mode and run the instrumentation tests. The tests generate and remove their synthetic documents on the device.

```sh
adb install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
```

To verify reading state after process termination, run both stages on a test emulator. The first stage imports a synthetic copy. The second stage opens it after force-stop and removes the test book.

```sh
adb shell am instrument -w -e class 'io.github.santtua.stillreader.ReaderFlowTest#copiedBookResumesWithSavedPosition' -e library_restart prepare io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop io.github.santtua.stillreader
adb shell am instrument -w -e class 'io.github.santtua.stillreader.ReaderFlowTest#copiedBookResumesWithSavedPosition' -e library_restart resume io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
```

The database migration check seeds a version 1 library and verifies its book and position after the app upgrades the schema. Run this check first on a fresh test emulator with the debug and test APKs installed.

```sh
adb shell am instrument -w -e class 'io.github.santtua.stillreader.LibraryMigrationTest#keepsVersionOneBooksAndReadingPositions' -e verify_v1_library true io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
```

The real file picker check uses the system DocumentsUI on an English-language test emulator.

```sh
adb shell am instrument -w -e class 'io.github.santtua.stillreader.ReaderFlowTest#systemPickerRetainsReadAccessAndRelinksAMissingBook' -e verify_system_picker true io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
```

The backup and restore flow test uses DocumentsUI on a fresh English-language test emulator with an empty library. It creates a synthetic book and JSON backup, deletes the test library, and restores the backed-up reading metadata. Keep this test on a disposable emulator.

```sh
adb shell am instrument -w -e class 'io.github.santtua.stillreader.ReaderFlowTest#exportsAndRestoresReadingMetadataThroughTheSystemPicker' -e verify_system_backup true io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
```

Enable TalkBack on a disposable Android 15 test emulator to check accessibility focus, zoom activation, and the page-jump label. An interactive pass is still needed to check spoken announcements and navigation throughout the app.

```sh
adb shell am instrument -w -e class 'io.github.santtua.stillreader.ReaderFlowTest#exposesPageNavigationAndZoomWithTalkBackEnabled' -e verify_talkback true io.github.santtua.stillreader.test/androidx.test.runner.AndroidJUnitRunner
```

The unsigned release APK is app/build/outputs/apk/release/app-release-unsigned.apk. For personal distribution, use Android Studio’s Generate Signed Bundle or APK wizard, choose APK, and keep the key and passwords outside the checkout. Retain a secure copy of the same release key for upgrades. Run apksigner verify --verbose and check the final signed APK with scripts/verify-release-manifest.ps1 using its -Apk parameter. The local development build is the debug APK. The release manifest check covers the unsigned release output and can also inspect a locally signed release APK.
