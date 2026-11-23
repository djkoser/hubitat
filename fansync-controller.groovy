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
	// Schedule the debug auto-off ONLY here: initialize() runs on every
	// reconnect, and rescheduling there kept pushing the 30-min timer out.
	if (logEnable) runIn(1800, "logsOff")
	initialize()
}

def uninstalled() {
	interfaces.webSocket.close()
}

def initialize() {
	// Cancel only our own reconnect/poll jobs — a blanket unschedule() would
	// also cancel the pending logsOff and leave debug logging on indefinitely.
	unschedule("initialize")
	unschedule("scheduledPoll")
	// Mark the close as deliberate so the resulting "status: closing" callback
	// doesn't schedule a spurious reconnect (and double the backoff).
	state.intentionalCloseAt = now()
	try { interfaces.webSocket.close() } catch (ignored) {}
	state.pending = [:]
	if (!email || !password) {
		log.warn "FanSync: email/password not configured"
		return
	}
	sendEvent(name: "connectionStatus", value: "connecting")
	if (state.token && !tokenExpired()) {
		// Reuse the cached token — the session endpoint rate-limits (HTTP 429),
		// so only log in over HTTP when we don't have a live token.
		state.phase = "ws_connecting"
		connectWebSocket()
	} else {
		state.token = null
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
	def status = response.getStatus() as Integer
	if (status >= 400 && status < 500 && status != 408) {
		// Client error (400 malformed/invalid credentials, 401/403 rejected,
		// etc.) — the same request will fail the same way, so retrying only
		// hammers the rate-limited session endpoint forever. Stop; the user
		// must fix email/password and Save. 408 (timeout) and 429 (handled
		// above) are transient and still retry.
		log.error "FanSync login rejected (HTTP ${status}) — check email/password and Save Preferences"
		unschedule("initialize")
		state.reconnectDelay = null
		sendEvent(name: "connectionStatus", value: "disconnected")
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
	} else if (status.startsWith("status: closing") && state.intentionalCloseAt &&
			   now() - (state.intentionalCloseAt as Long) < 10000L) {
		// Echo of our own close in initialize() — a new connection is already
		// underway. Time-windowed (not a one-shot flag) because the old socket's
		// "closing" can arrive before or after the new socket's "open".
		if (logEnable) log.debug "Ignoring close of previous socket"
	} else if (status.startsWith("failure:") || status.startsWith("status: closing")) {
		scheduleReconnect()
	}
}

// ── Message Dispatch ──────────────────────────────────────────────────────────

def parse(String raw) {
	state.lastRx = now()
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
	// Device list from discoverDevices() — handled without leaving "ready",
	// so fan commands and status pushes keep flowing during discovery.
	if (json?.id == 2) {
		syncChildren((json?.data instanceof List) ? json.data : [])
		return
	}
	// Responses to our pending get/set requests — matched by id.
	// Set acks include a fresh data.status, so they confirm state too.
	if (json?.id != null) {
		def did = state.pending?.remove(json.id.toString())
		def status = extractStatus(json)
		if (did && status) routeStatus(did, status)
		return
	}
	// Unsolicited pushes — device id at top level or inside data,
	// status at data.status or data.changes.status (shapes vary by API version).
	def did = json?.device ?: json?.data?.device
	def status = extractStatus(json)
	if (did && status) routeStatus(did as String, status)
}

private Map extractStatus(Map json) {
	def data = json?.data
	if (!(data instanceof Map)) return null
	if (data.status instanceof Map)          return data.status
	if (data.changes?.status instanceof Map) return data.changes.status
	return null
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

// Called by child devices — send protocol keys to this child's fan.
// Returns true only if the command was actually sent, so the child can skip
// its optimistic state update when the command was dropped.
boolean componentSet(cd, Map keys) {
	def did = cd.getDataValue("fansyncId")
	if (!did) { log.warn "componentSet: ${cd.displayName} has no fansyncId"; return false }
	if (state.phase != "ready") { log.warn "componentSet: ${cd.displayName} command dropped — not connected (phase=${state.phase})"; return false }
	def id = nextReqId()
	state.pending[id.toString()] = did // set acks carry data.status — route it back
	wsSend([id: id, request: "set", device: did, data: keys])
	return true
}

void componentRefresh(cd) {
	pollDevice(cd.getDataValue("fansyncId"))
}

// ── Commands ──────────────────────────────────────────────────────────────────

def discoverDevices() {
	if (state.phase == "ready") {
		// Stay in "ready": onReadyMessage() picks up the id-2 list response.
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
	// A dead socket never answers, so orphaned entries accumulate — cap the map
	if (state.pending.size() > 40) {
		log.warn "FanSync: pruning ${state.pending.size()} unanswered requests"
		state.pending = [:]
	}
	def id = nextReqId()
	state.pending[id.toString()] = did
	wsSend([id: id, request: "get", device: did])
}

private wsSend(Map payload) {
	def msg = JsonOutput.toJson(payload)
	if (logEnable) log.debug "WS send: ${msg}"
	interfaces.webSocket.sendMessage(msg)
}

// The session token is a JWT — check its exp claim locally so we never burn a
// WebSocket connect on a token we already know is dead (per tjbaker/homeassistant-fansync).
private boolean tokenExpired() {
	try {
		def parts = state.token.split("\\.")
		if (parts.size() != 3) return false // not a JWT — let the server judge it
		def b64 = parts[1].replace('-', '+').replace('_', '/')
		while (b64.length() % 4 != 0) b64 += "="
		def claims = new JsonSlurper().parseText(new String(b64.decodeBase64(), "UTF-8"))
		if (!(claims?.exp instanceof Number)) return false
		// Treat anything within 60s of expiry as expired
		return (claims.exp as Long) * 1000L < now() + 60000L
	} catch (e) {
		if (logEnable) log.debug "tokenExpired parse failure: ${e}"
		return false
	}
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
	// Silence watchdog: polls should produce responses. If nothing has arrived
	// in 3 poll intervals (min 180s), the socket is half-open — pings can pass
	// while data doesn't — so rebuild the connection (token is reused, no login).
	def secs = pollSecs ? pollSecs as Integer : 60
	def silentMs = Math.max(3 * secs, 180) * 1000L
	if (state.lastRx && now() - (state.lastRx as Long) > silentMs) {
		log.warn "FanSync: no traffic in ${(now() - (state.lastRx as Long)) / 1000}s — reconnecting"
		initialize()
		return
	}
	refresh()
	schedulePoll()
}
