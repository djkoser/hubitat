/**
 * Virtual Security Keypad — Hubitat Driver
 *
 * A virtual device implementing the SecurityKeypad capability so an arm/disarm
 * state machine (e.g. HSM) can be surfaced to integrations that need a device:
 *  - Google Home Community's ArmDisarm trait defaults map 1:1 onto this driver
 *    (attribute "securityKeypad", commands disarm/armHome/armNight/armAway,
 *    exit delay attribute "exitAllowance")
 *  - HSM can use it as a keypad to arm/disarm and to reflect status
 *
 * The keypad itself applies no delays — state changes are immediate; exit/entry
 * windows belong to the automation layer (HSM or Rule Machine).
 */

import groovy.json.JsonOutput

metadata {
	definition(name: "Virtual Security Keypad", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/virtual-security-keypad.groovy") {
		capability "Actuator"
		capability "SecurityKeypad"
		capability "Switch"

		attribute "exitAllowance", "number"

		command "armNight"
	}

	preferences {
		input "exitDelaySecs", "number", title: "Exit delay reported to controllers (seconds)", defaultValue: 0
		input "logEnable",     "bool",   title: "Enable debug logging", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
	sendEvent(name: "securityKeypad", value: "disarmed", descriptionText: "${device.displayName} is disarmed")
	sendEvent(name: "switch", value: "off")
	sendEvent(name: "codeLength",     value: 4)
	sendEvent(name: "maxCodes",       value: 20)
	sendEvent(name: "lockCodes",      value: JsonOutput.toJson([:]))
	sendEvent(name: "exitAllowance",  value: 0)
}

def updated() {
	sendEvent(name: "exitAllowance", value: (exitDelaySecs ?: 0) as Integer)
	if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
	log.warn "${device.displayName}: debug logging disabled"
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── Arm / Disarm ─────────────────────────────────────────────────────────────

// Switch capability mirrors the arm state (on = armed away, off = disarmed) so
// integrations that only render switch-like controls (e.g. the Google Home app,
// whose SecuritySystem tile is unreliable without an On/Off trait) get a UI.
def on()  { armAway() }
def off() { disarm() }

def armAway()  { setKeypadState("armed away") }
def armHome()  { setKeypadState("armed home") }
def armNight() { setKeypadState("armed night") }
def disarm()   { setKeypadState("disarmed") }

private setKeypadState(String value) {
	if (logEnable) log.debug "securityKeypad -> ${value}"
	sendEvent(name: "securityKeypad", value: value, descriptionText: "${device.displayName} is ${value}")
	sendEvent(name: "switch", value: (value == "disarmed") ? "off" : "on")
}

// ── Delays ───────────────────────────────────────────────────────────────────

def setExitDelay(delay) {
	def secs = (delay ?: 0) as Integer
	device.updateSetting("exitDelaySecs", [value: secs, type: "number"])
	sendEvent(name: "exitAllowance", value: secs)
}

def setEntryDelay(delay) {
	state.entryDelay = (delay ?: 0) as Integer
}

// ── Lock Codes (minimal implementation) ──────────────────────────────────────

def setCodeLength(length) {
	sendEvent(name: "codeLength", value: length as Integer)
}

def setCode(position, pincode, name = null) {
	def codes = state.codes ?: [:]
	codes["${position}"] = [code: pincode, name: name ?: "code${position}"]
	state.codes = codes
	sendEvent(name: "codeChanged", value: "added")
	sendEvent(name: "lockCodes", value: JsonOutput.toJson(codes))
}

def deleteCode(position) {
	def codes = state.codes ?: [:]
	codes.remove("${position}")
	state.codes = codes
	sendEvent(name: "codeChanged", value: "deleted")
	sendEvent(name: "lockCodes", value: JsonOutput.toJson(codes))
}

def getCodes() {
	sendEvent(name: "lockCodes", value: JsonOutput.toJson(state.codes ?: [:]))
}
