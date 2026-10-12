# BBox GMS Sign-In Fix — Status Report Round 4 for Gemini

> **Device**: Infinix X6873 / Android 16 (API 36) / arm64-v8a  
> **Guest**: Play Store 26.37.37 + GMS 26.37.37  
> **Host**: ArkCheat (`com.bgmi`) / MundoCore (BlackBox-derived)

---

## 1. What Was Applied From Your Round 4 Guide

### Action 1 — Register virtual LoadedApk in ActivityThread.mPackages / mResourcePackages (APPLIED)

In `handleBindApplication`, immediately after `mBoundApplication = bindData;`, we now reflectively
obtain `ActivityThread.mPackages` and `ActivityThread.mResourcePackages` and insert a
`WeakReference<>(loadedApk)` keyed by the virtual package name.

**Intent**: prevent AOSP `installProvider()` from calling `getPackageInfoNoCheck()` → `createPackageContextAsUser()` when it misses the package in `mPackages`, which creates a second isolated `PathClassLoader` (ClassLoader B).

### Action 2 — Direct local ContentProvider instantiation (APPLIED)

The entire `installProviders()` method was rewritten. Instead of calling AOSP's `installProvider(MundoCore.mainThread(), ctx, info, null)` (which triggers the secondary ClassLoader path), we now:

1. Load the provider class via `mInitialApplication.getClassLoader().loadClass(info.name)` — using the single guest ClassLoader
2. Instantiate it with `providerClass.newInstance()`
3. Call `provider.attachInfo(isolatedContext, info)` where `isolatedContext` wraps `mInitialApplication` and explicitly returns `targetCl` from `getClassLoader()`
4. Register the provider into `ActivityThread.mProviderMap` via reflection (`ProviderClientRecord` + `ProviderKey`)

`getIContentProvider()` is called via reflection (hidden API).

**Intent**: both `FinskyApplication` and `avcr/auin` Dagger entrypoint interfaces now share the EXACT same ClassLoader — no secondary loader is ever created.

### Action 3 — Merge split APK DEX files into primary ClassLoader (APPLIED)

After `mInitialApplication` is set, before `installProviders()`, we call `mergeSplitApksIfPresent(mInitialApplication, applicationInfo.sourceDir)`.

This method:
- Checks if the guest app's ClassLoader is a `BaseDexClassLoader`
- Lists all `.apk` files in the same directory as `sourceDir`
- Calls `BaseDexClassLoader.addDexPath(splitApkPath)` on each split APK found (via reflection)

**Split APK confirmation**: BBox source has **zero** native handling for split APK ClassLoader merging — no `addDexPath`, no `splitSourceDirs`, no `splitApk` anywhere in the MundoCore codebase. This is confirmed by grep. This means Play Store split APKs (`split_config.arm64_v8a.apk`, `split_store_phoneGapps.apk`, etc.) were previously loaded in ISOLATED ClassLoaders, separate from the base APK's ClassLoader. Action 3 fixes this by merging them all.

### Action 4 — Additional IUserManagerProxy hooks (APPLIED)

Six new `@ProxyMethod` handlers added to `IUserManagerProxy.java`:

| Method | Return |
|---|---|
| `isManagedProfile` | `false` |
| `isUserAdmin` | `true` |
| `isDemoUser` | `false` |
| `isQuietModeEnabled` | `false` |
| `getUserRestrictions` | empty `Bundle()` |
| `getApplicationRestrictionsForUser` | empty `Bundle()` |

---

## 2. Expected Result

With Actions 1+2+3 combined, the two ClassLoaders that caused `ClassCastException: Cannot cast snb to avcr/auin` should now be unified into ONE ClassLoader. Both `FinskyApplication` and `avcr/auin` Dagger `@EntryPoint` interfaces will be loaded by the same `BaseDexClassLoader`, so `Class.cast(app)` in `sml.e(PG:3)` should succeed.

This should allow the `bgExecutor #0` and `bgExecutor #1` tasks to complete, which in turn populates the "Checking info…" verification UI and activates the NEXT button.

---

## 3. Current Architecture State

| Component | State |
|---|---|
| `ActivityThread.mPackages` registration | ✅ NEW — Round 4 |
| Direct provider instantiation (no AOSP installProvider) | ✅ NEW — Round 4 |
| Split APK dex merging via `addDexPath` | ✅ NEW — Round 4 |
| IUserManagerProxy (15 hooks total) | ✅ 6 new in Round 4 |
| HiddenApiBypassInternal `["L"]` exemption | ✅ active |
| `attachInfo` interception in ContentProviderStub | ⚠️ kept but superseded by Action 2 |
| Thread ContextClassLoader sync | ✅ in AppInstrumentation + ContentProviderStub |

---

## 4. What to Watch in Logcat

```
# Confirm split APKs were merged:
adb logcat -s BActivityThread | grep "Merged split APK"

# Watch for ClassCastException (should be GONE):
adb logcat -s crash | grep -E "ClassCastException|bgExecutor"

# Watch for provider registration:
adb logcat -s BActivityThread | grep -E "install local provider|register to mProviderMap"
```

Expected success log lines:
```
BActivityThread: Merged split APK into primary ClassLoader: split_config.arm64_v8a.apk
BActivityThread: Merged split APK into primary ClassLoader: split_store_phoneGapps.apk
```

If the split APK directory path is wrong (e.g., BBox virtualizes sourceDir to a different path), `mergeSplitApksIfPresent` will silently do nothing — `appDir.exists()` will return false. In that case the split dex path must be resolved from BBox's virtual install path for the package.

---

## 5. If ClassCastException Persists After Round 4

If the crash still occurs, it means Actions 1+2 did NOT prevent ClassLoader B from being created. The next diagnostic step is to add this log in `handleBindApplication` after `mInitialApplication` is set:

```java
Slog.d("BActivityThread", "Guest ClassLoader: " + mInitialApplication.getClassLoader());
// Then after installProviders, log the ClassLoader of a known split class if possible
```

And in the bgExecutor crash, check whether `avcr.class.getClassLoader()` equals `FinskyApplication.class.getClassLoader()` — if still not equal, the split APK directory was not found by `mergeSplitApksIfPresent` or the secondary ClassLoader was created elsewhere (e.g., in `ContentProvider.attachInfo` via `ContextImpl.createPackageContext`).

**Alternative fix if directory path is wrong**: Resolve the virtual package source directory from `BEnvironment.getApkPath(packageName, userId)` or the equivalent BBox path resolution API, instead of `applicationInfo.sourceDir`.

---

## 6. Summary Table

| Crash | Status | Round Fixed |
|---|---|---|
| ClassCastException `snb→avcr/auin` in bgExecutor | **PENDING Round 4 result** | — |
| Dark screen / MinuteMaidActivity | FIXED | Round 2 |
| `SecurityException: MANAGE_USERS` | FIXED | Round 3 |
| `FrpService: getUserInfo` SecurityException | FIXED | Round 3 |
| "Checking info…" stuck / NEXT never activates | PENDING — caused by bgExecutor crash | — |
