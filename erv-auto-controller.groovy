/**
 * ERV Auto Controller — Hubitat App
 *
 * Drives an ERV and air handler from indoor/outdoor air-quality monitors
 * (AirGradient ONE + Open Air via the "AirGradient Local" driver) while the
 * "ERV Auto" mode switch is on. Any number of indoor and outdoor monitors:
 *   - indoor: the worst room decides (highest CO2, VOC, NOx and PM2.5 across monitors)
 *   - outdoor: conservative (highest reading across monitors)
 *   - an offline/stale monitor is left out; fail-safe applies only when every
 *     indoor or every outdoor monitor is offline/stale
 * Outputs one of three states:
 *   On          — ERV + air handler run (ventilate)
 *   Recirculate — ERV stopped, air handler runs (filter indoor air)
 *   Off         — both stopped
 *
 * The relays are wired normally closed: relay OFF = equipment runs, relay ON =
 * stopped. Whenever the ERV runs, the air handler runs too.
 *
 * Decision order (first match wins):
 *   1. All indoor or all outdoor monitors offline/stale -> fail-safe state (default On)
 *   2. Indoor CO2 >= critical (absolute)              -> On, whatever is outside
 *   3. Indoor CO2 too far above OUTDOOR CO2, or indoor VOC/NOx index high
 *                                                     -> On, or Recirculate if outdoor VOC/NOx is high
 *      (CO2 is relative: ventilation can only bring it down to the outdoor level. VOC/NOx are
 *      Sensirion indices, each relative to its own sensor's 24-h baseline, so an indoor and an
 *      outdoor index can't be compared; they keep their own thresholds.)
 *   4. Indoor PM2.5 high                              -> On if outdoor PM2.5 is fine and lower than
 *                                                        indoor by the margin, else Recirculate
 *                                                        (filtration can beat outdoor air for PM)
 *   5. Everything good                                -> idle state (default Off)
 * Each "high" flag has hysteresis: it trips at the threshold and clears only once the value
 * drops below threshold minus its buffer.
 *
 * Status device: a child "ERV Status" device (view only) shows On / Recirculate / Off from the
 * two stop relays at all times, in any mode, for dashboards and Google Home.
 *
 * Equipment protection (per piece of equipment, ERV and air handler): once started it runs at
 * least ervAutoMinRunMinutes, and once stopped it stays off at least ervAutoMinOffMinutes, so
 * it runs in long stretches instead of short-cycling. Exceptions: critical CO2 may start
 * equipment early, the fail-safe starts it immediately (it then runs continuously, so it adds
 * no cycling), and the ERV stops immediately when outdoor air turns bad (the air handler keeps
 * recirculating). Minimum run time still applies when the monitors come back.
 *
 * Thresholds and timings are this app's own settings (the Thresholds section of its page).
 * Its state and reason are published to the hub variables ervAutoState / ervAutoReason, if
 * they exist, for dashboards and other apps.
 */

definition(
	name: "ERV Auto Controller",
	namespace: "djkoser",
	author: "David Koser",
	description: "Ventilate, recirculate or idle an ERV + air handler from indoor/outdoor air quality",
	category: "Convenience",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/erv-auto-controller.groovy",
	singleThreaded: true   // several monitors report at once; evaluate one event at a time
)

preferences { page(name: "mainPage") }

// Settings: name: [type, default, description]
@groovy.transform.Field static final Map PARAMS = [
	ervAutoCo2AboveOutdoor:    ["Number", 600,  "Indoor CO2 this many ppm above outdoor triggers ventilation"],
	ervAutoCo2Buffer:          ["Number", 150,  "CO2 must fall this far below the threshold to clear"],
	ervAutoOutdoorCo2Default:  ["Number", 420,  "Outdoor CO2 (ppm) assumed when no outdoor monitor reports it"],
	ervAutoCo2Critical:        ["Number", 1500, "Indoor CO2 (ppm) that forces On regardless of outdoor air"],
	ervAutoVocHigh:            ["Number", 250,  "Indoor VOC index that triggers ventilation (100 = baseline)"],
	ervAutoVocBuffer:          ["Number", 50,   "VOC index hysteresis"],
	ervAutoNoxHigh:            ["Number", 50,   "Indoor NOx index that triggers ventilation (1 = baseline)"],
	ervAutoNoxBuffer:          ["Number", 15,   "NOx index hysteresis"],
	ervAutoPm25High:           ["Decimal", 15,  "Indoor PM2.5 (µg/m³) that triggers filtering/ventilation"],
	ervAutoPm25Buffer:         ["Decimal", 5,   "Indoor PM2.5 hysteresis"],
	ervAutoOutdoorPm25High:    ["Decimal", 35,  "Outdoor PM2.5 (µg/m³) considered bad"],
	ervAutoOutdoorPm25Buffer:  ["Decimal", 5,   "Outdoor PM2.5 hysteresis"],
	ervAutoPm25Margin:         ["Decimal", 5,   "Outdoor PM2.5 must be this much lower than indoor to ventilate for PM"],
	ervAutoOutdoorVocHigh:     ["Number", 250,  "Outdoor VOC index considered bad"],
	ervAutoOutdoorNoxHigh:     ["Number", 50,   "Outdoor NOx index considered bad"],
	ervAutoOutdoorGasBuffer:   ["Number", 25,   "Outdoor VOC/NOx hysteresis"],
	ervAutoMinRunMinutes:      ["Number", 30,   "Once started, the ERV / air handler runs at least this many minutes"],
	ervAutoMinOffMinutes:      ["Number", 30,   "Once stopped, the ERV / air handler stays off at least this many minutes"],
	ervAutoStaleMinutes:       ["Number", 15,   "Minutes without a sensor update before fail-safe"],
	ervAutoIdleState:          ["String", "Off", "State when air is good: Off, Recirculate or On"],
	ervAutoFailSafeState:      ["String", "On",  "State when a sensor is offline: On, Recirculate or Off"],
]
// Outputs published for other apps/dashboards (create them as String hub variables to use them)
@groovy.transform.Field static final List OUTPUTS = ["ervAutoState", "ervAutoReason"]
@groovy.transform.Field static final List STATES = ["On", "Recirculate", "Off"]
// Settings replaced Nov 2026 (absolute CO2 threshold, per-hour minimum, state dwell), removed on update
@groovy.transform.Field static final List RETIRED_SETTINGS = ["ervAutoCo2High", "ervAutoMinDwellMinutes", "ervAutoMinVentMinutesPerHour"]

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Devices") {
			input "autoSwitch", "capability.switch", title: "Auto mode switch (controller acts only while it is on)", required: true
			input "indoor", "capability.airQuality", title: "Indoor monitors (AirGradient ONE; worst room decides)", multiple: true, required: true
			input "outdoor", "capability.airQuality", title: "Outdoor monitors (AirGradient Open Air)", multiple: true, required: true
			input "ervStopRelay", "capability.switch", title: "ERV stop relay (on = ERV stopped)", required: true
			input "ahStopRelay", "capability.switch", title: "Air handler stop relay (on = air handler stopped)", required: true
		}
		if (app.id) {
			section("Status") {
				paragraph "<b>Auto:</b> ${autoSwitch?.currentValue('switch') == 'on' ? 'active' : 'inactive (another mode is selected)'}<br>" +
						  "<b>State:</b> ${state.current ?: '—'} &nbsp; <b>Reason:</b> ${state.reason ?: '—'}<br>" +
						  "<b>Since:</b> ${state.lastChange ? new Date(state.lastChange as Long).format('yyyy-MM-dd HH:mm', location.timeZone) : '—'}<br>" +
						  "<b>Monitors:</b> ${monitorStatus()}<br><br>" + readingsTable()
			}
		}
		section("Thresholds") {
			PARAMS.each { n, d ->
				if (d[0] == "String") input n, "enum", title: d[2], options: STATES, defaultValue: d[1], required: false
				else input n, d[0] == "Decimal" ? "decimal" : "number", title: d[2], defaultValue: d[1], required: false
			}
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
	RETIRED_SETTINGS.each { if (settings.containsKey(it)) app.removeSetting(it) }
	["ventMs", "ventHour", "ventTick", "hubVarsMigrated"].each { state.remove(it) }
	initialize()
}
def uninstalled() { removeAllInUseGlobalVar() }


def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	state.flags = state.flags ?: [:]
	removeAllInUseGlobalVar()
	addInUseGlobalVar(OUTPUTS)
	subscribe(autoSwitch, "switch", "autoSwitchHandler")
	["pm25", "carbonDioxide", "vocIndex", "noxIndex", "healthStatus", "lastUpdate"].each {
		subscribe(indoorDevs(), it, "sensorHandler")
		subscribe(outdoorDevs(), it, "sensorHandler")
	}
	runEvery1Minute("evaluate")
	ensureStatusDevice()
	subscribe(ervStopRelay, "switch", "relayHandler")
	subscribe(ahStopRelay, "switch", "relayHandler")
	updateStatusDevice()
	evaluate()
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ── Handlers ─────────────────────────────────────────────────────────────────

def autoSwitchHandler(evt) {
	if (evt.value == "on") {
		state.remove("equip")   // entering Auto: equipment timing starts fresh
		evaluate()
	}
	updateStatusDevice()
}

def sensorHandler(evt) {
	if (evt.name == "lastUpdate" || evt.name == "healthStatus") {
		state.seen = (state.seen ?: [:]) + [(evt.deviceId.toString()): now()]
	}
	evaluate()
}

// ── Decision ─────────────────────────────────────────────────────────────────

def evaluate() {
	if (autoSwitch?.currentValue("switch") != "on") return

	def p = PARAMS.collectEntries { n, d -> [(n): param(n)] }
	def f = state.flags ?: [:]

	def staleMs = (p.ervAutoStaleMinutes as BigDecimal) * 60000
	def inLive  = indoorDevs().findAll { !isStale(it, staleMs) }
	def outLive = outdoorDevs().findAll { !isStale(it, staleMs) }
	def stale = (indoorDevs() + outdoorDevs()).findAll { isStale(it, staleMs) }

	// Worst reading per pollutant across live monitors, with the monitor it came from
	def inCo2M = worst(inLive, "carbonDioxide"), inVocM = worst(inLive, "vocIndex")
	def inNoxM = worst(inLive, "noxIndex"),      inPmM  = worst(inLive, "pm25")
	def outPmM = worst(outLive, "pm25"), outVocM = worst(outLive, "vocIndex"), outNoxM = worst(outLive, "noxIndex")
	def inCo2 = inCo2M?.v, inVoc = inVocM?.v, inNox = inNoxM?.v, inPm = inPmM?.v
	def outPm = outPmM?.v, outVoc = outVocM?.v, outNox = outNoxM?.v
	def outCo2 = outdoorCo2(outLive, p.ervAutoOutdoorCo2Default)
	def co2Excess = inCo2 != null ? inCo2 - outCo2 : null
	boolean multiIn = indoorDevs().size() > 1
	def where = { m -> (multiIn && m) ? " (${m.name})" : "" }

	f.inCo2Bad  = hyst(f.inCo2Bad,  co2Excess, p.ervAutoCo2AboveOutdoor, p.ervAutoCo2Buffer)
	f.inVocBad  = hyst(f.inVocBad,  inVoc, p.ervAutoVocHigh,         p.ervAutoVocBuffer)
	f.inNoxBad  = hyst(f.inNoxBad,  inNox, p.ervAutoNoxHigh,         p.ervAutoNoxBuffer)
	f.inPmBad   = hyst(f.inPmBad,   inPm,  p.ervAutoPm25High,        p.ervAutoPm25Buffer)
	f.outPmBad  = hyst(f.outPmBad,  outPm, p.ervAutoOutdoorPm25High, p.ervAutoOutdoorPm25Buffer)
	f.outVocBad = hyst(f.outVocBad, outVoc, p.ervAutoOutdoorVocHigh, p.ervAutoOutdoorGasBuffer)
	f.outNoxBad = hyst(f.outNoxBad, outNox, p.ervAutoOutdoorNoxHigh, p.ervAutoOutdoorGasBuffer)
	state.flags = f

	// Each reading against its limits, for the reason text
	def co2Text = co2Excess == null ? "CO2 —" :
		vsLimit("CO2 over outdoor", co2Excess, p.ervAutoCo2AboveOutdoor, p.ervAutoCo2Buffer, f.inCo2Bad, "ppm") +
		" (${fmt(inCo2)} ppm${multiIn && inCo2M ? ' in ' + inCo2M.name : ''} vs ${fmt(outCo2)} ppm outdoors)"
	def inVocText  = vsLimit("VOC index", inVoc, p.ervAutoVocHigh, p.ervAutoVocBuffer, f.inVocBad) + (f.inVocBad ? where(inVocM) : "")
	def inNoxText  = vsLimit("NOx index", inNox, p.ervAutoNoxHigh, p.ervAutoNoxBuffer, f.inNoxBad) + (f.inNoxBad ? where(inNoxM) : "")
	def inPmText   = vsLimit("PM2.5", inPm, p.ervAutoPm25High, p.ervAutoPm25Buffer, f.inPmBad, "µg/m³") + (f.inPmBad ? where(inPmM) : "")
	def outVocText = vsLimit("VOC index", outVoc, p.ervAutoOutdoorVocHigh, p.ervAutoOutdoorGasBuffer, f.outVocBad)
	def outNoxText = vsLimit("NOx index", outNox, p.ervAutoOutdoorNoxHigh, p.ervAutoOutdoorGasBuffer, f.outNoxBad)
	def outPmText  = vsLimit("PM2.5", outPm, p.ervAutoOutdoorPm25High, p.ervAutoOutdoorPm25Buffer, f.outPmBad, "µg/m³")

	def target, reason, cause
	boolean urgent = false    // may start equipment during its minimum off time
	boolean protect = false   // may stop the ERV during its minimum run time (outdoor air bad)

	if (!inLive || !outLive) {
		target = stateParam(p.ervAutoFailSafeState, "On"); urgent = true
		reason = "${!inLive ? 'all indoor' : 'all outdoor'} monitors offline/stale (${stale*.displayName.join(', ')})"
		cause = "Monitors offline"
	} else if (inCo2 != null && inCo2 >= p.ervAutoCo2Critical) {
		target = "On"; urgent = true
		reason = "CO2 ${fmt(inCo2)} ppm${where(inCo2M)} ≥ critical ${fmt(p.ervAutoCo2Critical)} ppm"
		cause = "CO2 critical"
	} else if (f.inCo2Bad || f.inVocBad || f.inNoxBad) {
		def what = [f.inCo2Bad ? co2Text : null, f.inVocBad ? inVocText : null, f.inNoxBad ? inNoxText : null].findAll { it }.join(", ")
		if (f.outVocBad || f.outNoxBad) {
			target = "Recirculate"; protect = true
			reason = "indoor ${what}, but outdoor ${outVocText}, ${outNoxText}"
			cause = f.outVocBad && f.outNoxBad ? "Outdoor VOC and NOx high" : (f.outVocBad ? "Outdoor VOC high" : "Outdoor NOx high")
		} else {
			target = "On"
			reason = "indoor ${what}; outdoor ${outVocText}, ${outNoxText}"
			cause = f.inCo2Bad ? "Indoor CO2 high" : (f.inVocBad ? "Indoor VOC high" : "Indoor NOx high")
		}
	} else if (f.inPmBad) {
		def cleaner = inPm != null ? inPm - p.ervAutoPm25Margin : null
		if (!f.outPmBad && outPm != null && inPm != null && outPm <= cleaner) {
			target = "On"
			reason = "indoor ${inPmText}; outdoor PM2.5 ${fmt(outPm)} µg/m³ is cleaner (needs ≤ ${fmt(cleaner)} µg/m³)"
			cause = "Indoor PM2.5 high"
		} else {
			target = "Recirculate"; protect = f.outPmBad as boolean
			reason = "indoor ${inPmText}, but " + (f.outPmBad ? "outdoor ${outPmText}" :
			         "outdoor PM2.5 ${fmt(outPm)} µg/m³ isn't cleaner (needs ≤ ${fmt(cleaner)} µg/m³)") + " — filtering"
			cause = "Filtering particles"
		}
	} else {
		target = stateParam(p.ervAutoIdleState, "Off")
		reason = "air good: ${co2Text}, ${inVocText}, ${inNoxText}, ${inPmText}"
		cause = "Air good"
	}

	// Name any monitor that is being ignored, without triggering the fail-safe
	if (stale && inLive && outLive) reason += " (ignoring offline: ${stale*.displayName.join(', ')})"

	// Equipment protection: the state actually applied may lag the target
	def applied = applyCycleLimits(target, urgent, protect, p)
	if (applied.state != target) {
		reason = "${reason} — holding ${applied.state}: ${applied.why}"
		cause = STATES.indexOf(applied.state) < STATES.indexOf(target) ? "Minimum run" : "Minimum off"   // STATES runs On → Off
	}

	def current = state.current
	if (applied.state != current) {
		log.info "ERV Auto: ${current ?: '—'} → ${applied.state} (${reason})"
		state.current = applied.state
		state.lastChange = now()
		publish(applied.state, reason)
	} else if (reason != state.reason) {
		publish(applied.state, reason)
	}
	state.reason = reason
	state.cause = cause
	enforce(applied.state)
	updateStatusDevice()
}

// Minimum run / minimum off per piece of equipment. Returns the state to apply and, if it
// differs from the target, why. Equipment timing lives in state.equip: [erv|ah: [on, since]].
private Map applyCycleLimits(String target, boolean urgent, boolean protect, Map p) {
	long minRun = ((p.ervAutoMinRunMinutes as BigDecimal) * 60000) as long
	long minOff = ((p.ervAutoMinOffMinutes as BigDecimal) * 60000) as long
	def eq = state.equip ?: [:]
	// Unknown history (new install / entering Auto): take the relays as they are, unconstrained
	if (!eq.erv) eq.erv = [on: ervStopRelay.currentValue("switch") == "off", since: 0L]
	if (!eq.ah)  eq.ah  = [on: ahStopRelay.currentValue("switch") == "off", since: 0L]

	boolean wantErv = target == "On"
	boolean wantAh  = target in ["On", "Recirculate"]
	def why = []
	def decide = { String k, boolean want ->
		def e = eq[k]
		if ((e.on as boolean) == want) return want
		long age = now() - (e.since as Long)
		if (want && !urgent && age < minOff) { why << "${k == 'erv' ? 'ERV' : 'air handler'} off ${mins(age)} of ${mins(minOff)} min"; return false }
		if (!want && !(protect && k == "erv") && age < minRun) { why << "${k == 'erv' ? 'ERV' : 'air handler'} ran ${mins(age)} of ${mins(minRun)} min"; return true }
		return want
	}
	boolean erv = decide("erv", wantErv)
	boolean ah  = decide("ah", wantAh)
	if (erv && !ah) {   // the ERV never runs without the air handler
		if (decide("ah", true)) ah = true
		else erv = false   // the air handler is in its minimum off time; the ERV waits for it
	}
	[erv: erv, ah: ah].each { k, on ->
		if ((eq[k].on as boolean) != on) eq[k] = [on: on, since: now()]
	}
	state.equip = eq
	def applied = erv ? "On" : (ah ? "Recirculate" : "Off")
	return [state: applied, why: why.unique().join(", ") ?: "minimum run/off time"]
}

// "VOC index 202 ≥ 200" when high, "… 180 (high until ≤ 150)" while clearing, "… 120 < 200" when fine
private String vsLimit(String name, def value, def high, def buffer, def bad, String unit = "") {
	if (value == null) return "${name} —"
	def u = unit ? " ${unit}" : ""
	def v = "${name} ${fmt(value)}${u}"
	if (!bad) return "${v} < ${fmt(high)}${u}"
	return value >= high ? "${v} ≥ ${fmt(high)}${u}" : "${v} (high until ≤ ${fmt(high - buffer)}${u})"
}

private String fmt(def n) {
	if (n == null) return "—"
	def b = new BigDecimal(n.toString()).setScale(1, BigDecimal.ROUND_HALF_UP).stripTrailingZeros()
	return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString()
}

private String mins(long ms) { (ms / 60000).setScale(0, BigDecimal.ROUND_DOWN).toString() }

// Outdoor CO2 = mean of live outdoor monitors that report it, else the configured default
private BigDecimal outdoorCo2(List outLive, def fallback) {
	def vals = outLive.collect { val(it, "carbonDioxide") }.findAll { it != null && it > 0 }
	return vals ? (vals.sum() / vals.size()).setScale(0, BigDecimal.ROUND_HALF_UP) : new BigDecimal(fallback.toString())
}

// ── Status device ───────────────────────────────────────────────────────────

private String statusDni() { "erv-status-${app.id}" }

private void ensureStatusDevice() {
	if (getChildDevice(statusDni())) return
	try {
		addChildDevice("djkoser", "ERV Status", statusDni(), [label: "ERV Status", isComponent: false])
		log.info "ERV Auto: created the ERV Status device"
	} catch (e) {
		log.warn "ERV Auto: couldn't create the ERV Status device (is the ERV Status driver installed?): ${e.message}"
	}
}

def relayHandler(evt) { updateStatusDevice() }

// What the equipment is doing, from the normally-closed stop relays (relay off = runs)
private String equipmentStatus() {
	boolean erv = ervStopRelay.currentValue("switch") == "off"
	boolean ah = ahStopRelay.currentValue("switch") == "off"
	return erv ? "On" : (ah ? "Recirculate" : "Off")
}

private void updateStatusDevice() {
	def dev = getChildDevice(statusDni())
	if (!dev) return
	def s = equipmentStatus()
	if (dev.currentValue("ervStatus") != s)
		dev.sendEvent(name: "ervStatus", value: s, descriptionText: "ERV is ${s}")
	dev.sendEvent(name: "ervRunning", value: (ervStopRelay.currentValue("switch") == "off").toString())
	dev.sendEvent(name: "airHandlerRunning", value: (ahStopRelay.currentValue("switch") == "off").toString())
	boolean auto = autoSwitch?.currentValue("switch") == "on"
	def cause = auto ? (state.cause ?: "Air good") : "Manual"
	def reason = auto ? (state.reason ?: "") : "manual ERV mode (Auto is off)"
	if (dev.currentValue("ervCause") != cause) dev.sendEvent(name: "ervCause", value: cause, descriptionText: "ERV reason: ${cause}")
	if (dev.currentValue("ervReason") != reason) dev.sendEvent(name: "ervReason", value: reason)
}

// Set relays for a state. Order keeps the air handler running whenever the ERV runs.
private void enforce(String s) {
	switch (s) {
		case "On":
			if (ahStopRelay.currentValue("switch") != "off") ahStopRelay.off()
			if (ervStopRelay.currentValue("switch") != "off") ervStopRelay.off()
			break
		case "Recirculate":
			if (ahStopRelay.currentValue("switch") != "off") ahStopRelay.off()
			if (ervStopRelay.currentValue("switch") != "on") ervStopRelay.on()
			break
		case "Off":
			if (ervStopRelay.currentValue("switch") != "on") ervStopRelay.on()
			if (ahStopRelay.currentValue("switch") != "on") ahStopRelay.on()
			break
	}
}

private void publish(String s, String reason) {
	if (getGlobalVar("ervAutoState") != null)  setGlobalVar("ervAutoState", s)
	if (getGlobalVar("ervAutoReason") != null) setGlobalVar("ervAutoReason", reason.take(250))
}

// ── Helpers ──────────────────────────────────────────────────────────────────


// Trips at >= high, clears at <= high - buffer, otherwise keeps the previous value.
// Unknown readings (sensor absent) count as "not bad".
private boolean hyst(prev, value, high, buffer) {
	if (value == null) return false
	if (value >= high) return true
	if (value <= high - buffer) return false
	return prev ? true : false
}

private boolean isStale(dev, staleMs) {
	if (dev.currentValue("healthStatus") == "offline") return true
	def seen = (state.seen ?: [:])[dev.id.toString()]
	if (seen == null) {
		// No heartbeat since install: fall back to the device's last activity
		def la = dev.getLastActivity()
		return la == null || now() - la.time > staleMs
	}
	return now() - (seen as Long) > staleMs
}

// Settings saved before multi-monitor support hold a single device; normalize to lists
private List indoorDevs()  { def x = indoor;  x instanceof Collection ? (x as List) : (x ? [x] : []) }
private List outdoorDevs() { def x = outdoor; x instanceof Collection ? (x as List) : (x ? [x] : []) }

// Highest reading of an attribute across devices: [v: value, name: device name], or null
private Map worst(List devs, String attr) {
	Map best = null
	devs.each { d ->
		def v = val(d, attr)
		if (v != null && (best == null || v > best.v)) best = [v: v, name: d.displayName]
	}
	return best
}

// CO2 row: indoor worst vs outdoor, as ppm above outdoor
private String co2Row(List inLive, List outLive, boolean bad) {
	def m = worst(inLive, "carbonDioxide")
	def out = outdoorCo2(outLive, param("ervAutoOutdoorCo2Default"))
	def hi = param("ervAutoCo2AboveOutdoor"), clear = hi - param("ervAutoCo2Buffer")
	def now = m ? "${m.v - out} <small>(${m.v}${indoorDevs().size() > 1 ? ' ' + m.name : ''} vs outdoor ${out})</small>" : "—"
	"<tr><td>Indoor CO₂ above outdoor (ppm)</td><td>${now}</td><td>≥ ${hi}</td><td>≤ ${clear}</td>" +
	"<td>${bad ? "<b style='color:#c00'>high</b>" : 'ok'}</td></tr>"
}

// Current reading vs trigger/clear levels for each input, for the status section
private String readingsTable() {
	def staleMs = (param("ervAutoStaleMinutes") as BigDecimal) * 60000
	def inLive  = indoorDevs().findAll { !isStale(it, staleMs) }
	def outLive = outdoorDevs().findAll { !isStale(it, staleMs) }
	def f = state.flags ?: [:]
	boolean multi = indoorDevs().size() > 1 || outdoorDevs().size() > 1
	def cell = { Map m -> m ? "${m.v}${multi ? " <small>(${m.name})</small>" : ''}" : "—" }
	def row = { String label, Map m, String high, String buffer, boolean bad ->
		def hi = param(high), clear = param(high) - param(buffer)
		"<tr><td>${label}</td><td>${cell(m)}</td><td>≥ ${hi}</td><td>≤ ${clear}</td>" +
		"<td>${bad ? "<b style='color:#c00'>high</b>" : 'ok'}</td></tr>"
	}
	def rows = [
		co2Row(inLive, outLive, f.inCo2Bad as boolean),
		row("Indoor VOC index",      worst(inLive, "vocIndex"),      "ervAutoVocHigh", "ervAutoVocBuffer", f.inVocBad as boolean),
		row("Indoor NOx index",      worst(inLive, "noxIndex"),      "ervAutoNoxHigh", "ervAutoNoxBuffer", f.inNoxBad as boolean),
		row("Indoor PM2.5 (µg/m³)",  worst(inLive, "pm25"),          "ervAutoPm25High", "ervAutoPm25Buffer", f.inPmBad as boolean),
		row("Outdoor PM2.5 (µg/m³)", worst(outLive, "pm25"),         "ervAutoOutdoorPm25High", "ervAutoOutdoorPm25Buffer", f.outPmBad as boolean),
		row("Outdoor VOC index",     worst(outLive, "vocIndex"),     "ervAutoOutdoorVocHigh", "ervAutoOutdoorGasBuffer", f.outVocBad as boolean),
		row("Outdoor NOx index",     worst(outLive, "noxIndex"),     "ervAutoOutdoorNoxHigh", "ervAutoOutdoorGasBuffer", f.outNoxBad as boolean),
	]
	return "<table style='font-size:13px'><tr><th align=left>Reading</th><th align=left>Now${multi ? ' (worst)' : ''}</th>" +
		   "<th align=left>Triggers at</th><th align=left>Clears at</th><th align=left></th></tr>${rows.join('')}</table>" +
		   "<small>CO₂ at or above ${param('ervAutoCo2Critical')} ppm forces On regardless of outdoor air.</small>"
}

private String monitorStatus() {
	def staleMs = (param("ervAutoStaleMinutes") as BigDecimal) * 60000
	def fmt = { d -> "${d.displayName}${isStale(d, staleMs) ? ' <span style=\"color:#c00\">(offline/stale)</span>' : ''}" }
	return "indoor: ${indoorDevs().collect(fmt).join(', ') ?: 'none'} &nbsp; outdoor: ${outdoorDevs().collect(fmt).join(', ') ?: 'none'}"
}

private val(dev, String attr) {
	def v = dev?.currentValue(attr)
	if (v == null) return null
	try { return new BigDecimal(v.toString()) } catch (e) { return null }
}

private param(String name) {
	def d = PARAMS[name]
	def v = settings[name]
	if (v == null || v.toString() == "") return d[1] instanceof String ? d[1] : new BigDecimal(d[1].toString())
	if (d[0] == "String") return v.toString().trim()
	try { return new BigDecimal(v.toString()) } catch (e) {
		log.warn "ERV Auto: setting ${name}='${v}' is not a number; using ${d[1]}"
		return new BigDecimal(d[1].toString())
	}
}

private String stateParam(value, String fallback) {
	def v = STATES.find { it.equalsIgnoreCase(value?.toString()) }
	if (!v) log.warn "ERV Auto: '${value}' is not On, Recirculate or Off; using ${fallback}"
	return v ?: fallback
}
