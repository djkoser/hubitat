/**
 * Safety Monitor — Hubitat App
 *
 * Replaces HSM with three always-running parts:
 *
 * Intrusion monitoring
 *   - Arms when every selected lock has reported locked for safetyArmDelayMinutes.
 *   - Disarms when any lock reports unlocked (doors can't open without unlocking).
 *   - While armed, any door opening alerts: text + announcement + camera spotlights + sirens.
 *   - While armed, a camera detection (person / vehicle by default) is texted, at most once
 *     per camera per safetyCameraCooldownMinutes.
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
 *   - A monitored device is texted once when it goes offline (and again when it recovers):
 *     its driver's healthStatus if it reports one, otherwise safetySensorStaleHours of silence.
 *
 * Timings are this app's own settings. It publishes intrusionMonitoringEnabled and
 * safetyStatus to hub variables of those names, if they exist, for dashboards and other apps.
 * It also creates and updates a view-only "Safety Status" device (driver safety-status.groovy)
 * that shows intrusion Armed / Disarmed / Alert, the most serious active hazard and the number
 * of devices offline, for Google Home and dashboards. It has no commands, so it can't disarm.
 *
 * Cameras are the devices from Hubitat's built-in Reolink Integration, which receives the NVR's
 * AI detections over a live local connection (person / vehicle / pet / package / motion
 * attributes) and provides setSpotlight / setSiren.
 */

definition(
	name: "Safety Monitor",
	namespace: "djkoser",
	author: "David Koser",
	description: "Lock-armed intrusion monitoring, smoke/CO/leak alerts with valve shutoff, and device health",
	category: "Safety & Security",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/safety-monitor.groovy",
	singleThreaded: true   // simultaneous door/sensor events update shared alert state
)

preferences { page(name: "mainPage") }

// Settings: name: [type, default, description]
@groovy.transform.Field static final Map PARAMS = [
	safetyArmDelayMinutes:  ["Number", 2,       "Minutes all locks must stay locked before arming"],
	safetyArmReminderTime:  ["String", "22:00", "Time (HH:mm, 24-hour) to announce if not armed; \"off\" disables"],
	safetySensorStaleHours: ["Number", 26,      "Hours of silence before a device is reported offline"],
	ttsStopDelay:           ["Number", 10,      "Seconds before speakers are stopped after an announcement"],
	safetyRepeatSeconds:    ["Number", 30,      "Seconds between repeated alert announcements; 0 = announce once"],
	safetyRepeatCount:      ["Number", 10,      "Times an active alert is repeated; 0 = until cancelled or cleared"],
	safetyCameraCooldownMinutes: ["Number", 2,  "Minimum minutes between detection texts from the same camera"],
]
// Reolink Integration detection attributes that can trigger a text while armed
@groovy.transform.Field static final List DETECTIONS = ["person", "vehicle", "pet", "package", "motion"]
// Settings from the retired NVR email-alert path, removed on update
@groovy.transform.Field static final List RETIRED_SETTINGS = ["nvrIpAddress", "reolinkUsername", "reolinkPassword",
                                                               "cameraChannels", "cameraChannelVars"]
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
			input "cameras", "capability.motionSensor", title: "Reolink cameras", multiple: true, required: false
			input "alertDetections", "enum", title: "While armed, text when a camera detects (none chosen = person and vehicle)",
				  options: DETECTIONS, multiple: true, required: false
			input "camerasEnabled", "bool", title: "Turn on camera spotlights and sirens when a door opens while armed", defaultValue: true
			input "sirenSwitch", "capability.switch", title: "Sirens switch (on/off sounds or stops every camera siren)", required: false
		}
		if (app.id) {
			section("Status") {
				paragraph "<b>Intrusion:</b> ${state.armed ? 'ARMED' : 'disarmed'}${state.alertActive ? ' — <span style=\"color:#c00\">ALERT ACTIVE</span>' : ''}<br>" +
						  "<b>Unlocked:</b> ${unlockedNames() ?: 'none'}<br>" +
						  "<b>Open doors:</b> ${openDoorNames() ?: 'none'}<br>" +
						  "<b>Repeating alerts:</b> ${(state.activeAlerts ?: [:]).values().join(' ') ?: 'none'}<br>" +
						  "<b>Unhealthy devices:</b> ${(state.unhealthy ?: [:]).values().join(', ') ?: 'none'}<br>" +
						  "<b>Camera texts while armed:</b> ${cameras ? (detectionTypes().join(', ') ?: 'none') : 'no cameras selected'}"
			}
		}
		section("Settings") {
			PARAMS.each { n, d -> input n, d[0] == "String" ? "text" : "number", title: d[2], defaultValue: d[1], required: false }
		}
		section("Logging") {
			input "logEnable", "bool", title: "Debug logging (auto-off after 30 min)", defaultValue: false
		}
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() { initialize() }
def updated() {
	unsubscribe(); unschedule()
	// Clean-up from the retired NVR email-alert path
	["reolinkAuthFailed", "reolinkToken", "reolinkTokenExpires", "hubVarsMigrated"].each { state.remove(it) }
	RETIRED_SETTINGS.each { if (settings.containsKey(it)) app.removeSetting(it) }
	initialize()
}
def uninstalled() { removeAllInUseGlobalVar() }

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	removeAllInUseGlobalVar()
	addInUseGlobalVar(OUTPUTS)
	state.unhealthy = state.unhealthy ?: [:]

	subscribe(locks, "lock", "lockHandler")
	subscribe(doors, "contact.open", "doorOpenHandler")
	if (cancelSwitch) subscribe(cancelSwitch, "switch.on", "cancelHandler")
	if (sirenSwitch) subscribe(sirenSwitch, "switch", "sirenSwitchHandler")
	if (cameras) detectionTypes().each { subscribe(cameras, it, "cameraDetectionHandler") }
	if (smokeDetectors) subscribe(smokeDetectors, "smoke", "smokeHandler")
	if (coDetectors) subscribe(coDetectors, "carbonMonoxide", "coHandler")
	if (leakSensors) subscribe(leakSensors, "water", "leakHandler")
	allMonitored().each { subscribe(it, "healthStatus", "healthEventHandler") }

	runEvery1Hour("healthCheck")
	scheduleReminder()

	ensureStatusDevice()

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

private void scheduleReminder() {
	unschedule("armReminder")
	def t = paramString("safetyArmReminderTime")
	if (!t || t.equalsIgnoreCase("off")) return
	def hm = parseHhMm(t)
	if (!hm) { log.warn "Safety Monitor: reminder time '${t}' is not a valid 24-hour HH:mm (or \"off\")"; return }
	schedule("0 ${hm[1]} ${hm[0]} ? * *", "armReminder")
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
	publishStatus()
}

private void clearAlert(String key) {
	def active = state.activeAlerts ?: [:]
	if (active.remove(key) != null) state.activeAlerts = active
	if (!active) unschedule("repeatAlerts")
	publishStatus()
}

private void clearAllAlerts() {
	state.activeAlerts = [:]
	unschedule("repeatAlerts")
	publishStatus()
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
		// Trust the driver's own health check when it has one: event-only devices (e.g. a smoke
		// alarm relay) can be silent for months and still be fine. Otherwise use silence.
		def health = dev.currentValue("healthStatus")
		def offline = health == "offline"
		def last = dev.getLastActivity()
		def stale = health == null && last != null && (now() - last.time) > staleMs
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

// While armed, text camera detections (live from the Reolink Integration), at most once per
// camera per cooldown so one person walking past doesn't produce a burst of texts.
def cameraDetectionHandler(evt) {
	if (!state.armed || evt.value != "active") return
	def key = evt.deviceId.toString()
	def last = (state.lastCameraAlert ?: [:])[key]
	def cooldownMs = (param("safetyCameraCooldownMinutes") as BigDecimal) * 60000
	if (last != null && now() - (last as Long) < cooldownMs) {
		if (logEnable) log.debug "Safety Monitor: ${evt.name} at ${evt.displayName} within cooldown; not texted"
		return
	}
	state.lastCameraAlert = (state.lastCameraAlert ?: [:]) + [(key): now()]
	def msg = "${evt.name.capitalize()} detected at ${evt.displayName} while armed (${nowText()})."
	log.info "Safety Monitor: ${msg}"
	notify(msg)
}

private List detectionTypes() {
	def v = settings.alertDetections
	if (v == null) return ["person", "vehicle"]
	return (v instanceof Collection ? v : [v]).collect { it.toString() }.findAll { DETECTIONS.contains(it) }
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
	updateStatusDevice(s, bad)
}

// ── Status device (view only, for Google Home and dashboards) ───────────────

private String statusDni() { "safety-status-${app.id}" }

private void ensureStatusDevice() {
	if (getChildDevice(statusDni())) return
	try {
		addChildDevice("djkoser", "Safety Status", statusDni(), [label: "Safety Status", isComponent: false])
		log.info "Safety Monitor: created the Safety Status device"
	} catch (e) {
		log.warn "Safety Monitor: couldn't create the Safety Status device (is the Safety Status driver installed?): ${e.message}"
	}
}

// Most serious first; an intrusion alert is shown by the intrusion attribute instead
@groovy.transform.Field static final Map HAZARDS = ["co": "Carbon monoxide", "smoke": "Smoke", "leak": "Water leak"]

private String hazardText() {
	def kinds = (state.activeAlerts ?: [:]).keySet().collect { it.toString().tokenize(":")[0] }
	return HAZARDS.find { k, v -> kinds.contains(k) }?.value ?: "None"
}

private void updateStatusDevice(String summary, int offline) {
	def dev = getChildDevice(statusDni())
	if (!dev) return
	def set = { String name, String value, String text = null ->
		if (dev.currentValue(name)?.toString() != value) dev.sendEvent(name: name, value: value, descriptionText: text)
	}
	def intrusion = state.alertActive ? "Alert" : (state.armed ? "Armed" : "Disarmed")
	set("intrusion", intrusion, "Intrusion monitoring is ${intrusion.toLowerCase()}")
	set("hazard", hazardText())
	set("devicesOffline", Math.min(offline, 50).toString())
	set("safetyStatus", summary)
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private boolean anyUnlocked() { locks.any { it.currentValue("lock") != "locked" } }
private String unlockedNames() { locks.findAll { it.currentValue("lock") != "locked" }*.displayName.join(", ") }
private String openDoorNames() { doors.findAll { it.currentValue("contact") == "open" }*.displayName.join(", ") }
private int armDelaySeconds() { Math.max(((param("safetyArmDelayMinutes") as BigDecimal) * 60) as Integer, 1) }
// "HH:mm" (24-hour) -> [hour, minute], or null if malformed/out of range
private List parseHhMm(String t) {
	def m = (t =~ /^(\d{1,2}):(\d{2})$/)
	if (!m.matches()) return null
	int h = m[0][1] as Integer, mi = m[0][2] as Integer
	return (h <= 23 && mi <= 59) ? [h, mi] : null
}
private String nowText() { new Date().format("MMM d h:mm a", location.timeZone) }

private param(String name) {
	def d = PARAMS[name]
	def v = settings[name]
	if (v == null || v.toString() == "") return d[1] instanceof String ? d[1] : new BigDecimal(d[1].toString())
	if (d[0] == "String") return v.toString().trim()
	try { return new BigDecimal(v.toString()) } catch (e) { return new BigDecimal(d[1].toString()) }
}

// Text setting as entered; unset = default, explicitly blank = "" (e.g. reminder off)
private String paramString(String name) {
	def v = settings[name]
	return v == null ? PARAMS[name][1] : v.toString().trim()
}
