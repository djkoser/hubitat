/**
 * Safety Monitor — Hubitat App
 *
 * Replaces HSM with three always-running parts:
 *
 * Intrusion monitoring
 *   - Arms when every selected lock has reported locked for safetyArmDelayMinutes.
 *   - Disarms when any lock reports unlocked (doors can't open without unlocking).
 *   - While armed, any door opening alerts: text + announcement + camera spotlights + sirens.
 *   - Camera email alerts are enabled while armed and disabled while disarmed.
 *   - The "cancel" switch (or disarming) stops spotlights and sirens.
 *   - An optional sirens switch (e.g. exposed to Google) sounds/stops every camera siren.
 *   - At safetyArmReminderTime, if the house isn't armed, announces what is unlocked/open.
 *
 * Environmental monitoring (always on)
 *   - Smoke, carbon monoxide and water leaks: text + announcement.
 *   - Water leak closes the main shutoff valve when one is configured.
 *   - Clearing events are texted too.
 *
 * Active alerts (intrusion, smoke, CO, leak) are re-announced every safetyRepeatSeconds,
 * safetyRepeatCount times (0 = until cleared). The cancel switch acknowledges all of them.
 *
 * Device health
 *   - Any monitored device that reports offline, or has been silent for
 *     safetySensorStaleHours, is texted once (and again when it recovers).
 *
 * Tunables are hub variables (Settings > Hub Variables); a missing one falls back to its
 * default and is flagged on the app page.
 *
 * Spotlights and sirens use the camera devices from Hubitat's built-in Reolink Integration
 * (setSpotlight / setSiren). Camera email alerts are not exposed by that integration, so they
 * are set directly on the NVR over HTTPS with a token login, using hub variables
 * nvrIpAddress / reolinkUsername / reolinkPassword and the per-camera channel variables.
 */

import groovy.json.JsonOutput

definition(
	name: "Safety Monitor",
	namespace: "djkoser",
	author: "David Koser",
	description: "Lock-armed intrusion monitoring, smoke/CO/leak alerts with valve shutoff, and device health",
	category: "Safety & Security",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/safety-monitor.groovy"
)

preferences { page(name: "mainPage") }

// name: [type, default, description]
@groovy.transform.Field static final Map PARAMS = [
	safetyArmDelayMinutes:  ["Number", 2,       "Minutes all locks must stay locked before arming"],
	safetyArmReminderTime:  ["String", "22:00", "Time (HH:mm) to announce if not armed; blank = off"],
	safetySensorStaleHours: ["Number", 26,      "Hours of silence before a device is reported offline"],
	ttsStopDelay:           ["Number", 10,      "Seconds before speakers are stopped after an announcement"],
	safetyRepeatSeconds:    ["Number", 30,      "Seconds between repeated alert announcements; 0 = announce once"],
	safetyRepeatCount:      ["Number", 10,      "Times an active alert is repeated; 0 = until cancelled or cleared"],
]
@groovy.transform.Field static final List OUTPUTS = ["intrusionMonitoringEnabled", "safetyStatus"]

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Intrusion") {
			input "locks", "capability.lock", title: "Locks (all locked = arm; any unlocked = disarm)", multiple: true, required: true
			input "doors", "capability.contactSensor", title: "Door sensors (alert when opened while armed)", multiple: true, required: true
			input "cancelSwitch", "capability.switch", title: "Cancel alerts switch", required: false
		}
		section("Environmental") {
			input "smokeDetectors", "capability.smokeDetector", title: "Smoke detectors", multiple: true, required: false
			input "coDetectors", "capability.carbonMonoxideDetector", title: "Carbon monoxide detectors", multiple: true, required: false
			input "leakSensors", "capability.waterSensor", title: "Leak sensors", multiple: true, required: false
			input "waterValve", "capability.valve", title: "Main water shutoff valve (closed on any leak)", required: false
		}
		section("Notifications") {
			input "notifiers", "capability.notification", title: "Text these", multiple: true, required: true
			input "speakers", "capability.speechSynthesis", title: "Announce on these", multiple: true, required: false
		}
		section("Cameras (Reolink)") {
			input "camerasEnabled", "bool", title: "Use cameras (spotlights, sirens, email alerts)", defaultValue: true
			input "cameras", "capability.motionSensor", title: "Reolink cameras (spotlight + siren when a door opens while armed)", multiple: true, required: false
			input "sirenSwitch", "capability.switch", title: "Sirens switch (on/off sounds or stops every camera siren)", required: false
			input "cameraChannelVars", "text", title: "Hub variables holding camera channel numbers (comma-separated)",
				  defaultValue: "drivewayCameraChannel,southCameraChannel,southeastCameraChannel,northeastCameraChannel,northwestCameraChannel"
		}
		if (app.id) {
			section("Status") {
				paragraph "<b>Intrusion:</b> ${state.armed ? 'ARMED' : 'disarmed'}${state.alertActive ? ' — <span style=\"color:#c00\">ALERT ACTIVE</span>' : ''}<br>" +
						  "<b>Unlocked:</b> ${unlockedNames() ?: 'none'}<br>" +
						  "<b>Open doors:</b> ${openDoorNames() ?: 'none'}<br>" +
						  "<b>Repeating alerts:</b> ${(state.activeAlerts ?: [:]).values().join(' ') ?: 'none'}<br>" +
						  "<b>Unhealthy devices:</b> ${(state.unhealthy ?: [:]).values().join(', ') ?: 'none'}"
			}
		}
		section("Settings (hub variables)") {
			def rows = PARAMS.collect { n, d ->
				def gv = getGlobalVar(n)
				"<tr><td>${n}</td><td>${gv != null ? gv.value : "<span style='color:#c00'>missing — using ${d[1]}</span>"}</td><td>${d[2]}</td></tr>"
			}.join("")
			paragraph "<table style='font-size:12px'><tr><th align=left>Variable</th><th align=left>Value</th><th align=left>Meaning</th></tr>${rows}</table>"
		}
		section("Logging") {
			input "logEnable", "bool", title: "Debug logging (auto-off after 30 min)", defaultValue: false
		}
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); state.remove("reolinkAuthFailed"); state.remove("reolinkToken"); initialize() }
def uninstalled() { removeAllInUseGlobalVar() }

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	removeAllInUseGlobalVar()
	def cams = channelVarNames()
	addInUseGlobalVar((PARAMS.keySet() as List) + OUTPUTS + cams + ["nvrIpAddress", "reolinkUsername", "reolinkPassword"])
	state.unhealthy = state.unhealthy ?: [:]

	subscribe(locks, "lock", "lockHandler")
	subscribe(doors, "contact.open", "doorOpenHandler")
	if (cancelSwitch) subscribe(cancelSwitch, "switch.on", "cancelHandler")
	if (sirenSwitch) subscribe(sirenSwitch, "switch", "sirenSwitchHandler")
	if (smokeDetectors) subscribe(smokeDetectors, "smoke", "smokeHandler")
	if (coDetectors) subscribe(coDetectors, "carbonMonoxide", "coHandler")
	if (leakSensors) subscribe(leakSensors, "water", "leakHandler")
	allMonitored().each { subscribe(it, "healthStatus", "healthEventHandler") }
	subscribe(location, "variable:safetyArmReminderTime", "reminderTimeChanged")

	runEvery1Hour("healthCheck")
	scheduleReminder()

	// Sync arming with the locks' current state without announcing a change.
	state.armed = state.armed ?: false
	if (anyUnlocked()) setArmed(false, null, true)
	else if (!state.armed) runIn(armDelaySeconds(), "armIfAllLocked")
	publishStatus()
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ── Intrusion ────────────────────────────────────────────────────────────────

def lockHandler(evt) {
	if (evt.value == "locked") {
		if (!state.armed && !anyUnlocked()) runIn(armDelaySeconds(), "armIfAllLocked", [overwrite: true])
	} else {
		unschedule("armIfAllLocked")
		if (state.armed) setArmed(false, evt.displayName, false)
	}
}

def armIfAllLocked() {
	if (!state.armed && !anyUnlocked()) setArmed(true, null, false)
}

private void setArmed(boolean armed, String byDevice, boolean quiet) {
	def changed = (state.armed ?: false) != armed
	state.armed = armed
	if (getGlobalVar("intrusionMonitoringEnabled") != null) setGlobalVar("intrusionMonitoringEnabled", armed)
	cameraEmailAlerts(armed)
	if (!armed) stopAlert()
	if (changed && !quiet) {
		def msg = armed ? "Intrusion monitoring has been armed." :
				  "Intrusion monitoring has been disarmed${byDevice ? " by ${byDevice}" : ''}."
		log.info msg
		speak(msg)
	}
	publishStatus()
}

def doorOpenHandler(evt) {
	if (!state.armed) return
	def msg = "ALERT: ${evt.displayName} opened while intrusion monitoring was armed (${nowText()})."
	log.warn msg
	state.alertActive = true
	notify(msg)
	startAlert("intrusion", "Alert. ${evt.displayName} opened while armed.")
	cameraSpotlights(true)
	cameraSirens(true)
	publishStatus()
}

def cancelHandler(evt) {
	log.info "Active alerts cancelled by ${evt.displayName}"
	stopAlert()
	clearAllAlerts()   // acknowledges environmental alerts too: their announcements stop repeating
}

def sirenSwitchHandler(evt) {
	log.info "Sirens turned ${evt.value} by ${evt.displayName}"
	cameraSirens(evt.value == "on")
}

private void stopAlert() {
	if (state.alertActive) {
		cameraSpotlights(false)
		cameraSirens(false)
	}
	if (sirenSwitch?.currentValue("switch") == "on") sirenSwitch.off()
	state.alertActive = false
	clearAlert("intrusion")
	publishStatus()
}

// Nightly check: say what's keeping the house from arming.
def armReminder() {
	if (state.armed) return
	def parts = []
	def u = unlockedNames(); if (u) parts << "unlocked: ${u}"
	def o = openDoorNames(); if (o) parts << "open: ${o}"
	if (parts) speak("Intrusion monitoring is not armed. ${parts.join('. ')}.")
}

def reminderTimeChanged(evt) { scheduleReminder() }

private void scheduleReminder() {
	unschedule("armReminder")
	def t = paramString("safetyArmReminderTime")
	if (!t) return
	def m = (t =~ /^(\d{1,2}):(\d{2})$/)
	if (!m.matches()) { log.warn "safetyArmReminderTime '${t}' is not HH:mm"; return }
	schedule("0 ${m[0][2] as Integer} ${m[0][1] as Integer} ? * *", "armReminder")
}

// ── Environmental ────────────────────────────────────────────────────────────

def smokeHandler(evt) {
	def key = "smoke:${evt.deviceId}"
	if (evt.value == "detected") alarm(key, "Smoke detected by ${evt.displayName}")
	else if (evt.value == "clear") { clearAlert(key); notify("Smoke cleared at ${evt.displayName} (${nowText()}).") }
}

def coHandler(evt) {
	def key = "co:${evt.deviceId}"
	if (evt.value == "detected") alarm(key, "Carbon monoxide detected by ${evt.displayName}. Get everyone outside")
	else if (evt.value == "clear") { clearAlert(key); notify("Carbon monoxide cleared at ${evt.displayName} (${nowText()}).") }
}

def leakHandler(evt) {
	if (evt.value == "wet") {
		def valveText = ""
		if (waterValve) {
			waterValve.close()
			valveText = " The main water valve is being closed."
		}
		alarm("leak:${evt.deviceId}", "Water leak detected by ${evt.displayName}.${valveText}")
	} else if (evt.value == "dry") {
		clearAlert("leak:${evt.deviceId}")
		notify("${evt.displayName} is dry again (${nowText()}).${waterValve ? " The main water valve stays closed until you reopen it." : ''}")
	}
}

private void alarm(String key, String text) {
	def t = text.endsWith(".") ? text : text + "."
	log.warn t
	notify("${t} (${nowText()})")
	startAlert(key, t)
}

// ── Repeating announcements (like HSM) ───────────────────────────────────────
// Each active alert is announced now and re-announced every safetyRepeatSeconds,
// safetyRepeatCount times (0 = until it is cleared, cancelled or the house disarms).

private void startAlert(String key, String text) {
	def active = state.activeAlerts ?: [:]
	active[key] = text
	state.activeAlerts = active
	state.repeatsLeft = (param("safetyRepeatCount") as BigDecimal) as Integer
	speak(text)
	scheduleRepeat()
}

private void clearAlert(String key) {
	def active = state.activeAlerts ?: [:]
	if (active.remove(key) != null) state.activeAlerts = active
	if (!active) unschedule("repeatAlerts")
}

private void clearAllAlerts() {
	state.activeAlerts = [:]
	unschedule("repeatAlerts")
}

private void scheduleRepeat() {
	def secs = (param("safetyRepeatSeconds") as BigDecimal) as Integer
	if (secs > 0) runIn(Math.max(secs, 10), "repeatAlerts", [overwrite: true])
}

def repeatAlerts() {
	def active = state.activeAlerts ?: [:]
	if (!active) return
	def total = (param("safetyRepeatCount") as BigDecimal) as Integer
	def left = (state.repeatsLeft ?: 0) as Integer
	if (total > 0 && left <= 0) return
	speak(active.values().join(" "))
	if (total > 0) state.repeatsLeft = left - 1
	if (total == 0 || left - 1 > 0) scheduleRepeat()
}

// ── Device health ────────────────────────────────────────────────────────────

def healthEventHandler(evt) { healthCheck() }

def healthCheck() {
	def staleMs = (param("safetySensorStaleHours") as BigDecimal) * 3600000
	def unhealthy = state.unhealthy ?: [:]
	allMonitored().each { dev ->
		def key = dev.id.toString()
		def offline = dev.currentValue("healthStatus") == "offline"
		def last = dev.getLastActivity()
		def stale = last != null && (now() - last.time) > staleMs
		if (offline || stale) {
			if (!unhealthy.containsKey(key)) {
				unhealthy[key] = dev.displayName
				notify("${dev.displayName} appears offline (${offline ? 'reported offline' : "no activity since ${last.format('MMM d h:mm a', location.timeZone)}"}). Safety monitoring for it may not work.")
			}
		} else if (unhealthy.containsKey(key)) {
			unhealthy.remove(key)
			notify("${dev.displayName} is back online.")
		}
	}
	state.unhealthy = unhealthy
	publishStatus()
}

private List allMonitored() {
	def all = []
	[locks, doors, smokeDetectors, coDetectors, leakSensors].each { if (it) all.addAll(it) }
	if (waterValve) all << waterValve
	return all.unique { it.id }
}

// ── Cameras ──────────────────────────────────────────────────────────────────

private void cameraSpotlights(boolean on) {
	if (camerasEnabled == false) return
	cameras?.each { if (it.hasCommand("setSpotlight")) it.setSpotlight(on ? "on" : "off") }
}

private void cameraSirens(boolean on) {
	if (camerasEnabled == false) return
	cameras?.each { if (it.hasCommand("setSiren")) it.setSiren(on ? "on" : "off") }
}

// Email alert schedule (AI people/vehicle on while armed). Not in the Reolink Integration,
// so it goes straight to the NVR: HTTPS, token login, all channels in one batch request.
private void cameraEmailAlerts(boolean on) {
	def table = on ? "1" * 168 : "0" * 168
	def off = "0" * 168
	reolink(channels().collect { ch -> [cmd: "SetEmailV20", param: [Email: [schedule: [channel: ch, table: [
		AI_PEOPLE: table, AI_VEHICLE: table, AI_DOG_CAT: off, MD: off]]]]] })
}

private List channels() {
	if (camerasEnabled == false) return []
	channelVarNames().collect { n ->
		def gv = getGlobalVar(n)
		if (gv?.value != null && gv.value.toString().isInteger()) return gv.value.toString() as Integer
		log.warn "Camera channel variable ${n} is missing or not a number"
		return null
	}.findAll { it != null }
}

private List channelVarNames() {
	(cameraChannelVars ?: "").split(",").collect { it.trim() }.findAll { it }
}

// The NVR locks the account after repeated failed logins, so after one failed login
// NVR calls stop until the app is saved again.
private void reolink(List commands) {
	if (!commands) return
	if (state.reolinkAuthFailed) { log.warn "NVR calls paused after a failed login; fix the credentials and click Done in the app"; return }
	if (!getGlobalVar("nvrIpAddress")?.value) { log.warn "Hub variable nvrIpAddress is not set"; return }
	def body = JsonOutput.toJson(commands)
	if (state.reolinkToken && now() < ((state.reolinkTokenExpires ?: 0) as Long)) sendNvr(body, false)
	else nvrLogin(body)
}

private void nvrLogin(String pendingBody, boolean retried = false) {
	def user = getGlobalVar("reolinkUsername")?.value
	def pass = getGlobalVar("reolinkPassword")?.value
	if (!user || pass == null) { log.warn "Hub variables reolinkUsername / reolinkPassword are not set"; return }
	asynchttpPost("nvrLoginResponse", [uri: "https://${getGlobalVar('nvrIpAddress').value}/cgi-bin/api.cgi?cmd=Login",
		ignoreSSLIssues: true, requestContentType: "application/json", contentType: "application/json", timeout: 15,
		body: JsonOutput.toJson([[cmd: "Login", param: [User: [Version: "0", userName: user, password: pass]]]])],
		[body: pendingBody, retried: retried])
}

def nvrLoginResponse(resp, data) {
	if (resp.hasError() || resp.status != 200) { log.warn "NVR login request failed (${resp.status})"; return }
	def r
	try { r = resp.json; r = (r instanceof List) ? r[0] : r } catch (e) { return }
	def token = r?.value?.Token
	if (r?.code != 0 || !token?.name) {
		state.reolinkAuthFailed = true
		log.error "NVR login failed (${r?.error?.detail ?: r?.code}); NVR calls paused until the app is saved again"
		return
	}
	state.reolinkToken = token.name
	state.reolinkTokenExpires = now() + (((token.leaseTime ?: 3600) as Long) - 60) * 1000
	sendNvr(data.body, data.retried ?: false)
}

private void sendNvr(String body, boolean retried) {
	asynchttpPost("nvrResponse", [uri: "https://${getGlobalVar('nvrIpAddress').value}/cgi-bin/api.cgi?token=${state.reolinkToken}",
		ignoreSSLIssues: true, requestContentType: "application/json", contentType: "application/json", timeout: 15,
		body: body], [body: body, retried: retried])
}

def nvrResponse(resp, data) {
	if (resp.hasError() || resp.status != 200) { log.warn "NVR request failed (${resp.status})"; return }
	def j
	try { j = resp.json } catch (e) { return }
	def results = (j instanceof List) ? j : [j]
	// -6 = token expired/invalid: log in again once
	if (!data.retried && results.any { it?.error?.rspCode == -6 }) {
		state.remove("reolinkToken")
		nvrLogin(data.body, true)
		return
	}
	def bad = results.findAll { it?.code != 0 }
	if (bad) log.warn "NVR: ${bad.size()} of ${results.size()} commands failed: ${bad[0]}"
	else if (logEnable) log.debug "NVR ok x${results.size()}"
}

// ── Output ───────────────────────────────────────────────────────────────────

private void notify(String msg) { notifiers*.deviceNotification(msg) }

private void speak(String msg) {
	if (!speakers) return
	speakers*.speak(msg)
	runIn(((param("ttsStopDelay") as BigDecimal) as Integer), "stopSpeakers", [overwrite: true])
}

def stopSpeakers() { speakers*.stop() }

private void publishStatus() {
	def s = state.alertActive ? "ALERT" : (state.armed ? "Armed" : "Disarmed")
	def bad = (state.unhealthy ?: [:]).size()
	if (bad) s += " (${bad} device${bad == 1 ? '' : 's'} offline)"
	if (getGlobalVar("safetyStatus") != null) setGlobalVar("safetyStatus", s)
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private boolean anyUnlocked() { locks.any { it.currentValue("lock") != "locked" } }
private String unlockedNames() { locks.findAll { it.currentValue("lock") != "locked" }*.displayName.join(", ") }
private String openDoorNames() { doors.findAll { it.currentValue("contact") == "open" }*.displayName.join(", ") }
private int armDelaySeconds() { Math.max(((param("safetyArmDelayMinutes") as BigDecimal) * 60) as Integer, 1) }
private String nowText() { new Date().format("MMM d h:mm a", location.timeZone) }

private param(String name) {
	def gv = getGlobalVar(name)
	def d = PARAMS[name]
	if (gv == null || gv.value == null || gv.value.toString() == "") return d[1] instanceof String ? d[1] : new BigDecimal(d[1].toString())
	if (d[0] == "String") return gv.value.toString().trim()
	try { return new BigDecimal(gv.value.toString()) } catch (e) { return new BigDecimal(d[1].toString()) }
}

private String paramString(String name) {
	def gv = getGlobalVar(name)
	if (gv == null) return PARAMS[name][1]
	return gv.value?.toString()?.trim() ?: ""
}
