# Hubitat Apps & Drivers

Private Hubitat Elevation apps and drivers, installed through Hubitat Package Manager (HPM)
from `packageManifest.json` (repository listing: `repository.json`).

## Apps

- **Safety Monitor** - HSM replacement. Arms when every lock has stayed locked for a set time, disarms on any unlock; a door opening while armed texts, announces (repeating until cancelled) and turns on camera spotlights and sirens. Smoke, CO and leak alerts at all times, with main water valve shutoff on a leak. Offline-device alerts and a nightly "not armed" reminder. While armed, person/vehicle detections from Hubitat's built-in Reolink Integration are texted directly (per-camera cooldown); cameras also provide the spotlights and sirens.
- **ERV Auto Controller** - Runs an ERV and air handler as On / Recirculate / Off from any number of indoor and outdoor air monitors (CO2, VOC, NOx, PM2.5): the worst room decides, offline monitors are skipped, CO2 is judged relative to outdoor CO2, PM2.5 can be filtered below outdoor levels, outdoor air blocks ventilation only on evidence of a plume (PM2.5, NOx, a strong VOC spike, or a ventilation test in which indoor VOC rose instead of falling), flags use hysteresis, each piece of equipment has a minimum run and minimum off time to avoid short-cycling, and a fail-safe applies when a whole side goes offline. Publishes its state to the `ervAutoState` / `ervAutoReason` hub variables, and keeps a view-only **ERV Status** device (On / Recirculate / Off, from the relays in every mode) for dashboards and Google Home.
- **Bathroom Humidity Fan** - One instance per bathroom. Runs the exhaust fan from humidity relative to a reference sensor; refuses an off while it's still humid (with an LED blink on the switch), a double press forces it off, and a time limit applies.
- **Enhanced Battery Monitor** - Notifies once per device each time its battery crosses a threshold (very high, high, medium, low, critical).

Each app keeps its own settings on its own page. Hub variables are used only for values shared
across apps or dashboards (the published outputs above).

## Drivers

- **FanSync Controller / FanSync Fan / FanSync Light** - Fanimation FanSync cloud integration. The controller reads Fanimation's information model (`/api:1/info-model`) and sets each device up from its model: fixed speed presets, breeze mode (speed "auto"), direction, Home Shield, fault codes, and a separate light device per light (e.g. TorchAire down and up lights) with dimming and color-temperature presets.
- **AirGradient Local** - Polls AirGradient ONE / Open Air monitors over the local API and reports corrected PM2.5, temperature and humidity, plus CO2, VOC/NOx indices and US AQI; sets AirGradient's recommended corrections on the monitor.
- **IKEA Tradfri Remote Control (E1810) - Power Hold** - E1810 five-button remote driver that reports holding the Power button as "held 1" (stock drivers misreport it as a Prev press).

## Build checks and tests

```
./gradlew test
```

- **Compile checks** (`test/CompileSpec.groovy`) - every `.groovy` file at the repo root is compiled inside [hubitat_ci](https://github.com/biocomp/hubitat_ci)'s emulated Hubitat sandbox (no threads, exec, reflection, custom classes, `println`), apps' definitions and pages are validated, and every import is checked against the hub's class allowlist (`test/support/ImportPolicy.groovy`). New files are picked up automatically.
- **Unit tests** (`test/*Spec.groovy`, Spock) - the apps' decision logic and the drivers' protocol handling, run against fake devices and a fake hub (`test/support`). Fixtures in `test/fixtures` are captured from real devices and Fanimation's cloud.
- **CI** - `.github/workflows/build.yml` runs the same on every push and pull request.

Requirements: a JDK 17+ to run Gradle, and JDK 11 for the tests (hubitat_ci 0.17 needs Groovy 2.5,
which doesn't run on JDK 16+). Point Gradle at the JDK 11 install, e.g. in `~/.gradle/gradle.properties`:

```
org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@11
```

hubitat_ci (2021) predates some current Hubitat features, so a few of its checks are relaxed or
shimmed; each is documented at the top of `test/CompileSpec.groovy`.
