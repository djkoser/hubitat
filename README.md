# Hubitat Private Applications Repository

This repository serves as a centralized location for my private Hubitat Elevation applications and integrations.

## Purpose

This repository is designed to:
- House custom Hubitat applications and device drivers
- Maintain version control for personal Hubitat automation projects
- Provide a structured approach to managing Hubitat package manifests
- Store documentation and configuration files for Hubitat integrations

## Repository Structure

- `packageManifest.json` - Package manifest file for Hubitat Package Manager integration
- `battery-monitor.groovy` - Enhanced Battery Monitor application
- Additional applications and drivers will be organized in dedicated folders

## Current Applications

- **Enhanced Battery Monitor** - Advanced battery monitoring application with configurable thresholds and intelligent notifications that sends alerts when devices cross battery level boundaries (high→medium→low)
- **Safety Monitor** - HSM replacement: arms when all locks are locked, disarms on any unlock, alerts (text, announcement, Reolink spotlights/sirens) on doors opened while armed; smoke/CO/leak alerts with main water valve shutoff; offline-device alerts
- **ERV Auto Controller** - Runs an ERV and air handler as On / Recirculate / Off from indoor and outdoor air quality (CO2, VOC, NOx, PM2.5), with hysteresis, minimum dwell and a stale-sensor fail-safe. All thresholds are hub variables

## Current Drivers

- **FanSync Controller / FanSync Fan / FanSync Light** - Parent/child cloud drivers for Fanimation FanSync fans and lights, built to Fanimation's information model: fan speed, direction, breeze mode, Home Shield, fault codes; light on/off, dimming and color temperature (3000/4000/5000 K) as a separate light device, detected per model
- **AirGradient Local** - Polls AirGradient ONE / Open Air monitors over the local API and reports corrected PM2.5, temperature and humidity; can set the recommended corrections on the monitor
- **IKEA Tradfri Remote Control (E1810) - Power Hold** - E1810 5-button remote driver that reports holding the Power button as "held 1" (stock drivers misreport it as a Prev press)

## Installation

Applications in this repository can be installed via:
1. Hubitat Package Manager (HPM) using the package manifest
2. Manual installation by copying code directly to your Hubitat hub
3. Direct import using raw GitHub URLs

## Usage

Each application will include its own documentation with installation instructions, configuration details, and usage examples.

## Author

David Koser

---

*This is a private repository for personal Hubitat development and automation projects.*