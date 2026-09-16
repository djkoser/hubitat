/**
 * FanSync Controller — Hubitat Parent Driver
 *
 * Holds ONE set of FanSync credentials, performs ONE login, and maintains
 * ONE WebSocket to Fanimation's FanSync cloud API. Creates a "FanSync Fan"
 * child device per fan on the account and routes commands/status between
 * the children and the cloud.
 *
 * Replaces per-fan instances of "FanSync WiFi Fan" — three fans logging in
 * independently (and each retrying on failure) trips Exosite's rate limit
 * (HTTP 429) on the session endpoint.
 *
 * Protocol reverse-engineered by github.com/rotinom/fansync
 * and github.com/tjbaker/homeassistant-fansync (Apache 2.0).
 *
 * API endpoints:
 *   Auth:      POST https://fanimation.apps.exosite.io/api:1/session
 *   WebSocket: wss://fanimation.apps.exosite.io/api:1/phone
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
	definition(name: "FanSync Controller", namespace: "fansync", author: "David Koser",
			   singleThreaded: true,
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/fansync-controller.groovy") {
		capability "Initialize"
		capability "Refresh"

		attribute "connectionStatus", "enum", ["connected", "disconnected", "connecting"]

		command "discoverDevices"
		command "reconnect"
	}

	preferences {
		input "email",     "text",     title: "FanSync Email",    required: true
		input "password",  "password", title: "FanSync Password", required: true
		input "pollSecs",  "number",   title: "Poll interval seconds (0 = push only)", defaultValue: 60
		input "logEnable", "bool",     title: "Enable debug logging", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
	state.reqId = 3
	initialize()
}

def updated() {
	// Credentials may have changed — force a fresh login
	state.token = null
	initialize()
}

def uninstalled() {
	interfaces.webSocket.close()
}

def initialize() {
	unschedule()
	try { interfaces.webSocket.close() } catch (ignored) {}
	if (logEnable) runIn(1800, "logsOff")
	state.pending = [:]
	if (!email || !password) {
		log.warn "FanSync: email/password not configured"
		return
	}
	sendEvent(name: "connectionStatus", value: "connecting")
	if (state.token) {
		// Reuse the cached token — the session endpoint rate-limits (HTTP 429),
		// so only log in over HTTP when we don't have a token or it's rejected.
		state.phase = "ws_connecting"
		connectWebSocket()
	} else {
		state.phase = "http_login"
		httpLogin()
	}
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
	if (response.getStatus() == 429) {
		log.warn "FanSync login rate-limited (HTTP 429) — backing off"
		scheduleReconnect()
		return
	}
	if (response.getStatus() != 200) {
		log.error "FanSync login failed: HTTP ${response.getStatus()}"
		scheduleReconnect()
		return
	}
	def json
	try { json = new JsonSlurper().parseText(response.getData()) }
	catch (e) { log.error "FanSync login parse error: ${e}"; scheduleReconnect(); return }

	if (!json?.token) {
		log.error "FanSync login response missing token"
		scheduleReconnect()
		return
	}
	if (logEnable) log.debug "FanSync HTTP login OK"
	state.token = json.token
	state.freshToken = true
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
		scheduleReconnect()
	}
}

def webSocketStatus(String status) {
	if (logEnable) log.debug "WebSocket status: ${status}"
	if (status.startsWith("status: open")) {
		state.phase = "ws_login"
		wsSend([id: 1, request: "login", data: [token: state.token]])
	} else if (status.startsWith("failure:") || status.startsWith("status: closing")) {
		scheduleReconnect()
	}
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
				state.freshToken = false
				state.reconnectDelay = 15
				state.phase = "ws_list_devices"
				wsSend([id: 2, request: "lst_device"])
			} else if (!state.freshToken) {
				// Cached token expired — get a fresh one, no backoff needed.
				log.info "FanSync: cached token rejected, logging in fresh"
				state.token = null
				state.phase = "http_login"
				httpLogin()
			} else {
				log.error "WS login rejected with fresh token: ${raw}"
				state.token = null
				scheduleReconnect()
			}
			break

		case "ws_list_devices":
			if (json?.id != 2) return
			def devices = (json?.data instanceof List) ? json.data : []
			syncChildren(devices)
			state.phase = "ready"
			unschedule("initialize") // cancel any pending reconnect now that we're connected
			sendEvent(name: "connectionStatus", value: "connected")
			refresh()
			schedulePoll()
			break

		case "ready":
			onReadyMessage(json)
			break
	}
}

private onReadyMessage(Map json) {
	// Unsolicited change events carry the device id directly
	if (json?.data?.device && json?.data?.changes?.status instanceof Map) {
		routeStatus(json.data.device as String, json.data.changes.status)
		return
	}
	// Responses to our "get" requests are matched to a device via the pending map
	if (json?.id != null && json?.data?.status instanceof Map) {
		def did = state.pending?.remove(json.id.toString())
		if (did) routeStatus(did, json.data.status)
	}
}

private routeStatus(String did, Map status) {
	def child = getChildDevice(childDni(did))
	if (!child) {
		if (logEnable) log.debug "Status for unknown device ${did} — run discoverDevices"
		return
	}
	child.parseStatus(status)
}

// ── Child Management ──────────────────────────────────────────────────────────

private String childDni(String did) {
	return "fansync-${did}"
}

private syncChildren(List devices) {
	if (logEnable) log.debug "Devices: ${devices*.device}"
	devices.each { d ->
		def did = d?.device
		if (!did) return
		def child = getChildDevice(childDni(did))
		if (!child) {
			def label = d?.properties?.displayName ?: "FanSync Fan ${did.toString().takeRight(6)}"
			child = addChildDevice("fansync", "FanSync Fan", childDni(did),
								   [name: "FanSync Fan", label: label, isComponent: false])
			log.info "FanSync: created child '${label}' for device ${did}"
		}
		child.updateDataValue("fansyncId", did as String)
	}
}

// Called by child devices — send protocol keys to this child's fan
void componentSet(cd, Map keys) {
	def did = cd.getDataValue("fansyncId")
	if (!did) { log.warn "componentSet: ${cd.displayName} has no fansyncId"; return }
	if (state.phase != "ready") { log.warn "componentSet: not connected (phase=${state.phase})"; return }
	wsSend([id: nextReqId(), request: "set", device: did, data: keys])
}

void componentRefresh(cd) {
	pollDevice(cd.getDataValue("fansyncId"))
}

// ── Commands ──────────────────────────────────────────────────────────────────

def discoverDevices() {
	if (state.phase == "ready") {
		state.phase = "ws_list_devices"
		wsSend([id: 2, request: "lst_device"])
	} else {
		log.warn "discoverDevices: not connected (phase=${state.phase})"
	}
}

def reconnect() {
	state.reconnectDelay = 15
	initialize()
}

def refresh() {
	getChildDevices().each { pollDevice(it.getDataValue("fansyncId")) }
}

// ── Internal Helpers ──────────────────────────────────────────────────────────

private pollDevice(String did) {
	if (!did || state.phase != "ready") return
	def id = nextReqId()
	state.pending[id.toString()] = did
	wsSend([id: id, request: "get", device: did])
}

private wsSend(Map payload) {
	def msg = JsonOutput.toJson(payload)
	if (logEnable) log.debug "WS send: ${msg}"
	interfaces.webSocket.sendMessage(msg)
}

private int nextReqId() {
	if (!state.reqId || state.reqId < 3) state.reqId = 3
	def id = state.reqId as Integer
	state.reqId = id + 1
	return id
}

private scheduleReconnect() {
	sendEvent(name: "connectionStatus", value: "disconnected")
	// Exponential backoff, 15s → 300s max, reset on successful connect —
	// keeps a broken account/outage from hammering the login endpoint.
	def delay = (state.reconnectDelay ?: 15) as Integer
	state.reconnectDelay = Math.min(delay * 2, 300)
	log.warn "FanSync: reconnecting in ${delay}s"
	runIn(delay, "initialize", [overwrite: true])
}

private schedulePoll() {
	def secs = pollSecs ? pollSecs as Integer : 0
	if (secs > 0) runIn(secs, "scheduledPoll")
}

def scheduledPoll() {
	refresh()
	schedulePoll()
}
