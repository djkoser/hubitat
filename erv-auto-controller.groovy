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
 *   2. Indoor CO2 >= critical              -> On, whatever is outside
 *   3. Indoor CO2/VOC/NOx high             -> On, or Recirculate if outdoor VOC/NOx is high
 *   4. Indoor PM2.5 high                   -> On if outdoor PM2.5 is fine and lower than
 *                                             indoor by the margin, else Recirculate
 *   5. Everything good                     -> idle state (default Off), except that the
 *                                             ERV is forced On late in each clock hour if it
 *                                             has not yet run the minimum ventilation minutes
 *                                             (ASHRAE 62.2: sensors add ventilation, they do not
 *                                             replace the continuous whole-house rate)
 * Each "high" flag has hysteresis: it trips at the threshold and clears only once the
 * value drops below threshold minus its buffer. A state is held for a minimum dwell
 * time before it may change (except for fail-safe and critical CO2).
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

// Settings: name: [type, default, description]. Names match the hub variables they
// replaced (before Nov 2026), so migrateFromHubVariables() can carry old values over.
@groovy.transform.Field static final Map PARAMS = [
	ervAutoCo2High:            ["Number", 1000, "Indoor CO2 (ppm) that triggers ventilation"],
	ervAutoCo2Buffer:          ["Number", 150,  "CO2 must fall this far below the threshold to clear"],
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
	ervAutoMinDwellMinutes:    ["Number", 15,   "Minimum minutes a state is held before changing"],
	ervAutoStaleMinutes:       ["Number", 15,   "Minutes without a sensor update before fail-safe"],
	ervAutoMinVentMinutesPerHour: ["Number", 20, "Minimum ERV run minutes per clock hour (ASHRAE 62.2 continuous-rate equivalent; 0 = disabled)"],
	ervAutoIdleState:          ["String", "Off", "State when air is good: Off, Recirculate or On"],
	ervAutoFailSafeState:      ["String", "On",  "State when a sensor is offline: On, Recirculate or Off"],
]
// Outputs published for other apps/dashboards (create them as String hub variables to use them)
@groovy.transform.Field static final List OUTPUTS = ["ervAutoState", "ervAutoReason"]
@groovy.transform.Field static final List STATES = ["On", "Recirculate", "Off"]

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
def updated()   { unsubscribe(); unschedule(); initialize() }
def uninstalled() { removeAllInUseGlobalVar() }

// One-time carry-over of settings that lived in hub variables before Nov 2026. Runs once
// (state.hubVarsMigrated): the first save of the new settings page also stores each field's
// default, so the old hub variable's value must win on that first run. Safe to delete later.
private void migrateFromHubVariables() {
	if (state.hubVarsMigrated) return
	state.hubVarsMigrated = true
	PARAMS.each { n, d ->
		def gv = getGlobalVar(n)
		if (gv?.value == null || gv.value.toString() == "") return
		def type = d[0] == "String" ? "enum" : (d[0] == "Decimal" ? "decimal" : "number")
		def value = d[0] == "String" ? gv.value.toString() : (d[0] == "Decimal" ? new BigDecimal(gv.value.toString()) : (gv.value.toString() as BigDecimal).intValue())
		app.updateSetting(n, [type: type, value: value])
		log.info "ERV Auto: moved setting ${n} = ${value} from its hub variable into the app"
	}
}

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	state.flags = state.flags ?: [:]
	migrateFromHubVariables()
	removeAllInUseGlobalVar()
	addInUseGlobalVar(OUTPUTS)
	subscribe(autoSwitch, "switch", "autoSwitchHandler")
	["pm25", "carbonDioxide", "vocIndex", "noxIndex", "healthStatus", "lastUpdate"].each {
		subscribe(indoorDevs(), it, "sensorHandler")
		subscribe(outdoorDevs(), it, "sensorHandler")
	}
	runEvery1Minute("evaluate")
	evaluate()
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ── Handlers ─────────────────────────────────────────────────────────────────

def autoSwitchHandler(evt) {
	if (evt.value == "on") {
		state.lastChange = 0   // entering Auto: apply immediately, no dwell
		evaluate()
	}
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
	accountVentilation()

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
	boolean multiIn = indoorDevs().size() > 1
	def where = { m -> (multiIn && m) ? " (${m.name})" : "" }

	f.inCo2Bad  = hyst(f.inCo2Bad,  inCo2, p.ervAutoCo2High,         p.ervAutoCo2Buffer)
	f.inVocBad  = hyst(f.inVocBad,  inVoc, p.ervAutoVocHigh,         p.ervAutoVocBuffer)
	f.inNoxBad  = hyst(f.inNoxBad,  inNox, p.ervAutoNoxHigh,         p.ervAutoNoxBuffer)
	f.inPmBad   = hyst(f.inPmBad,   inPm,  p.ervAutoPm25High,        p.ervAutoPm25Buffer)
	f.outPmBad  = hyst(f.outPmBad,  outPm, p.ervAutoOutdoorPm25High, p.ervAutoOutdoorPm25Buffer)
	f.outVocBad = hyst(f.outVocBad, outVoc, p.ervAutoOutdoorVocHigh, p.ervAutoOutdoorGasBuffer)
	f.outNoxBad = hyst(f.outNoxBad, outNox, p.ervAutoOutdoorNoxHigh, p.ervAutoOutdoorGasBuffer)
	state.flags = f

	def target, reason, urgent = false

	if (!inLive || !outLive) {
		target = stateParam(p.ervAutoFailSafeState, "On"); urgent = true
		reason = "${!inLive ? 'all indoor' : 'all outdoor'} monitors offline/stale (${stale*.displayName.join(', ')})"
	} else if (inCo2 != null && inCo2 >= p.ervAutoCo2Critical) {
		target = "On"; urgent = true
		reason = "CO2 ${inCo2}${where(inCo2M)} ≥ critical ${p.ervAutoCo2Critical}"
	} else if (f.inCo2Bad || f.inVocBad || f.inNoxBad) {
		def what = [f.inCo2Bad ? "CO2 ${inCo2}${where(inCo2M)}" : null, f.inVocBad ? "VOC ${inVoc}${where(inVocM)}" : null,
					f.inNoxBad ? "NOx ${inNox}${where(inNoxM)}" : null].findAll { it }.join(", ")
		if (f.outVocBad || f.outNoxBad) {
			target = "Recirculate"
			reason = "indoor ${what} high but outdoor gases bad (VOC ${outVoc}, NOx ${outNox})"
		} else {
			target = "On"
			reason = "indoor ${what} high"
		}
	} else if (f.inPmBad) {
		if (!f.outPmBad && outPm != null && inPm != null && outPm + p.ervAutoPm25Margin <= inPm) {
			target = "On"
			reason = "indoor PM2.5 ${inPm}${where(inPmM)} high, outdoor ${outPm} cleaner"
		} else {
			target = "Recirculate"
			reason = "indoor PM2.5 ${inPm}${where(inPmM)} high, outdoor ${outPm} not cleaner — filtering"
		}
	} else {
		target = stateParam(p.ervAutoIdleState, "Off")
		reason = "air good"
		// Minimum ventilation duty: if the remaining minutes this hour are no more than the
		// ventilation still owed, run now (deadline scheduling, so sensor-driven runs count).
		def needMin = (p.ervAutoMinVentMinutesPerHour as BigDecimal).min(60)
		if (target != "On" && needMin > 0) {
			def owedMin = needMin - ((state.ventMs ?: 0) as BigDecimal) / 60000
			def cal = Calendar.getInstance(location.timeZone)
			def leftMin = 60 - cal.get(Calendar.MINUTE) - cal.get(Calendar.SECOND) / 60
			if (owedMin > 0 && leftMin <= owedMin + 1) {
				target = "On"; urgent = true
				reason = "minimum ventilation (${needMin - owedMin.setScale(0, BigDecimal.ROUND_UP)} of ${needMin} min this hour)"
			}
		}
	}

	// Name any monitor that is being ignored, without triggering the fail-safe
	if (stale && inLive && outLive) reason += " (ignoring offline: ${stale*.displayName.join(', ')})"

	def current = state.current
	if (target != current) {
		def dwellMs = (p.ervAutoMinDwellMinutes as BigDecimal) * 60000
		def held = now() - ((state.lastChange ?: 0) as Long)
		if (!urgent && current && held < dwellMs) {
			if (logEnable) log.debug "Holding ${current} (${(held / 60000).setScale(1, BigDecimal.ROUND_DOWN)} of ${p.ervAutoMinDwellMinutes} min); wants ${target}: ${reason}"
			enforce(current)
			return
		}
		log.info "ERV Auto: ${current ?: '—'} → ${target} (${reason})"
		state.current = target
		state.lastChange = now()
		publish(target, reason)
	} else if (reason != state.reason) {
		publish(target, reason)
	}
	state.reason = reason
	enforce(target)
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

// Accumulates ERV run time (relay-confirmed) within the current clock hour.
private void accountVentilation() {
	def hourKey = new Date().format("yyyyMMddHH", location.timeZone)
	def t = now()
	if (state.ventHour != hourKey) {
		state.ventHour = hourKey
		state.ventMs = 0
		state.ventTick = t
		return
	}
	def last = (state.ventTick ?: t) as Long
	if (ervStopRelay.currentValue("switch") == "off") {   // relay off = ERV running
		state.ventMs = ((state.ventMs ?: 0) as Long) + Math.min(t - last, 120000L)   // cap gaps (hub restarts)
	}
	state.ventTick = t
}

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
		row("Indoor CO₂ (ppm)",      worst(inLive, "carbonDioxide"), "ervAutoCo2High", "ervAutoCo2Buffer", f.inCo2Bad as boolean),
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
