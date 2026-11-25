/**
 * FanSync Fan — Hubitat Child Driver
 *
 * Child of "FanSync Controller", which holds the shared credentials and the
 * single WebSocket to Fanimation's FanSync cloud. One child per fan; the
 * controller creates these automatically on connect/discoverDevices.
 *
 * All cloud I/O goes through the parent (parent.componentSet / componentRefresh);
 * the parent pushes status back in via parseStatus().
 *
 * Protocol keys (Fanimation information model, /api:1/info-model):
 *   H00 fan power (0=off, 1=on)
 *   H01 fan mode  (0=normal, 1=natural breeze; preset "fresh_air", and speed "auto")
 *   H02 fan speed (1-100; speed changes need H00=1 first)
 *   H04 light color temperature (3000/4000/5000 K, L3 models)
 *   H05 learn-load timer (FDR2 models, read-only)
 *   H06 direction (0=forward, 1=reverse)
 *   H0B light power (0=off, 1=on)
 *   H0C light brightness (1-100)
 *   H0D Home Shield (0=off, 1=on)
 *   H0E error code (0 = none; 250-269 = fault, see ERRORS)
 *
 * Speed: when the model's information model lists fixed speed presets (e.g. FD6 fans:
 * 20/35/50/65/80/100), the controller stores them in the "speedPresets" data value;
 * levels snap to the nearest preset and the named speeds map to the top five
 * (the lowest preset also reports as "low"). Otherwise speed is 1-100.
 *
 * The light has its own "FanSync Light" device (created by the controller when the
 * fan model has a light kit). The light commands below are kept for existing rules.
 */

import groovy.json.JsonOutput

metadata {
	definition(name: "FanSync Fan", namespace: "fansync", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/fansync-fan.groovy") {
		capability "Switch"
		capability "FanControl"
		capability "SwitchLevel"
		capability "Refresh"

		attribute "fanDirection", "enum",   ["forward", "reverse"]
		attribute "preset",       "enum",   ["normal", "fresh_air"]
		attribute "lightSwitch",  "enum",   ["on", "off"]
		attribute "lightLevel",   "number"
		attribute "homeShield",   "enum",   ["on", "off"]
		attribute "errorCode",    "number"
		attribute "errorText",    "string"
		attribute "learnLoad",    "number"

		command "lightOn"
		command "lightOff"
		command "setLightLevel",   [[name: "level*",     type: "NUMBER",
									 description: "Light brightness 1-100"]]
		command "setFanDirection", [[name: "direction*", type: "ENUM",
									 constraints: ["forward", "reverse"]]]
		command "setPreset",       [[name: "preset*",    type: "ENUM",
									 constraints: ["normal", "fresh_air"]]]
		command "setHomeShield",   [[name: "state*",     type: "ENUM",
									 constraints: ["on", "off"]]]
	}

	preferences {
		input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
	state.lastSpeed = "medium"
	publishSupportedSpeeds()
}

def updated() {
	if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
	log.warn "${device.displayName}: debug logging disabled"
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── Commands ──────────────────────────────────────────────────────────────────
// Each command updates state only if the parent actually sent it; a dropped
// command (controller disconnected) must not leave a phantom on/off state.

def on() {
	if (!parent.componentSet(device, ["H00": 1])) return
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} switch is on")
}

def off() {
	if (!parent.componentSet(device, ["H00": 0])) return
	sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} switch is off")
}

def setLevel(def level, def duration = null) {
	if (toInt(level) <= 0) { off(); return }
	def pct = snapSpeed(clamp(toInt(level), 1, 100))
	if (!parent.componentSet(device, ["H00": 1, "H01": 0, "H02": pct])) return
	sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${device.displayName} level is ${pct}%")
}

// "auto" = Fanimation's natural breeze mode (H01=1), which varies the speed itself.
// Any named speed or level switches breeze back off (H01=0), as the fan's own app does.
def setSpeed(String speed) {
	if (speed == "off") { off(); return }
	if (speed == "on")  { setSpeed(state.lastSpeed ?: "medium"); return }
	if (speed == "auto") { setPreset("fresh_air"); return }
	def pct = speedToPct(speed)
	if (!parent.componentSet(device, ["H00": 1, "H01": 0, "H02": pct])) return
	state.lastSpeed = speed
	sendEvent(name: "speed", value: speed, descriptionText: "${device.displayName} speed is ${speed}")
}

def cycleSpeed() {
	def order = SPEED_NAMES
	def current = device.currentValue("speed") ?: "off"   // from "auto" or "off" this starts at low
	def idx = order.indexOf(current)
	def next = (idx >= 0 && idx < order.size() - 1) ? order[idx + 1] : order[0]
	setSpeed(next)
}

def setFanDirection(String direction) {
	if (!parent.componentSet(device, ["H00": 1, "H06": (direction == "reverse") ? 1 : 0])) return
	sendEvent(name: "fanDirection", value: direction, descriptionText: "${device.displayName} fan direction is ${direction}")
}

def setPreset(String preset) {
	// H01: 0=normal, 1=fresh_air (breeze). Speed changes reset this to normal.
	def breeze = preset == "fresh_air"
	if (!parent.componentSet(device, ["H00": 1, "H01": breeze ? 1 : 0])) return
	sendEvent(name: "preset", value: preset, descriptionText: "${device.displayName} preset is ${preset}")
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} switch is on")
	if (breeze) state.lastSpeed = "auto"
	def spd = breeze ? "auto" : pctToSpeed((device.currentValue("level") ?: 50) as Integer)
	sendEvent(name: "speed", value: spd, descriptionText: "${device.displayName} speed is ${spd}")
}

def setHomeShield(String value) {
	def on = value == "on"
	if (!parent.componentSet(device, ["H0D": on ? 1 : 0])) return
	sendEvent(name: "homeShield", value: on ? "on" : "off", descriptionText: "${device.displayName} Home Shield is ${on ? 'on' : 'off'}")
}

def lightOn() {
	if (!parent.componentSet(device, ["H0B": 1])) return
	sendEvent(name: "lightSwitch", value: "on", descriptionText: "${device.displayName} light is on")
}

def lightOff() {
	if (!parent.componentSet(device, ["H0B": 0])) return
	sendEvent(name: "lightSwitch", value: "off", descriptionText: "${device.displayName} light is off")
}

def setLightLevel(def level) {
	def pct = clamp(toInt(level), 1, 100)
	if (!parent.componentSet(device, ["H0B": 1, "H0C": pct])) return
	sendEvent(name: "lightLevel", value: pct, unit: "%", descriptionText: "${device.displayName} light level is ${pct}%")
	sendEvent(name: "lightSwitch", value: "on", descriptionText: "${device.displayName} light is on")
}

def refresh() {
	parent.componentRefresh(device)
}

// ── Status from Parent ────────────────────────────────────────────────────────

// Called by the FanSync Controller when the cloud reports state for this fan
void parseStatus(Map status) {
	if (logEnable) log.debug "${device.displayName} status: ${status}"
	def dn = device.displayName
	if (status["H00"] != null) {
		def v = status["H00"] == 1 ? "on" : "off"
		sendEvent(name: "switch", value: v, descriptionText: "${dn} switch is ${v}")
	}
	publishSupportedSpeeds()
	if (status["H01"] != null) {
		def v = status["H01"] == 1 ? "fresh_air" : "normal"
		sendEvent(name: "preset", value: v, descriptionText: "${dn} preset is ${v}")
	}
	boolean breeze = (status["H01"] != null ? status["H01"] : (device.currentValue("preset") == "fresh_air" ? 1 : 0)) == 1
	if (status["H02"] != null) {
		def pct = status["H02"] as Integer
		sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${dn} level is ${pct}%")
	}
	if (status["H01"] != null || status["H02"] != null) {
		def spd = breeze ? "auto" : pctToSpeed((device.currentValue("level") ?: 0) as Integer)
		sendEvent(name: "speed", value: spd, descriptionText: "${dn} speed is ${spd}")
	}
	if (status["H06"] != null) {
		def v = status["H06"] == 1 ? "reverse" : "forward"
		sendEvent(name: "fanDirection", value: v, descriptionText: "${dn} fan direction is ${v}")
	}
	if (status["H0B"] != null) {
		def v = status["H0B"] == 1 ? "on" : "off"
		sendEvent(name: "lightSwitch", value: v, descriptionText: "${dn} light is ${v}")
	}
	if (status["H0C"] != null) {
		def pct = status["H0C"] as Integer
		sendEvent(name: "lightLevel", value: pct, unit: "%", descriptionText: "${dn} light level is ${pct}%")
	}
	if (status["H0D"] != null) {
		def v = status["H0D"] == 1 ? "on" : "off"
		sendEvent(name: "homeShield", value: v, descriptionText: "${dn} Home Shield is ${v}")
	}
	if (status["H05"] != null) sendEvent(name: "learnLoad", value: status["H05"] as Integer)
	if (status["H0E"] != null) {
		def code = status["H0E"] as Integer
		def text = code == 0 ? "none" : (ERRORS[code] ?: "Unknown error ${code}")
		if (code != 0 && device.currentValue("errorCode") != code) log.warn "${dn}: fan reports error ${code} (${text})"
		sendEvent(name: "errorCode", value: code)
		sendEvent(name: "errorText", value: text)
	}
}

// H0E error codes from Fanimation's information model (errorFields)
@groovy.transform.Field static final Map ERRORS = [
	250: "Parameter error", 251: "Parameter length error", 252: "Memory allocate failed", 253: "Send error",
	254: "Unknown commands", 255: "Unknown fields", 256: "Too many entries", 257: "Under voltage",
	258: "Over voltage", 259: "Over current", 260: "Under speed", 261: "Over speed", 262: "Stalled fault",
	263: "Open phase fault", 264: "Chase max speed", 265: "Over temperature", 266: "EEPROM error",
	267: "Remote control did not learn", 268: "Driver IC not response", 269: "ACZ abnormal"
]

// ── Internal Helpers ──────────────────────────────────────────────────────────

@groovy.transform.Field static final List SPEED_NAMES = ["low", "medium-low", "medium", "medium-high", "high"]

// Speeds offered to dashboards/Google: the five named speeds plus auto (breeze).
// On/off are left out on purpose: they're the fan's Switch, not speeds.
private void publishSupportedSpeeds() {
	def json = JsonOutput.toJson(SPEED_NAMES + ["auto"])
	if (device.currentValue("supportedFanSpeeds") != json) sendEvent(name: "supportedFanSpeeds", value: json)
}

private List speedPresets() {
	(getDataValue("speedPresets") ?: "").split(",").findAll { it }.collect { it as Integer }.sort()
}

// Named speeds use the top five presets when the model has fixed speeds
private int speedToPct(String speed) {
	def p = speedPresets()
	def idx = SPEED_NAMES.indexOf(speed == "auto" ? "medium" : speed)
	if (idx < 0) idx = 2
	if (p.size() >= 5) return p[p.size() - 5 + idx]
	return [25, 38, 50, 75, 100][idx]
}

private String pctToSpeed(int pct) {
	if (pct <= 0) return "off"
	def p = speedPresets()
	if (p.size() >= 5) {
		def top = p.drop(p.size() - 5)
		def nearest = top.min { Math.abs(it - pct) }
		return SPEED_NAMES[top.indexOf(nearest)]
	}
	return pct <= 30 ? "low" : pct <= 45 ? "medium-low" : pct <= 60 ? "medium" : pct <= 85 ? "medium-high" : "high"
}

private int snapSpeed(int pct) {
	def p = speedPresets()
	return p ? (p.min { Math.abs(it - pct) } as int) : pct
}

// Commands may arrive as numbers or strings (e.g. "50.0" from a dashboard)
private int toInt(v) { new BigDecimal(v.toString().trim()).setScale(0, BigDecimal.ROUND_HALF_UP).intValue() }

private int clamp(int value, int min, int max) {
	return Math.max(min, Math.min(max, value))
}
