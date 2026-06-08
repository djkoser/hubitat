/**
 * FanSync WiFi Fan — Hubitat Driver
 *
 * Interfaces with Fanimation's FanSync cloud API via WebSocket.
 * Protocol reverse-engineered by github.com/rotinom/fansync
 * and github.com/tjbaker/homeassistant-fansync (Apache 2.0).
 *
 * API endpoints:
 *   Auth:      POST https://fanimation.apps.exosite.io/api:1/session
 *   WebSocket: wss://fanimation.apps.exosite.io/api:1/phone
 *
 * Protocol keys:
 *   H00 fan power (0=off, 1=on)
 *   H01 preset   (0=normal, 1=fresh_air)
 *   H02 fan speed (1-100)
 *   H06 direction (0=forward, 1=reverse)
 *   H0B light power (0=off, 1=on)
 *   H0C light brightness (1-100)
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
	definition(name: "FanSync WiFi Fan", namespace: "fansync", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/fansync-wifi-fan.groovy") {
		capability "Initialize"
		capability "Switch"
		capability "FanControl"
		capability "SwitchLevel"

		attribute "fanDirection",      "enum",        ["forward", "reverse"]
		attribute "lightSwitch",       "enum",        ["on", "off"]
		attribute "lightLevel",        "number"
		attribute "connectionStatus",  "enum",        ["connected", "disconnected", "connecting"]

		command "lightOn"
		command "lightOff"
		command "setLightLevel",   [[name: "level*",     type: "NUMBER",
									 description: "Light brightness 1-100"]]
		command "setFanDirection", [[name: "direction*", type: "ENUM",
									 constraints: ["forward", "reverse"]]]
		command "discoverDevices"
		command "reconnect"
	}

	preferences {
		input "email",       "string",   title: "FanSync Email",    required: true
		input "password",    "password", title: "FanSync Password", required: true
		input "fanDeviceId", "string",   title: "Device ID (leave blank to auto-discover)"
		input "pollSecs",    "number",   title: "Poll interval seconds (0 = push only)", defaultValue: 60
		input "logEnable",   "bool",     title: "Enable debug logging", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
	state.reqId = 3
	state.lastSpeed = "medium"
	sendEvent(name: "supportedFanSpeeds",
			  value: JsonOutput.toJson(["low","medium-low","medium","medium-high","high","on","off","auto"]))
	initialize()
}

def updated() {
	initialize()
}

def uninstalled() {
	interfaces.webSocket.close()
}

def initialize() {
	unschedule()
	try { interfaces.webSocket.close() } catch (ignored) {}
	if (logEnable) runIn(1800, "logsOff")
	state.phase = "http_login"
	state.token = null
	sendEvent(name: "connectionStatus", value: "connecting")
	httpLogin()
}

def logsOff() {
	log.warn "FanSync: debug logging disabled"
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── HTTP Login ────────────────────────────────────────────────────────────────

private httpLogin() {
	asynchttpPost("httpLoginCallback", [
		uri:                "https://fanimation.apps.exosite.io",
		path:               "/api:1/session",
		requestContentType: "application/json",
		contentType:        "application/json",
		body:               [email: email, password: password],
		timeout:            20
	])
}

def httpLoginCallback(response, data) {
	if (response.getStatus() != 200) {
		log.error "FanSync login failed: HTTP ${response.getStatus()}"
		scheduleReconnect(60)
		return
	}
	def json
	try { json = new JsonSlurper().parseText(response.getData()) }
	catch (e) { log.error "FanSync login parse error: ${e}"; scheduleReconnect(60); return }

	if (!json?.token) {
		log.error "FanSync login response missing token"
		scheduleReconnect(60)
		return
	}
	if (logEnable) log.debug "FanSync HTTP login OK"
	state.token = json.token
	state.phase = "ws_connecting"
	connectWebSocket()
}

// ── WebSocket ─────────────────────────────────────────────────────────────────

private connectWebSocket() {
	try {
		interfaces.webSocket.connect(
			"wss://fanimation.apps.exosite.io/api:1/phone",
			pingInterval:    30,
			ignoreSSLIssues: false,
			headers:         [:]
		)
	} catch (e) {
		log.error "WebSocket connect exception: ${e}"
		scheduleReconnect(30)
	}
}

def webSocketStatus(String status) {
	if (logEnable) log.debug "WebSocket status: ${status}"
	if (status.startsWith("status: open")) {
		state.phase = "ws_login"
		wsLogin()
	} else if (status.startsWith("failure:") || status.startsWith("status: closing")) {
		sendEvent(name: "connectionStatus", value: "disconnected")
		scheduleReconnect(30)
	}
}

private wsLogin() {
	wsSend([id: 1, request: "login", data: [token: state.token]])
	if (logEnable) log.debug "Sent WS login"
}

private wsListDevices() {
	wsSend([id: 2, request: "lst_device"])
}

// ── Message Dispatch ──────────────────────────────────────────────────────────

def parse(String raw) {
	if (logEnable) log.debug "WS recv [${state.phase}]: ${raw}"
	def json
	try { json = new JsonSlurper().parseText(raw) }
	catch (e) { log.warn "Unparseable WS message: ${raw}"; return }
	if (!(json instanceof Map)) return

	switch (state.phase) {
		case "ws_login":
			// id==1 is our login request; anything else is a server greeting — skip it.
			if (json?.id != 1) return
			if (json?.status == "ok") {
				if (logEnable) log.debug "WS login OK"
				state.phase = "ws_list_devices"
				wsListDevices()
			} else {
				log.error "WS login rejected: ${raw}"
				scheduleReconnect(60)
			}
			break

		case "ws_list_devices":
			if (json?.id != 2) return
			def devices = json?.data ?: []
			def firstId = devices.find { it?.device }?.device
			if (firstId && !fanDeviceId) {
				state.discoveredDeviceId = firstId
				log.info "FanSync auto-discovered device: ${firstId}"
			}
			if (logEnable) log.debug "Devices: ${devices*.device}"
			state.phase = "ready"
			unschedule("initialize") // cancel any pending reconnect now that we're connected
			sendEvent(name: "connectionStatus", value: "connected")
			pollStatus()
			schedulePoll()
			break

		case "ready":
			onReadyMessage(json)
			break
	}
}

private onReadyMessage(Map json) {
	def status = extractStatus(json)
	if (status) updateAttributes(status)
}

private Map extractStatus(Map json) {
	def data = json?.data
	if (!(data instanceof Map)) return null
	if (data?.status instanceof Map)          return data.status
	if (data?.changes?.status instanceof Map) return data.changes.status
	return null
}

// ── Commands ──────────────────────────────────────────────────────────────────

def on() {
	fanSet(["H00": 1])
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} switch is on")
}

def off() {
	fanSet(["H00": 0])
	sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} switch is off")
}

def setLevel(def level, def duration = null) {
	def pct = clamp(level as Integer, 1, 100)
	fanSet(["H00": 1, "H02": pct])
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
	fanSet(["H00": 1, "H02": pct])
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
	fanSet(["H06": (direction == "reverse") ? 1 : 0])
	sendEvent(name: "fanDirection", value: direction, descriptionText: "${device.displayName} fan direction is ${direction}")
}

def lightOn() {
	fanSet(["H0B": 1])
	sendEvent(name: "lightSwitch", value: "on", descriptionText: "${device.displayName} light is on")
}

def lightOff() {
	fanSet(["H0B": 0])
	sendEvent(name: "lightSwitch", value: "off", descriptionText: "${device.displayName} light is off")
}

def setLightLevel(def level) {
	def pct = clamp(level as Integer, 1, 100)
	fanSet(["H0B": 1, "H0C": pct])
	sendEvent(name: "lightLevel", value: pct, unit: "%", descriptionText: "${device.displayName} light level is ${pct}%")
	sendEvent(name: "lightSwitch", value: "on", descriptionText: "${device.displayName} light is on")
}

def discoverDevices() {
	if (state.phase == "ready") {
		state.phase = "ws_list_devices"
		wsListDevices()
	} else {
		log.warn "discoverDevices: not connected (phase=${state.phase})"
	}
}

def reconnect() { initialize() }

def pollStatus() {
	def did = activeDeviceId()
	if (!did || state.phase != "ready") return
	wsSend([id: nextReqId(), request: "get", device: did])
}

// ── Internal Helpers ──────────────────────────────────────────────────────────

private fanSet(Map keys) {
	def did = activeDeviceId()
	if (!did) { log.warn "fanSet: no device ID configured or discovered"; return }
	if (state.phase != "ready") { log.warn "fanSet: not connected (phase=${state.phase})"; return }
	wsSend([id: nextReqId(), request: "set", device: did, data: keys])
}

private wsSend(Map payload) {
	def msg = JsonOutput.toJson(payload)
	if (logEnable) log.debug "WS send: ${msg}"
	interfaces.webSocket.sendMessage(msg)
}

private updateAttributes(Map status) {
	def dn = device.displayName
	if (status["H00"] != null) {
		def v = status["H00"] == 1 ? "on" : "off"
		sendEvent(name: "switch", value: v, descriptionText: "${dn} switch is ${v}")
	}
	if (status["H02"] != null) {
		def pct = status["H02"] as Integer
		sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${dn} level is ${pct}%")
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
	if (status["H02"] != null) {
		def pct = status["H02"] as Integer
		def spd = pct <= 0 ? "off" : pct <= 30 ? "low" : pct <= 45 ? "medium-low" : pct <= 60 ? "medium" : pct <= 85 ? "medium-high" : "high"
		sendEvent(name: "speed", value: spd, descriptionText: "${dn} speed is ${spd}")
	}
}

private String activeDeviceId() {
	return (fanDeviceId ?: state.discoveredDeviceId) as String ?: null
}

private int nextReqId() {
	if (!state.reqId || state.reqId < 3) state.reqId = 3
	def id = state.reqId as Integer
	state.reqId = id + 1
	return id
}

private int clamp(int value, int min, int max) {
	return Math.max(min, Math.min(max, value))
}

private scheduleReconnect(int delaySecs) {
	sendEvent(name: "connectionStatus", value: "disconnected")
	runIn(delaySecs, "initialize")
}

private schedulePoll() {
	def secs = pollSecs ? pollSecs as Integer : 0
	if (secs > 0) runIn(secs, "scheduledPoll")
}

def scheduledPoll() {
	pollStatus()
	schedulePoll()
}
