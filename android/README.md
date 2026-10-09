# OBD Auto

A small Android app that shows live OBD-II readings from a Bluetooth ELM327 adapter on the **Android Auto** car screen.
It lives in the `android/` folder of this repo and is separate from the web dashboard (`obd-dash.html`), which it doesn't change.

- Phone screen: choose the adapter, connect, see readings and a log.
- Car screen: speed, RPM, coolant, manifold pressure, battery and more, refreshed about once a second.
  Android Auto only allows simple list layouts, so there are no charts on the car screen.
- Works with Bluetooth Classic (paired adapters) and BLE adapters.
- Standard Mode 01 PIDs only. A PID the car doesn't report shows `n/a` after three failed reads.

## Install on the phone

1. Open the [latest Android build](https://github.com/kanealex/obd-dash/releases/tag/android-latest) on the phone and download `obd-auto.apk`.
2. Open the file and allow Chrome to install unknown apps when asked.
3. Open **OBD Auto**, allow Bluetooth, tap **Choose adapter** and pick the OBD adapter
   (pair it in Android Bluetooth settings first if it isn't listed, PIN is usually 1234 or 0000).
4. Turn on Android Auto developer mode: Settings › Apps › Android Auto › Additional settings in the app,
   then tap **Version** 10 times and accept.
5. In the Android Auto settings menu (⋮ › Developer settings) tick **Unknown sources**.
6. Connect to the car. OBD Auto appears in the Android Auto launcher (you may need to add it under Customise launcher).

New builds install over the old one, because every build is signed with the same key (`debug.keystore`, created by the first build).

## Build

GitHub Actions (`.github/workflows/android-apk.yml`) builds the APK on every push to `main` that changes `android/` and replaces the `android-latest` release.
Locally: `cd android && gradle :app:assembleDebug` with the Android SDK installed.
