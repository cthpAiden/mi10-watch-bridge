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

The IPA is unsigned. Sign and install it with your own Apple ID from a computer.
On Windows the tool that handles the watch part is
[WatchBridge](https://github.com/AdminZachary/WatchBridge) (unrelated project,
similar name). It needs iTunes or the Apple Devices app for the USB driver.

1. Connect the iPhone by cable, open WatchBridge, pick the iPhone.
2. Pair the watch in WatchBridge and accept the trust prompt on the watch. Ask
   it to show the Developer Mode switch, then on the watch: Settings > Privacy &
   Security > Developer Mode > on, restart, confirm.
3. On the iPhone: Settings > Privacy & Security > Developer Mode > on, restart.
4. Sign in with your Apple ID in WatchBridge, choose `AWBridge.ipa`, sign and
   install. It installs the iPhone host first, then the watch app.
5. On the iPhone: Settings > General > VPN & Device Management > trust your
   Apple ID.

[Sideloadly](https://sideloadly.io) also works on some setups (turn off
automatic bundle ID). If its log says the watch app was stripped from the IPA,
use WatchBridge.

With a free Apple ID the signature lasts 7 days. WatchBridge can re-sign
automatically before it expires.

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
