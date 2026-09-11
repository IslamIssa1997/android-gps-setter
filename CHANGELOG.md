# Changelog

All notable changes to this project are documented here. Format based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses date-stamped versions.

## [2.1.5] — 2026-09-11

**Full Changelog**: https://github.com/IslamIssa1997/android-gps-setter/compare/v2.1.4...v2.1.5

## [2.1.4] — 2026-09-11

**Full Changelog**: https://github.com/IslamIssa1997/android-gps-setter/compare/v2.1.1...v2.1.4

## [2.1.1] — 2026-09-09

**Full Changelog**: https://github.com/IslamIssa1997/android-gps-setter/commits/v2.1.1

## [2.0.0] — 2026-09

Full rebuild of the module on the modern Xposed API, plus a large feature set. Packaged as
`com.islam.gps`.

### Changed
- Migrated from the legacy `de.robv.android.xposed` API to **libxposed API 102** — interceptor
  chains instead of `XC_MethodHook`, framework remote preferences instead of `XSharedPreferences`.
- Module-active state now comes from the real framework service binding rather than a self-hook.
- `libxposed-api` and `libxposed-service` are vendored from source (published 102 AARs require
  compileSdk 37 / AGP 9).
- API keys moved out of source into `local.properties` / CI secrets; no keys in the repo.
- Google Maps build only; the FOSS (MapLibre/microG) variant removed.

### Added
- Complete, self-consistent fake fix: altitude, mean sea level, speed, bearing and every accuracy
  field, each **Off**, **Fixed**, or **Auto** (Auto uses a real elevation/geoid lookup).
- Dynamic speed and bearing while a route or the joystick drives the position.
- Per-app targeting: **stealth** (system-server side, invisible to the app) by ticking, or the
  **hook method** (app-side) via "Add to scope".
- GNSS: suppresses real satellite data and synthesizes a plausible constellation — on both the
  app side and the system side (stealth targets are covered against satellite checks too).
- Optional Wi-Fi identity and cell-tower spoofing.
- On-screen joystick, route navigation (draw-on-map, multi-stop, per-route speed up to 200 km/h),
  a resizable floating map window, address/Plus Code/national-address search bounded to the map view.
- Saved-favourite notes plus JSON import/export.
- Arabic localization with full RTL, in-app language picker.
- Optional broadcast control for automation; optional system-server hooks (default on).

### Fixed
- System-server hook install no longer aborts on the abstract `Registration.acceptLocationChange`.
- Synthetic GNSS delivers on the app's own callback thread; the system feed stops when a target
  app dies.
- Navigation is time-based, so reported speed matches the selected value exactly.
- The red pin persists across restarts once a location has been placed.

[2.0.0]: https://github.com/IslamIssa1997/android-gps-setter/releases
[2.1.1]: https://github.com/IslamIssa1997/android-gps-setter/releases/tag/v2.1.1
[2.1.4]: https://github.com/IslamIssa1997/android-gps-setter/releases/tag/v2.1.4
[2.1.5]: https://github.com/IslamIssa1997/android-gps-setter/releases/tag/v2.1.5
