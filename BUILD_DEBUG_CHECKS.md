# Checks

Practical checklist for local verification of the `AndroNSZ` project.

Run all commands from the project root:

```powershell
C:\Users\Boss\AndroidStudioProjects\AndroNSZ
```

## 1. Quick Kotlin check

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

What it checks:
- Kotlin code compiles;
- imports, signatures, and function calls are not broken;
- no errors in Compose code.

When to run:
- after any changes to `*.kt` files.

This is the fastest and most useful first pass. If it fails, there is usually no point checking further.

## 2. Full debug build

```powershell
.\gradlew.bat :app:assembleDebug
```

What it checks:
- Kotlin and Java;
- resources;
- `AndroidManifest.xml`;
- debug APK build;
- linked native part, if it participates in the build.

When to run:
- after changes to code, resources, manifest, or Gradle configuration.

## 3. Unit tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

What it checks:
- local unit tests from `app/src/test`.

Even if there are few tests, it is still worth keeping this command in the standard check routine.

## 4. Lint

```powershell
.\gradlew.bat :app:lintDebug
```

What it checks:
- common Android issues;
- resource errors;
- potential API and manifest problems;
- some UI issues.

When to run:
- after changes to Android UI, resources, manifest, or platform API.

## 5. Full local check without a device

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Recommended minimum before committing.

## 6. Full Gradle check

```powershell
.\gradlew.bat :app:build
```

What it does:
- builds the project;
- runs unit tests;
- performs standard Gradle checks.

Downside:
- takes longer;
- sometimes harder to quickly identify which step failed.

## 7. On-device check

If a bug can only manifest during app runtime, testing on a phone or emulator is needed.

### Verify that a device is connected

```powershell
adb devices
```

### Install the debug build

```powershell
.\gradlew.bat :app:installDebug
```

### Run instrumented tests

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

What this catches:
- issues that only appear on an Android device;
- Activity launch errors;
- instrumented UI/Android tests, if any exist.

## 8. Native part check

If C/C++ code or CMake configuration was changed:

```powershell
.\gradlew.bat :app:externalNativeBuildDebug
```

In practice, this is often sufficient:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Recommended order

### Regular check

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

### For quick Kotlin fixes

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

## Manual scenario verification

For changes related to application logic, building alone is not enough. After Gradle checks, you need to manually walk through the scenario on a device.

For the output folder naming case:

1. Select a source folder.
2. Start unpacking.
3. Verify that a folder like `Original_unpacked` is created.
4. Run again.
5. Verify that `Original_unpacked_2` is only created if `Original_unpacked` already exists.

## Quick reference

Minimum set:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Single command for a full run:

```powershell
.\gradlew.bat :app:build
```
