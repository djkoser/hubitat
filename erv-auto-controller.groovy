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
 *                                                     -> On, or Recirculate if outdoor air shows a plume:
 *                                                        PM2.5 high (smoke/dust), NOx high (exhaust) or
 *                                                        a strong VOC spike
 *      (CO2 is relative: ventilation can only bring it down to the outdoor level. VOC/NOx are
 *      Sensirion indices, each relative to its own sensor's 24-h baseline, so an indoor and an
 *      outdoor index can't be compared; an elevated outdoor VOC index alone doesn't block
 *      ventilation, because indoor VOCs usually exceed outdoor ones.)
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
 * It evaluates once a minute; a monitor is offline when its driver says so or it hasn't
 * updated for ervAutoStaleMinutes.
 */

definition(
	name: "ERV Auto Controller",
	namespace: "djkoser",
	author: "David Koser",
	description: "Ventilate, recirculate or idle an ERV + air handler from indoor/outdoor air quality",
	category: "Convenience",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/erv-auto-controller.groovy",
	singleThreaded: true   // the minute schedule and relay events don't overlap
)

preferences { page(name: "mainPage") }

// Settings: name: [type, default, title, why this default (with [n] = REFERENCES entries)]
@groovy.transform.Field static final Map PARAMS = [
	ervAutoCo2AboveOutdoor:    ["Number", 600,  "Indoor CO2 this many ppm above outdoor triggers ventilation",
		"Default 600 ppm. ASHRAE relates ~700 ppm above outdoor to 80% odor acceptability for visitors (a ventilation indicator, not a health limit) [1]; EN 16798-1 Category I (high expectation) rooms allow 550 above outdoor and Category II bedrooms 550 [2]. 600 sits between them."],
	ervAutoCo2Buffer:          ["Number", 150,  "CO2 must fall this far below the threshold to clear",
		"Default 150 ppm: about twice the CO2 sensor's accuracy near 1000 ppm (±40 ppm ±3% of reading ≈ ±70 ppm) [3], so sensor error alone can't flip the decision back and forth."],
	ervAutoOutdoorCo2Default:  ["Number", 420,  "Outdoor CO2 (ppm) assumed when no outdoor monitor reports it",
		"Default 420 ppm: the global background (NOAA global mean 422.8 ppm in 2024, rising about 2.5 ppm a year) [4]. Used only when no live outdoor monitor reports CO2."],
	ervAutoCo2Critical:        ["Number", 1500, "Indoor CO2 (ppm) that forces On regardless of outdoor air",
		"Default 1500 ppm: UK school guidance BB101 caps the daily occupied average at 1500 ppm [6], and Health Canada's residential long-term (24-hour) limit is 1000 ppm [5]. At 1500 ventilation wins even against bad outdoor air."],
	ervAutoVocHigh:            ["Number", 200,  "Indoor VOC index that triggers ventilation (100 = baseline)",
		"Default 200. The VOC index puts each sensor's 24-hour average at 100 [7]; Sensirion's own example acts at 150 [8], and vendor scales call 200–249 moderate [16]. 200 leaves a margin above Sensirion's example for normal cooking/cleaning swings."],
	ervAutoVocBuffer:          ["Number", 50,   "VOC index hysteresis",
		"Default 50 index points (clears at 150, Sensirion's example action level [8]); well above the sensor's ±15-point variation [9]."],
	ervAutoNoxHigh:            ["Number", 100,  "Indoor NOx index that triggers ventilation (1 = baseline)",
		"Default 100. The NOx index is 1 in clean air and rises with combustion such as a gas stove [10]; device-to-device variation is ±50 points [9], so 100 is clearly above that, and vendor scales start 'polluted' at 100 [16]."],
	ervAutoNoxBuffer:          ["Number", 15,   "NOx index hysteresis",
		"Default 15 index points: above the sensor's ±10-point repeatability [9]."],
	ervAutoPm25High:           ["Decimal", 15,  "Indoor PM2.5 (µg/m³) that triggers filtering/ventilation",
		"Default 15 µg/m³: the WHO 24-hour PM2.5 guideline [11]."],
	ervAutoPm25Buffer:         ["Decimal", 5,   "Indoor PM2.5 hysteresis",
		"Default 5 µg/m³: clears at 10, midway between the WHO annual (5) and 24-hour (15) guidelines [11]."],
	ervAutoOutdoorPm25High:    ["Decimal", 35,  "Outdoor PM2.5 (µg/m³) considered bad",
		"Default 35 µg/m³: the US EPA 24-hour PM2.5 standard; AQI 'Unhealthy for Sensitive Groups' starts at 35.5 [12]. This is also how AQI is used here, since the monitors' AQI is computed from PM2.5."],
	ervAutoOutdoorPm25Buffer:  ["Decimal", 5,   "Outdoor PM2.5 hysteresis",
		"Default 5 µg/m³: clears at 30, safely inside the EPA 'Moderate' band (9.1–35.4) [12]."],
	ervAutoPm25Margin:         ["Decimal", 10,  "Outdoor PM2.5 must be this much lower than indoor to ventilate for PM",
		"Default 10 µg/m³: the PM sensor's stated consistency is ±10 µg/m³ below 100 µg/m³ [13], so a smaller gap between two sensors may not be real."],
	ervAutoOutdoorVocSpike:    ["Number", 400,  "Outdoor VOC index that alone blocks ventilation (a strong spike; 100 = normal for that sensor)",
		"Default 400: the top fifth of the 1–500 scale [7]. Lower outdoor values don't block ventilation, because VOC indices are relative to each sensor and indoor VOCs usually run 2–5× (up to 10×) outdoor levels [14]."],
	ervAutoOutdoorNoxHigh:     ["Number", 100,  "Outdoor NOx index considered bad",
		"Default 100: same basis as the indoor NOx trigger (baseline 1 [10], ±50 device variation [9], vendor 'polluted' from 100 [16]); signals exhaust or combustion nearby."],
	ervAutoOutdoorGasBuffer:   ["Number", 25,   "Outdoor VOC/NOx hysteresis",
		"Default 25 index points: above the NOx sensor's ±10 repeatability and the VOC sensor's ±15 variation [9]."],
	ervAutoMinRunMinutes:      ["Number", 30,   "Once started, the ERV / air handler runs at least this many minutes",
		"Default 30 minutes: longer than ERV makers' own 20-minute intermittent run [15], to run in long stretches and limit starts."],
	ervAutoMinOffMinutes:      ["Number", 15,   "Once stopped, the ERV / air handler stays off at least this many minutes",
		"Default 15 minutes: with run time ≥ 30, at most two starts an hour. ECM motors ramp up gently, so each start causes little stress [17]; a longer off time mainly delays the response to rising CO2."],
	ervAutoStaleMinutes:       ["Number", 15,   "Minutes without a sensor update before fail-safe",
		"Default 15 minutes: the AirGradient Local driver reads each monitor every 60 seconds [19], so this is 15 missed readings, long enough to ride out a Wi-Fi blip."],
	ervAutoIdleState:          ["String", "Off", "State when air is good: Off, Recirculate or On",
		"Default Off: ventilate only when a sensor shows a need (owner's choice). For comparison, ASHRAE 62.2 sizes continuous whole-house ventilation instead [18]."],
	ervAutoFailSafeState:      ["String", "On",  "State when a sensor is offline: On, Recirculate or Off",
		"Default On: with no air-quality data, ventilating is the safe choice, matching ASHRAE 62.2's continuous-ventilation baseline [18]."],
]
// Citations shown in the app UI: [n] -> [text, URL]
@groovy.transform.Field static final Map REFERENCES = [
	1:  ["ASHRAE Position Document on Indoor Carbon Dioxide (2022, revised 2025)", "https://www.ashrae.org/File%20Library/About/Position%20Documents/PD_IndoorCarbonDioxide_2025.pdf"],
	2:  ["EN 16798-1:2019 indoor environmental criteria, CO2 above outdoor by category (REHVA Journal summary)", "https://rehva.eu/rehva-journal/chapter/new-health-comfort-promoting-cen-standard"],
	3:  ["Senseair S8 LP CO2 sensor (in AirGradient monitors): accuracy ±40 ppm ±3% of reading", "https://senseair.com/products/size-counts/s8-lp/"],
	4:  ["NOAA Global Monitoring Laboratory, trends in global CO2", "https://gml.noaa.gov/ccgg/trends/global.html"],
	5:  ["Health Canada, Residential Indoor Air Quality Guidelines: Carbon Dioxide (2021)", "https://www.canada.ca/en/health-canada/services/publications/healthy-living/residential-indoor-air-quality-guidelines-carbon-dioxide.html"],
	6:  ["UK DfE BB101:2018 ventilation criteria for schools (IES summary)", "https://help.iesve.com/ve2026/3_comfort_criteria.htm"],
	7:  ["Sensirion, What is Sensirion's VOC Index? (info note)", "https://www.sudo.is/docs/esphome/components/sgp4x/Info_Note_VOC_Index.pdf"],
	8:  ["Sensirion, SGP40 VOC Index for Experts (application note)", "https://cdn.sparkfun.com/assets/e/9/3/f/e/GAS_AN_SGP40_VOC_Index_for_Experts_D1.pdf"],
	9:  ["Sensirion SEN5x / SGP41 datasheet: VOC ±15 and NOx ±50 index points device-to-device, NOx repeatability ±10", "https://cdn.sparkfun.com/assets/b/0/3/c/0/Sensirion_Datasheet_Environmental_Node_SEN5x.pdf"],
	10: ["Sensirion, What is Sensirion's NOx Index? (info note)", "https://www.sudo.is/docs/esphome/components/sgp4x/Info_Note_NOx_Index.pdf"],
	11: ["WHO Global Air Quality Guidelines (2021): PM2.5 24-hour 15 µg/m³, annual 5 µg/m³", "https://www.who.int/publications/i/item/9789240034228"],
	12: ["US EPA, 2024 PM NAAQS and Air Quality Index fact sheet", "https://www.epa.gov/system/files/documents/2024-02/pm-naaqs-air-quality-index-fact-sheet.pdf"],
	13: ["Plantower PMS5003 particle sensor manual: consistency ±10 µg/m³ at 0–100 µg/m³", "https://www.digikey.jp/htmldatasheets/production/2903006/0/0/1/pms5003-series-manual.html"],
	14: ["US EPA, Volatile Organic Compounds' Impact on Indoor Air Quality", "https://www.epa.gov/indoor-air-quality-iaq/volatile-organic-compounds-impact-indoor-air-quality"],
	15: ["Fantech EDF1R ERV wall control manual: intermittent 20 min on / 40 min off", "https://shop.fantech.net/upload/assets/403391_EDF1R_INSTALLATION_ENG-FR_20190604_132321993.PDF"],
	16: ["Atmotube sensor thresholds (vendor VOC/NOx index bands)", "https://intercom.help/atmotech-inc/en/articles/10450324-sensor-thresholds"],
	17: ["EEPower, What is an electronically commutated motor (ECM)? (soft start)", "https://eepower.com/technical-articles/what-is-an-electronically-commutated-motor-ecm/"],
	18: ["ASHRAE Standard 62.2, Ventilation and Acceptable Indoor Air Quality in Residential Buildings", "https://www.ashrae.org/technical-resources/bookstore/standards-62-1-62-2"],
	19: ["AirGradient Local driver (this package): polls each monitor every 60 s by default", "https://github.com/djkoser/hubitat/blob/main/airgradient-local.groovy"],
]
@groovy.transform.Field static final List STATES = ["On", "Recirculate", "Off"]
// Settings replaced Nov 2026, removed on update
@groovy.transform.Field static final List RETIRED_SETTINGS = ["ervAutoCo2High", "ervAutoMinDwellMinutes", "ervAutoMinVentMinutesPerHour",
                                                               "ervAutoOutdoorVocHigh", "ervAutoVocTrialMinutes", "ervAutoVocTrialRise",
                                                               "ervAutoVocHoldoffMinutes"]
// State from removed features, cleared on update
@groovy.transform.Field static final List RETIRED_STATE = ["ventMs", "ventHour", "ventTick", "hubVarsMigrated", "seen",
                                                            "vocTrial", "vocVeto", "lastVocTrial"]

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
				paragraph thinkingHtml() + "<br>" + readingsTable()
			}
		}
		section("Thresholds") {
			paragraph "<small>Each setting shows its default and why it was chosen; [n] refers to the References section below.</small>"
			PARAMS.each { n, d ->
				def title = "${d[2]}<br><small style='color:#666'>${d[3]}</small>"   // always visible (a description is only a placeholder)
				if (d[0] == "String") input n, "enum", title: title, options: STATES, defaultValue: d[1], required: false
				else input n, d[0] == "Decimal" ? "decimal" : "number", title: title, defaultValue: d[1], required: false
			}
		}
		section("References") {
			paragraph REFERENCES.collect { k, r -> "[${k}] <a href='${r[1]}' target='_blank'>${r[0]}</a>" }.join("<br>")
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
	RETIRED_STATE.each { state.remove(it) }
	removeAllInUseGlobalVar()   // the ervAutoState/ervAutoReason hub variables were retired for the ERV Status device
	initialize()
}
def uninstalled() { }


def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	state.flags = state.flags ?: [:]
	subscribe(autoSwitch, "switch", "autoSwitchHandler")
	runEvery1Minute("evaluate")   // the minimum run/off times make faster reactions pointless
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

// ── Decision ─────────────────────────────────────────────────────────────────

def evaluate() {
	def p = PARAMS.collectEntries { n, d -> [(n): param(n)] }
	if (autoSwitch?.currentValue("switch") != "on") {
		updateStatusDevice(measure(p), p)   // readings stay current in manual modes too
		return
	}

	def f = state.flags ?: [:]

	def r = measure(p)
	def inLive = r.inLive, outLive = r.outLive, stale = r.stale
	def inCo2M = r.inCo2M, inVocM = r.inVocM, inNoxM = r.inNoxM, inPmM = r.inPmM
	def inCo2 = inCo2M?.v, inVoc = inVocM?.v, inNox = inNoxM?.v, inPm = inPmM?.v
	def outPm = r.outPmM?.v, outVoc = r.outVocM?.v, outNox = r.outNoxM?.v
	def outCo2 = r.outCo2, co2Excess = r.co2Excess
	boolean multiIn = indoorDevs().size() > 1
	def where = { m -> (multiIn && m) ? " (${m.name})" : "" }

	f.inCo2Bad  = hyst(f.inCo2Bad,  co2Excess, p.ervAutoCo2AboveOutdoor, p.ervAutoCo2Buffer)
	f.inVocBad  = hyst(f.inVocBad,  inVoc, p.ervAutoVocHigh,         p.ervAutoVocBuffer)
	f.inNoxBad  = hyst(f.inNoxBad,  inNox, p.ervAutoNoxHigh,         p.ervAutoNoxBuffer)
	f.inPmBad   = hyst(f.inPmBad,   inPm,  p.ervAutoPm25High,        p.ervAutoPm25Buffer)
	f.outPmBad  = hyst(f.outPmBad,  outPm, p.ervAutoOutdoorPm25High, p.ervAutoOutdoorPm25Buffer)
	f.outVocBad = hyst(f.outVocBad, outVoc, p.ervAutoOutdoorVocSpike, p.ervAutoOutdoorGasBuffer)
	f.outNoxBad = hyst(f.outNoxBad, outNox, p.ervAutoOutdoorNoxHigh, p.ervAutoOutdoorGasBuffer)
	state.flags = f

	// Each reading against its limits, for the reason text
	def co2Text = co2Excess == null ? "CO2 —" :
		vsLimit("CO2 over outdoor", co2Excess, p.ervAutoCo2AboveOutdoor, p.ervAutoCo2Buffer, f.inCo2Bad, "ppm") +
		" (${fmt(inCo2)} ppm${multiIn && inCo2M ? ' in ' + inCo2M.name : ''} vs ${fmt(outCo2)} ppm outdoors)"
	def inVocText  = vsLimit("VOC index", inVoc, p.ervAutoVocHigh, p.ervAutoVocBuffer, f.inVocBad) + (f.inVocBad ? where(inVocM) : "")
	def inNoxText  = vsLimit("NOx index", inNox, p.ervAutoNoxHigh, p.ervAutoNoxBuffer, f.inNoxBad) + (f.inNoxBad ? where(inNoxM) : "")
	def inPmText   = vsLimit("PM2.5", inPm, p.ervAutoPm25High, p.ervAutoPm25Buffer, f.inPmBad, "µg/m³") + (f.inPmBad ? where(inPmM) : "")
	def outVocText = vsLimit("VOC index", outVoc, p.ervAutoOutdoorVocSpike, p.ervAutoOutdoorGasBuffer, f.outVocBad)
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
		// Outdoor air blocks ventilation only on evidence of a plume: smoke/dust (PM2.5, absolute),
		// combustion (NOx) or a strong VOC spike
		if (f.outPmBad || f.outNoxBad || f.outVocBad) {
			target = "Recirculate"; protect = true
			def why = [f.outPmBad ? outPmText : null, f.outNoxBad ? outNoxText : null, f.outVocBad ? outVocText : null].findAll { it }
			reason = "indoor ${what}, but outdoor ${why.join(', ')}"
			cause = f.outPmBad ? "Outdoor PM2.5 high" : (f.outNoxBad ? "Outdoor NOx high" : "Outdoor VOC spike")
		} else {
			target = "On"
			reason = "indoor ${what}; outdoor ${outPmText}, ${outNoxText}, ${outVocText}"
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

	if (applied.state != state.current) {
		log.info "ERV Auto: ${state.current ?: '—'} → ${applied.state} (${reason})"
		state.current = applied.state
		state.lastChange = now()
	}
	state.reason = reason
	state.cause = cause
	enforce(applied.state)
	updateStatusDevice(r, p)
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

// Readings the decision uses: live monitors and the worst value per pollutant. Shared by
// evaluate() and the status table so both always show the same numbers.
private Map measure(Map p) {
	def staleMs = (p.ervAutoStaleMinutes as BigDecimal) * 60000
	def inLive  = indoorDevs().findAll { !isStale(it, staleMs) }
	def outLive = outdoorDevs().findAll { !isStale(it, staleMs) }
	def r = [inLive: inLive, outLive: outLive, stale: (indoorDevs() + outdoorDevs()).findAll { isStale(it, staleMs) },
			 inCo2M: worst(inLive, "carbonDioxide"), inVocM: worst(inLive, "vocIndex"), inNoxM: worst(inLive, "noxIndex"),
			 inPmM: worst(inLive, "pm25"), outPmM: worst(outLive, "pm25"), outVocM: worst(outLive, "vocIndex"),
			 outNoxM: worst(outLive, "noxIndex"), outCo2: outdoorCo2(outLive, p.ervAutoOutdoorCo2Default)]
	r.co2Excess = r.inCo2M ? r.inCo2M.v - r.outCo2 : null
	return r
}

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

// Short reasons for Google Home, with this app's thresholds in them (Google shows only values
// from a list it receives in advance; the list itself is published in ervCauseValues)
@groovy.transform.Field static final List CAUSES = ["Air good", "Indoor CO2 high", "Indoor VOC high", "Indoor NOx high",
	"Indoor PM2.5 high", "CO2 critical", "Outdoor PM2.5 high", "Outdoor NOx high", "Outdoor VOC spike",
	"Filtering particles", "Minimum run", "Minimum off", "Monitors offline", "Manual"]

private String causeLabel(String key, Map p) {
	switch (key) {
		case "Indoor CO2 high":     return "Indoor CO2 ≥ ${fmt(p.ervAutoCo2AboveOutdoor)} ppm over outdoor"
		case "Indoor VOC high":     return "Indoor VOC index ≥ ${fmt(p.ervAutoVocHigh)}"
		case "Indoor NOx high":     return "Indoor NOx index ≥ ${fmt(p.ervAutoNoxHigh)}"
		case "Indoor PM2.5 high":   return "Indoor PM2.5 ≥ ${fmt(p.ervAutoPm25High)} µg/m³"
		case "CO2 critical":        return "CO2 ≥ ${fmt(p.ervAutoCo2Critical)} ppm (critical)"
		case "Outdoor PM2.5 high":  return "Outdoor PM2.5 ≥ ${fmt(p.ervAutoOutdoorPm25High)} µg/m³"
		case "Outdoor NOx high":    return "Outdoor NOx index ≥ ${fmt(p.ervAutoOutdoorNoxHigh)}"
		case "Outdoor VOC spike":   return "Outdoor VOC index ≥ ${fmt(p.ervAutoOutdoorVocSpike)}"
		case "Filtering particles": return "Filtering: indoor PM2.5 ≥ ${fmt(p.ervAutoPm25High)} µg/m³"
		case "Minimum run":         return "Minimum run (${fmt(p.ervAutoMinRunMinutes)} min)"
		case "Minimum off":         return "Minimum off (${fmt(p.ervAutoMinOffMinutes)} min)"
		default:                    return key
	}
}

// VOC/NOx index (1–500) in 25-point bands, for Google Home (which can't show an index as a number)
@groovy.transform.Field static final List INDEX_BANDS = (0..19).collect { i -> "${i == 0 ? 1 : i * 25}–${i == 19 ? 500 : i * 25 + 24}".toString() }
private String indexBand(def v) {
	if (v == null) return null
	int i = Math.min(19, Math.max(0, (int) (new BigDecimal(v.toString()).intValue() / 25)))
	return INDEX_BANDS[i]
}

// r = measure() readings (null when only the relays changed)
private void updateStatusDevice(Map r = null, Map p = null) {
	def dev = getChildDevice(statusDni())
	if (!dev) return
	p = p ?: PARAMS.collectEntries { n, d -> [(n): param(n)] }
	def set = { String name, def value, String unit = null ->
		if (value != null && dev.currentValue(name)?.toString() != value.toString())
			dev.sendEvent(name: name, value: value, unit: unit)
	}
	def s = equipmentStatus()
	if (dev.currentValue("ervStatus") != s) dev.sendEvent(name: "ervStatus", value: s, descriptionText: "ERV is ${s}")
	boolean auto = autoSwitch?.currentValue("switch") == "on"
	set("ervCauseValues", groovy.json.JsonOutput.toJson(CAUSES.collect { causeLabel(it, p) }))
	set("indexBandValues", groovy.json.JsonOutput.toJson(INDEX_BANDS))
	set("ervCause", causeLabel(auto ? (state.cause ?: "Air good") : "Manual", p))
	set("ervReason", auto ? (state.reason ?: "") : "manual ERV mode (Auto is off)")
	if (r) {
		set("carbonDioxide", r.inCo2M?.v, "ppm")
		set("pm25", r.inPmM?.v, "µg/m³")
		set("vocIndexBand", indexBand(r.inVocM?.v))
		set("noxIndexBand", indexBand(r.inNoxM?.v))
	}
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

// ── Helpers ──────────────────────────────────────────────────────────────────


// Trips at >= high, clears at <= high - buffer, otherwise keeps the previous value.
// Unknown readings (sensor absent) count as "not bad".
private boolean hyst(prev, value, high, buffer) {
	if (value == null) return false
	if (value >= high) return true
	if (value <= high - buffer) return false
	return prev ? true : false
}

// Offline when the driver says so, or no update (the AirGradient driver updates every minute) for staleMs
private boolean isStale(dev, staleMs) {
	if (dev.currentValue("healthStatus") == "offline") return true
	def la = dev.getLastActivity()
	return la == null || now() - la.time > staleMs
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

// What the controller is doing and why, for the Status section
private String thinkingHtml() {
	boolean auto = autoSwitch?.currentValue("switch") == "on"
	def lines = []
	lines << "<b style='font-size:15px'>${auto ? (state.current ?: equipmentStatus()) : equipmentStatus()}</b>" + (auto ? " — <b>${state.cause ? causeLabel(state.cause, PARAMS.collectEntries { n, d -> [(n): param(n)] }) : '—'}</b>" : " — <b>Manual</b> (another ERV mode is selected; Auto is off)")
	if (auto) lines << "${state.reason ?: '—'}"
	lines << "<b>Since:</b> ${state.lastChange ? new Date(state.lastChange as Long).format('MMM d h:mm a', location.timeZone) : '—'}"
	def eq = state.equip ?: [:]
	def minRun = param("ervAutoMinRunMinutes"), minOff = param("ervAutoMinOffMinutes")
	def eqText = { String label, Map e ->
		if (!e) return null
		long age = now() - ((e.since ?: 0) as Long)
		if (!e.since) return "${label} ${e.on ? 'running' : 'off'}"
		e.on ? "${label} running ${mins(age)} min (minimum ${minRun})" : "${label} off ${mins(age)} min (minimum ${minOff})"
	}
	def eqLines = [eqText("ERV", eq.erv), eqText("Air handler", eq.ah)].findAll { it }
	if (eqLines) lines << "<b>Equipment:</b> ${eqLines.join('; ')}"
	lines << "<b>Monitors:</b> ${monitorStatus()}"
	return lines.join("<br>")
}

// Each reading against its trigger/clear levels, from the same measure() the decision uses
private String readingsTable() {
	def p = PARAMS.collectEntries { n, d -> [(n): param(n)] }
	def r = measure(p)
	def f = state.flags ?: [:]
	boolean multi = indoorDevs().size() > 1 || outdoorDevs().size() > 1
	def cell = { Map m -> m ? "${fmt(m.v)}${multi ? " <small>(${m.name})</small>" : ''}" : "—" }
	def row = { String label, String now, high, buffer, boolean bad ->
		"<tr><td>${label}</td><td>${now}</td><td>≥ ${fmt(high)}</td><td>≤ ${fmt(high - buffer)}</td>" +
		"<td>${bad ? "<b style='color:#c00'>high</b>" : 'ok'}</td></tr>"
	}
	def co2Now = r.co2Excess == null ? "—" :
		"${fmt(r.co2Excess)} <small>(${fmt(r.inCo2M.v)}${multi ? ' ' + r.inCo2M.name : ''} vs outdoor ${fmt(r.outCo2)})</small>"
	def rows = [
		row("Indoor CO₂ above outdoor (ppm)", co2Now, p.ervAutoCo2AboveOutdoor, p.ervAutoCo2Buffer, f.inCo2Bad as boolean),
		row("Indoor VOC index", cell(r.inVocM), p.ervAutoVocHigh, p.ervAutoVocBuffer, f.inVocBad as boolean),
		row("Indoor NOx index", cell(r.inNoxM), p.ervAutoNoxHigh, p.ervAutoNoxBuffer, f.inNoxBad as boolean),
		row("Indoor PM2.5 (µg/m³)", cell(r.inPmM), p.ervAutoPm25High, p.ervAutoPm25Buffer, f.inPmBad as boolean),
		row("Outdoor PM2.5 (µg/m³)", cell(r.outPmM), p.ervAutoOutdoorPm25High, p.ervAutoOutdoorPm25Buffer, f.outPmBad as boolean),
		row("Outdoor VOC index (spike)", cell(r.outVocM), p.ervAutoOutdoorVocSpike, p.ervAutoOutdoorGasBuffer, f.outVocBad as boolean),
		row("Outdoor NOx index", cell(r.outNoxM), p.ervAutoOutdoorNoxHigh, p.ervAutoOutdoorGasBuffer, f.outNoxBad as boolean),
	]
	return "<table style='font-size:13px'><tr><th align=left>Reading</th><th align=left>Now${multi ? ' (worst)' : ''}</th>" +
		   "<th align=left>Triggers at</th><th align=left>Clears at</th><th align=left></th></tr>${rows.join('')}</table>" +
		   "<small>CO₂ at or above ${fmt(p.ervAutoCo2Critical)} ppm forces On regardless of outdoor air.</small>"
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
