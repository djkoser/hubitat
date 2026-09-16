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
 * Protocol keys:
 *   H00 fan power (0=off, 1=on)
 *   H01 preset   (0=normal, 1=fresh_air)
 *   H02 fan speed (1-100)
 *   H06 direction (0=forward, 1=reverse)
 *   H0B light power (0=off, 1=on)
 *   H0C light brightness (1-100)
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

		command "lightOn"
		command "lightOff"
		command "setLightLevel",   [[name: "level*",     type: "NUMBER",
									 description: "Light brightness 1-100"]]
		command "setFanDirection", [[name: "direction*", type: "ENUM",
									 constraints: ["forward", "reverse"]]]
		command "setPreset",       [[name: "preset*",    type: "ENUM",
									 constraints: ["normal", "fresh_air"]]]
	}

	preferences {
		input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
	state.lastSpeed = "medium"
	sendEvent(name: "supportedFanSpeeds",
			  value: JsonOutput.toJson(["low","medium-low","medium","medium-high","high","on","off","auto"]))
}

def updated() {
	if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
	log.warn "${device.displayName}: debug logging disabled"
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── Commands ──────────────────────────────────────────────────────────────────

def on() {
	parent.componentSet(device, ["H00": 1])
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} switch is on")
}

def off() {
	parent.componentSet(device, ["H00": 0])
	sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} switch is off")
}

def setLevel(def level, def duration = null) {
	def pct = clamp(level as Integer, 1, 100)
	parent.componentSet(device, ["H00": 1, "H01": 0, "H02": pct])
	sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${device.displayName} level is ${pct}%")
}

def setSpeed(String speed) {
	def pctMap = [
		"low"        : 25,
		"medium-low" : 38,
		"medium"     : 50,
		"medium-high": 75,
		"high"       : 100,
		"auto"       : 50
	]
	if (speed == "off") { off(); return }
	if (speed == "on")  { setSpeed(state.lastSpeed ?: "medium"); return }
	def pct = pctMap[speed] ?: 50
	state.lastSpeed = speed
	parent.componentSet(device, ["H00": 1, "H01": 0, "H02": pct])
	sendEvent(name: "speed", value: speed, descriptionText: "${device.displayName} speed is ${speed}")
}

def cycleSpeed() {
	def order = ["low", "medium-low", "medium", "medium-high", "high"]
	def current = device.currentValue("speed") ?: "off"
	def idx = order.indexOf(current)
	def next = (idx >= 0 && idx < order.size() - 1) ? order[idx + 1] : order[0]
	setSpeed(next)
}

def setFanDirection(String direction) {
	parent.componentSet(device, ["H00": 1, "H06": (direction == "reverse") ? 1 : 0])
	sendEvent(name: "fanDirection", value: direction, descriptionText: "${device.displayName} fan direction is ${direction}")
}

def setPreset(String preset) {
	// H01: 0=normal, 1=fresh_air (breeze). Speed changes reset this to normal.
	parent.componentSet(device, ["H00": 1, "H01": (preset == "fresh_air") ? 1 : 0])
	sendEvent(name: "preset", value: preset, descriptionText: "${device.displayName} preset is ${preset}")
}

def lightOn() {
	parent.componentSet(device, ["H0B": 1])
	sendEvent(name: "lightSwitch", value: "on", descriptionText: "${device.displayName} light is on")
}

def lightOff() {
	parent.componentSet(device, ["H0B": 0])
	sendEvent(name: "lightSwitch", value: "off", descriptionText: "${device.displayName} light is off")
}

def setLightLevel(def level) {
	def pct = clamp(level as Integer, 1, 100)
	parent.componentSet(device, ["H0B": 1, "H0C": pct])
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
	if (status["H02"] != null) {
		def pct = status["H02"] as Integer
		sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${dn} level is ${pct}%")
		def spd = pct <= 0 ? "off" : pct <= 30 ? "low" : pct <= 45 ? "medium-low" : pct <= 60 ? "medium" : pct <= 85 ? "medium-high" : "high"
		sendEvent(name: "speed", value: spd, descriptionText: "${dn} speed is ${spd}")
	}
	if (status["H01"] != null) {
		def v = status["H01"] == 1 ? "fresh_air" : "normal"
		sendEvent(name: "preset", value: v, descriptionText: "${dn} preset is ${v}")
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
}

// ── Internal Helpers ──────────────────────────────────────────────────────────

private int clamp(int value, int min, int max) {
	return Math.max(min, Math.min(max, value))
}
