# BluetoothJammer — Fixed Android source (drop-in replacement)

This folder contains **fixed, working Android code** for the original
https://github.com/eikarna/BluetoothJammer repo. Copy these files over a fresh
checkout of that repo, then build the APK in Android Studio. The web GUI in this
generator (`index.html`) stays as an interactive simulator/preview — browsers can
never do RFCOMM flooding.

> Only test against **your own devices** in a lab, with permission. Jamming
> neighbours' speakers/headphones is illegal in most countries.

## What was fixed vs the original

- **Stop button now works** (original needed force-close). New `AttackManager`
  tracks every coroutine `Job` + every `BluetoothSocket`; Stop cancels all jobs
  and closes all sockets. The old code created a *new* `L2capFloodAttack` on stop,
  which cancelled nothing.
- **Connect loop is cancellable.** Old `while(true)` ignored `isAttacking`, so
  threads spun forever generating UUIDs. Fixed loop checks `isActive` +
  `isRunning()` every retry and `delay(150ms)` between attempts.
- **Devices list bug fixed.** Old code cleared `devicesList` every second and
  rebuilt the adapter (flicker, lost scroll, ACTION_FOUND results wiped).
  Fixed version keeps a `LinkedHashMap` keyed by MAC, dedups bonded + found
  devices, updates RSSI, and calls `notifyDataSetChanged()` once per tick.
- **Proper packages.** `api.*` / `util.*` → `com.eikarna.bluetoothjammer.api.*`
  (old bare `api` package breaks in some Gradle setups).
- **Handler leak fixed.** `Handler()` → `Handler(Looper.getMainLooper())`,
  nullable runnable, receivers unregistered in `onDestroy`.
- **Thread input validated** (1–256 normal, 257–1024 with confirm dialog —
  1024 coroutines will overheat most phones, as in the original screenshot).
- **Permissions:** adds `BLUETOOTH_ADVERTISE` handling on Android 12+, keeps
  SCAN/CONNECT/LOCATION flow, graceful deny message.
- Layouts keep the **same view IDs**, so no other wiring changes are needed.

## How to build the APK (Android Studio)

1. On your PC: `git clone https://github.com/eikarna/BluetoothJammer.git`
2. Copy this folder's files over the checkout, preserving paths:
   - `MainActivity.kt` → `app/src/main/java/com/eikarna/bluetoothjammer/MainActivity.kt`
   - `AttackActivity.kt` → `app/src/main/java/com/eikarna/bluetoothjammer/AttackActivity.kt`
   - `api/AttackDevices.kt` → `app/src/main/java/com/eikarna/bluetoothjammer/api/AttackDevices.kt`
   - `api/AttackManager.kt` → `app/src/main/java/com/eikarna/bluetoothjammer/api/AttackManager.kt` (new)
   - `api/ScanNearbyDevices.kt` → `app/src/main/java/com/eikarna/bluetoothjammer/api/ScanNearbyDevices.kt`
   - `util/Logger.kt` → `app/src/main/java/com/eikarna/bluetoothjammer/util/Logger.kt`
   - `res/layout/activity_main.xml` → `app/src/main/res/layout/activity_main.xml`
   - `res/layout/attack_layout.xml` → `app/src/main/res/layout/attack_layout.xml`
3. Open the project in **Android Studio Ladybug or newer**, let Gradle sync
   (AGP 8.6, Kotlin 1.9, compileSdk 34 — unchanged).
4. Plug in an Android phone (Android 8+) with USB debugging, press **Run**.
   Grant *Nearby devices* + *Location* when asked. Tap a device → Attack →
   set threads (start with 4–8 on your own test speaker) → Start. **Stop**
   now cleanly halts everything.
5. To make a shareable APK: **Build → Build App Bundle(s)/APK(s) → Build
   APK(s)**, then `app/build/outputs/apk/debug/app-debug.apk` installs on any
   phone (enable Install unknown apps).

## Files here

- `MainActivity.kt`, `AttackActivity.kt`
- `api/AttackDevices.kt`, `api/AttackManager.kt`, `api/ScanNearbyDevices.kt`
- `util/Logger.kt`
- `res/layout/activity_main.xml`, `res/layout/attack_layout.xml`
- `AndroidManifest.xml` (reference — unchanged from original except comments)
