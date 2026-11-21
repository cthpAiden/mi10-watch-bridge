# AW Bridge: Android phone to Apple Watch over Bluetooth LE

Shows Google Maps turn-by-turn directions and Android notifications on an Apple
Watch, straight from an Android phone. No root, no jailbreak, and the watch stays
paired with its iPhone.

Built for a Xiaomi Mi 10 and an Apple Watch Series 4 on watchOS 9.6.3. Nothing in
it is specific to those two: any Android 8+ phone with BLE advertising and any
watch on watchOS 9+ should behave the same.

## What it does

- **Live map** on the watch with the phone position in the middle. The phone
  sends its GPS fix every second and relays map tiles, which the watch caches.
- **Route and turns**: share a place from Google Maps to AW Bridge (or type it
  in the app). The route line is drawn on the map and the next turn, with a
  tap on the wrist, comes from the same route. Routing is done on
  OpenStreetMap data with a motorbike profile, because Google Maps does not
  expose its own route. Without a route set, the turn banner still follows a
  navigation running in Google Maps.
- **Notifications**: the watch mirrors the phone list: app, title, full text,
  time. Removing one on either side removes it on the other, and the whole
  list is resent after a reconnect so nothing is lost.
- **Find my phone** from the watch.

## How it works

```
Google Maps notification, GPS, notifications, map tiles
        |
   Android app  (BLE peripheral, GATT server)
        |  Bluetooth LE, custom service, encrypted after pairing
   watchOS app  (BLE central, CoreBluetooth)
        |
   wrist: map, route, next turn, notifications, haptic tap
```

- **Android app** (`android/`): a foreground service advertises a custom BLE
  service. A notification listener reads the Google Maps navigation
  notification and every other notification. The service also streams the
  position, fetches map tiles and plans routes for the watch, which has no
  internet of its own.
- **watchOS app** (`apple/Watch`): connects to the phone as a BLE central and
  shows the map, the route, the current maneuver and the notification list.
- **iPhone host app** (`apple/Host`): an empty shell. watchOS apps can only be
  installed through the paired iPhone, so the watch app ships inside it. The
  iPhone is not involved once the watch app is installed.

The wire format is in [`android/app/src/main/java/dev/awbridge/Protocol.kt`](android/app/src/main/java/dev/awbridge/Protocol.kt).

Map tiles come from the OpenStreetMap tile server and routes from the public
Valhalla server run by FOSSGIS; place names are looked up with Photon and
Nominatim. Map data (c) OpenStreetMap contributors. These are shared community
services: this app only asks for what is on screen and caches it.

## Install

Download both files from the [latest release](../../releases/latest).

### Android phone

1. Install `AWBridge.apk` (allow installing from unknown sources).
2. Open the app and press the buttons in order: grant Bluetooth and location,
   allow notification access, start the bridge.
3. Press buttons 4 and 5 as well: exempt the app from battery optimisation and,
   on MIUI / HyperOS, set its battery saver to **No restrictions** and turn on
   **Autostart**. Without this the system cuts the app's network once the phone
   is in a pocket, and the watch gets no map tiles and no new routes. The
   status lines at the top of the app ("Chạy nền", "Mạng") show whether it worked.

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

1. Start the bridge on the phone, open AW Bridge on the watch. The first time
   both ask to pair over Bluetooth: accept on both. The watch shows
   "Đã kết nối" on the last page.
2. Press "Gửi thử chỉ đường lên watch" on the phone to check the link.
3. The first page is the live map. For a route, open a place in Google Maps,
   tap Share and pick AW Bridge. A dropped pin always works; a named place is
   looked up by name and can miss.

## Limits

- watchOS only lets an app keep a Bluetooth link while it is on screen or
  holding a background session. The app starts a session by itself when
  navigation begins. One session lasts at most an hour; reopen the app to start
  another.
- Forwarded notifications only arrive while the watch app is running like that.
  This is not a replacement for system notification mirroring.
- The link is encrypted and needs Bluetooth pairing ("Mã hóa kết nối", on by
  default). Notifications are only forwarded over an encrypted link.
- The route comes from an open routing engine without live traffic, so it can
  differ from the route Google Maps would choose.
- A new map area takes several seconds to load over Bluetooth the first time.
- Google Maps "Live Updates" notifications (Android 16) hide the text this app
  reads. Turn Live Updates off for Maps if directions stop showing.

## Build

Everything is built by GitHub Actions (`.github/workflows/build.yml`): the APK on
Linux, the watch app on macOS with XcodeGen and `xcodebuild`, unsigned.
