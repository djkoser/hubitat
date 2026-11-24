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
 *   Auth:        POST https://fanimation.apps.exosite.io/api:1/session
 *   WebSocket:   wss://fanimation.apps.exosite.io/api:1/phone
 *   Device spec: https://fanimation.apps.exosite.io/api:1/info-model (Fanimation's information
 *                model: per-model traits, key ranges, "before" rules and error codes)
 *
 * Each "get" response carries the device's model (data.profile.esh.model, e.g.
 * "TorchAire52-FD6R1LD5") and the keys it supports (data.fields). The controller loads the
 * information model (public, cached a day), matches the model against each entry's
 * familyMembers patterns, and builds the children from that entry:
 *   - a "FanSync Fan" for FAN-* entries, with the model's fixed speed presets if it has them
 *   - a "FanSync Light" per light trait (LIGHT = H0B/H0C, LIGHT2 = H09/H0A on dual-light
 *     models, H00/H02 on standalone lights), with the color temperature range and presets
 * If the information model can't be loaded, the model suffix and reported fields are used.
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
	fetchInfoModel()
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
		if (did && json?.data instanceof Map && (json.data.profile || json.data.fields)) ensureChildren(did, json.data)
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
	def prefix = childDni(did)
	def kids = getChildDevices().findAll { it.deviceNetworkId == prefix || it.deviceNetworkId.startsWith(prefix + "-") }
	if (!kids) {
		if (logEnable) log.debug "Status for unknown device ${did} — run discoverDevices"
		return
	}
	kids.each { it.parseStatus(status) }
}

// ── Child Management ──────────────────────────────────────────────────────────

private String childDni(String did) {
	return "fansync-${did}"
}

// The device list has names but no model, so children are created on each device's
// first "get" response (ensureChildren), which carries the model and supported keys.
private syncChildren(List devices) {
	if (logEnable) log.debug "Devices: ${devices*.device}"
	def names = state.deviceNames ?: [:]
	devices.each { d ->
		def did = d?.device
		if (!did) return
		names[did as String] = d?.properties?.displayName ?: "FanSync ${did.toString().takeRight(6)}"
	}
	state.deviceNames = names
	if (state.phase == "ready") names.keySet().each { pollDevice(it) }
}

private ensureChildren(String did, Map data) {
	String model = data?.profile?.esh?.model ?: ""
	List fields = (data?.fields instanceof List) ? data.fields.collect { it as String } : []
	String label = state.deviceNames?.get(did) ?: "FanSync ${did.takeRight(6)}"
	def spec = specFor(model) ?: guessSpec(model, fields)
	if (!spec) { log.warn "FanSync: '${label}' model '${model}' is not recognized; creating a fan only"; spec = [fan: true, lights: []] }

	def fan = getChildDevice(childDni(did))
	if (spec.fan) {
		if (!fan) {
			fan = addChildDevice("fansync", "FanSync Fan", childDni(did),
								 [name: "FanSync Fan", label: label, isComponent: false])
			log.info "FanSync: created fan '${label}' (${model ?: 'model unknown'})"
		}
		fan.updateDataValue("fansyncId", did)
		if (model) fan.updateDataValue("model", model)
		if (spec.specName) fan.updateDataValue("infoModel", spec.specName as String)
		if (fields) fan.updateDataValue("fields", fields.join(","))
		if (spec.speeds) fan.updateDataValue("speedPresets", spec.speeds.join(","))
		else fan.removeDataValue("speedPresets")
	} else if (fan) {
		log.warn "FanSync: '${label}' is a standalone light (${model}); its 'FanSync Fan' device can be deleted"
	}

	spec.lights.eachWithIndex { lt, i ->
		String suffix = lt.id == "LIGHT" ? "light" : (lt.id as String).toLowerCase()
		String dni = "fansync-${did}-${suffix}"
		def light = getChildDevice(dni)
		if (!light) {
			String lightLabel = !spec.fan ? label : (lt.id == "LIGHT" ? "${label} Light" : "${label} Light ${(lt.id as String).replaceAll(/\D/, '') ?: i + 1}")
			light = addChildDevice("fansync", "FanSync Light", dni,
								   [name: "FanSync Light", label: lightLabel, isComponent: false])
			log.info "FanSync: created light '${lightLabel}' (${model ?: 'model unknown'}, ${lt.id})"
		}
		light.updateDataValue("fansyncId", did)
		light.updateDataValue("lightId", lt.id as String)
		light.updateDataValue("standalone", spec.fan ? "false" : "true")
		if (model) light.updateDataValue("model", model)
		[powerKey: lt.power, levelKey: lt.level, cctKey: lt.cct, cctMin: lt.cctMin, cctMax: lt.cctMax,
		 cctPresets: lt.cctPresets ? lt.cctPresets.join(",") : null].each { k, v ->
			if (v != null) light.updateDataValue(k, v as String) else light.removeDataValue(k)
		}
		light.configureFeatures()
	}
}

// ── Fanimation information model ─────────────────────────────────────────────
// GET /api:1/info-model (public) describes every model: traits with their keys,
// ranges and "before" rules, UI components with preset values, and error codes.
// It is reduced to a small per-model summary and cached in state for a day.

private fetchInfoModel() {
	if (state.infoModel && state.infoModelAt && now() - (state.infoModelAt as Long) < 86400000L) return
	asynchttpGet("infoModelResponse", [uri: "https://fanimation.apps.exosite.io", path: "/api:1/info-model",
									   contentType: "text/plain", timeout: 30])
}

def infoModelResponse(response, data) {
	if (response.hasError() || response.getStatus() != 200) {
		log.warn "FanSync: could not load Fanimation's information model (HTTP ${response.getStatus()}); using model-name rules"
		return
	}
	def json
	try { json = new JsonSlurper().parseText(response.getData()) } catch (e) { log.warn "FanSync: information model unreadable: ${e}"; return }
	def summary = [:]
	json.each { String name, entry ->
		def im = entry?.informationModel
		if (!(im instanceof Map)) return
		def comps = (im.components instanceof Map) ? im.components : [:]
		def presetsFor = { String key ->
			def out = null
			comps.each { cn, c -> c?.models?.each { m -> if (m?.key == key && m.values instanceof List) out = m.values*.value.collect { it as Integer }.sort() } }
			return out
		}
		def lights = []
		im.integration?.traits?.each { t ->
			if (t?.appliedDomain || t?.type != "light") return
			def a = t.attributes ?: [:]
			if (!a.on_off?.key) return
			lights << [id: t.id, power: a.on_off.key, level: a.brightness?.key ?: a.percentage?.key,
					   cct: a.color_temperature?.key, cctMin: a.color_temperature?.min, cctMax: a.color_temperature?.max,
					   cctPresets: a.color_temperature?.key ? presetsFor(a.color_temperature.key as String) : null]
		}
		summary[name] = [patterns: im.familyMembers ?: [], fan: name.startsWith("FAN"), lights: lights,
						 speeds: name.startsWith("FAN") ? presetsFor("H02") : null]
	}
	state.infoModel = summary
	state.infoModelAt = now()
	log.info "FanSync: loaded Fanimation information model (${summary.size()} models)"
	if (state.phase == "ready") refresh()   // reconfigure children from the spec
}

private Map specFor(String model) {
	if (!model || !(state.infoModel instanceof Map)) return null
	def hit = state.infoModel.find { name, s -> s.patterns.any { p -> (model =~ (p as String)).find() } }
	return hit ? (hit.value + [specName: hit.key]) : null
}

// Fallback when the information model is unavailable: model suffix + reported fields
private Map guessSpec(String model, List fields) {
	def mFan   = (model =~ /-(?:FDR|FAR|UAR|FD\d?R)\d*L(D?)(\d)$/)
	def mLight = (model =~ /-L(\d)$/)
	if (mFan.find()) {
		def lights = []
		def cls = mFan.group(2) as Integer
		if (fields.contains("H0B") || cls >= 1)
			lights << [id: "LIGHT", power: "H0B", level: (fields.contains("H0C") || cls >= 2) ? "H0C" : null,
					   cct: (fields.contains("H04") || cls >= 3) ? "H04" : null, cctMin: 2700, cctMax: 5000]
		if (fields.contains("H09"))
			lights << [id: "LIGHT2", power: "H09", level: fields.contains("H0A") ? "H0A" : null,
					   cct: fields.contains("H04") ? "H04" : null, cctMin: 2700, cctMax: 5000]
		return [fan: true, lights: lights]
	}
	if (mLight.find()) {
		def cls = mLight.group(1) as Integer
		return [fan: false, lights: [[id: "LIGHT", power: "H00", level: (cls >= 2 || fields.contains("H02")) ? "H02" : null]]]
	}
	if (fields.contains("H00") && fields.contains("H02")) {
		def lights = fields.contains("H0B") ? [[id: "LIGHT", power: "H0B", level: fields.contains("H0C") ? "H0C" : null, cct: fields.contains("H04") ? "H04" : null, cctMin: 2700, cctMax: 5000]] : []
		return [fan: true, lights: lights]
	}
	return null
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
	def ids = (state.deviceNames ?: [:]).keySet() as Set
	getChildDevices().each { def d = it.getDataValue("fansyncId"); if (d) ids << d }
	ids.each { pollDevice(it) }
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
