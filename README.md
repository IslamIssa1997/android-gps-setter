<div align="center">

# 🛰️ GPS Setter

### Modern, undetectable GPS spoofing for Android — rebuilt on the **libxposed API 102**.

Pin any location, fake a *complete and self-consistent* GNSS fix, and choose exactly which apps
believe it — all from a clean map UI.

<br/>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-2ea44f?style=for-the-badge&logo=gnu)](LICENSE)
[![Xposed API](https://img.shields.io/badge/Xposed-API%20102-8A2BE2?style=for-the-badge)](https://github.com/libxposed/api)
[![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#-compatibility)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#)

[![Latest release](https://img.shields.io/github/v/release/IslamIssa1997/android-gps-setter?color=blue&label=release&sort=semver)](https://github.com/IslamIssa1997/android-gps-setter/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/IslamIssa1997/android-gps-setter/total?color=orange&label=downloads)](https://github.com/IslamIssa1997/android-gps-setter/releases)

<sub>Requires a rooted device with an Xposed framework that implements libxposed API 102 (e.g. LSPosed).</sub>

</div>

---

## ✨ Why GPS Setter

An increasing number of apps abuse the location permission for tracking and refuse to work unless
it's granted. The classic workaround — Android's mock-location provider — is device-dependent and
easy to detect: many apps now check whether the location they receive is *trustworthy*.

GPS Setter closes those gaps. It doesn't just move a dot on a map — it delivers a **physically
consistent fix** (altitude, speed, bearing, every accuracy field, *and a plausible satellite
constellation*), and it can do so **invisibly from the system side**, so the target app never sees a
hook in its own process.

---

## 🚀 Features

|  | Feature | What it does |
|:--:|:--|:--|
| 🛰️ | **Complete fake fix** | Latitude, longitude, altitude, mean-sea-level, speed, bearing and every accuracy field — each **Off**, **Fixed**, or **Auto** (real elevation & geoid lookup). No more tell-tale `0.0`s. |
| 🥷 | **Stealth targeting** | Spoof an app entirely from **system_server** — the module is never loaded into it, so in-app hook detection finds nothing. |
| 🎯 | **Per-app control** | Tick apps for stealth, or “Add to scope” for the deeper in-app hook. Pinned selection, global search, auto-cleanup of uninstalled apps. |
| 📡 | **Synthetic GNSS** | Suppresses real satellites and feeds a believable constellation (GPS + GLONASS + Galileo + BeiDou) consistent with the fake position — on both the app **and** system side. |
| 📶 | **Wi-Fi & cell spoofing** | Closes the cheap cross-checks: fake access point identity and blanked cell towers for targeted apps. |
| 🛣️ | **Route navigation** | Draw a route on the map, add stops, set speed up to **200 km/h** — movement is time-accurate, so reported speed matches exactly. |
| 🕹️ | **Joystick & floating map** | On-screen joystick and a resizable, draggable floating map window to move your position live over any app. |
| 🔎 | **Smart search** | Place names bounded to the visible map, while Plus Codes and national-address codes resolve worldwide. |
| ⭐ | **Favourites** | Save locations with notes; JSON import/export to move them between installs. |
| 🌍 | **Arabic + RTL** | Full Arabic localization with proper right-to-left layout and an in-app language picker. |
| 📻 | **Automation** | Optional broadcast control to drive the module from scripts or ADB. |

---

## 🧭 How targeting works

> **Two methods, your choice per app.**

| | 🥷 Stealth (tick the app) | 🪝 Hook method (“Add to scope”) |
|:--|:--|:--|
| **Runs in** | `system_server` | the target app's own process |
| **Visible to the app?** | ❌ No | ⚠️ Detectable in-process |
| **Covers** | location, GNSS, Wi-Fi, cell | everything above **+** in-app fused location & method hooks |
| **Best for** | most apps, maximum stealth | apps that cross-check their *own* GNSS internals |

---

## 🧩 Compatibility

- **Android 8.1+**
- A **rooted** device with an Xposed framework implementing **libxposed API 102** (e.g. LSPosed)
- **Not compatible with LSPatch** or legacy `de.robv.android.xposed`-only frameworks — the two APIs may no longer be mixed
- Google Maps build only (the FOSS MapLibre/microG variant was removed)

---

## 📦 Install

1. Grab the latest signed APK from the [**Releases**](https://github.com/IslamIssa1997/android-gps-setter/releases/latest) page.
2. Install it and **enable the module** in LSPosed.
3. In LSPosed, allow the module's scope. It ships scoped to **System Framework** and **Phone** — enough for stealth location, synthetic GNSS, and cell spoofing. Reboot.
4. Open GPS Setter, choose your targets in **Settings → Target Apps** (tick an app for stealth), drop a pin, and hit **▶**.

> The module manages scope dynamically (`staticScope=false`): when you pick **Target Apps → ⋮ → Add to scope**, that app is added to the LSPosed scope automatically, so the scope list grows as you add apps.

The app checks for new releases on its own and can download & install updates in place.

---

## 📜 Changelog

See [**CHANGELOG.md**](CHANGELOG.md) for the full history.

---

## 🙏 Credits

| | |
|:--|:--|
| [**Android1500**](https://github.com/Android1500/GpsSetter) | Original creator of GpsSetter |
| [**jqssun**](https://github.com/jqssun/android-gps-setter) | New package & Android 15+ support |
| [**Mare Razvan**](https://github.com/Dphpvp) | UI improvements & route navigation |
| [**Islam Issa**](https://github.com/IslamIssa1997) | libxposed API 102 rebuild, feature work & this repo |
| [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation) | Reference implementation of the modern API |

<sub>Built with the help of [Claude Code](https://claude.com/claude-code) (Anthropic).</sub>

---

## ⚖️ License

Released under the **GNU General Public License v3.0** — same terms as the original project.
See [LICENSE](LICENSE). If you distribute a modified build, keep it GPLv3, keep the source available,
and keep this attribution.

<div align="center">
<br/>
<sub>Built for people who value their privacy. Use responsibly.</sub>
</div>
