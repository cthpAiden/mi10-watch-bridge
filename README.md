# AW Bridge: Android phone to Apple Watch over Bluetooth LE

Shows Google Maps turn-by-turn directions and Android notifications on an Apple
Watch, straight from an Android phone. No root, no jailbreak, and the watch stays
paired with its iPhone.

Built for a Xiaomi Mi 10 and an Apple Watch Series 4 on watchOS 9.6.3. Nothing in
it is specific to those two: any Android 8+ phone with BLE advertising and any
watch on watchOS 9+ should behave the same.

## How it works

```
Google Maps notification
        |
   Android app  (BLE peripheral, GATT server)
        |  Bluetooth LE, custom service
   watchOS app  (BLE central, CoreBluetooth)
        |
   wrist: arrow, distance, street, haptic tap
```

- **Android app** (`android/`): a foreground service advertises a custom BLE
  service. A notification listener reads the Google Maps navigation notification
  (distance, instruction, ETA, maneuver arrow) and pushes each change to the
  watch. It can also forward other apps' notifications.
- **watchOS app** (`apple/Watch`): connects to the phone as a BLE central, shows
  the current maneuver, taps the wrist when the instruction changes, lists
  forwarded notifications, and can ring the phone.
- **iPhone host app** (`apple/Host`): an empty shell. watchOS apps can only be
  installed through the paired iPhone, so the watch app ships inside it. The
  iPhone is not involved once the watch app is installed.

The wire format is in [`android/app/src/main/java/dev/awbridge/Protocol.kt`](android/app/src/main/java/dev/awbridge/Protocol.kt).

## Install

Download both files from the [latest release](../../releases/latest).

### Android phone

1. Install `AWBridge.apk` (allow installing from unknown sources).
2. Open the app and press the buttons in order: grant Bluetooth, allow
   notification access, start the bridge.
3. On MIUI / HyperOS: in the app's system settings turn on **Autostart** and set
   battery saver to **No restrictions**, otherwise the system kills the service.

### Apple Watch (through its iPhone)

The IPA is unsigned. Sign and install it with your own Apple ID from a computer:

1. Install [Sideloadly](https://sideloadly.io) (Windows or macOS), connect the
   iPhone by cable.
2. Drag `AWBridge.ipa` in, enter your Apple ID. Under advanced options turn off
   automatic bundle ID and keep a fixed one.
3. On the iPhone: Settings > Privacy & Security > Developer Mode > on, restart.
   Then Settings > General > VPN & Device Management > trust your Apple ID.
4. Open the Watch app on the iPhone, scroll to Available Apps, install AW Bridge.
5. On the watch: Settings > Privacy & Security > Developer Mode > on, restart.

If the Sideloadly log says the watch app was stripped from the IPA, use
[iloader for Windows](https://github.com/AdminZachary/iloader-for-windows)
instead, which installs the iPhone host and then the watch app.

With a free Apple ID the signature lasts 7 days, then the app has to be
re-signed the same way.

## Use

1. Start the bridge on the phone, open AW Bridge on the watch. The watch shows
   "Đã kết nối" on the third page.
2. Press "Gửi thử chỉ đường lên watch" on the phone to check the link.
3. Start navigation in Google Maps. The first page of the watch app follows it.

## Limits

- watchOS only lets an app keep a Bluetooth link while it is on screen or
  holding a background session. The app starts a session by itself when
  navigation begins. One session lasts at most an hour; reopen the app to start
  another.
- Forwarded notifications only arrive while the watch app is running like that.
  This is not a replacement for system notification mirroring.
- The link is unencrypted unless "Mã hóa kết nối" is turned on in the Android
  app, which makes the watch ask for Bluetooth pairing. Notification forwarding
  is off by default for that reason.
- Google Maps "Live Updates" notifications (Android 16) hide the text this app
  reads. Turn Live Updates off for Maps if directions stop showing.

## Build

Everything is built by GitHub Actions (`.github/workflows/build.yml`): the APK on
Linux, the watch app on macOS with XcodeGen and `xcodebuild`, unsigned.
