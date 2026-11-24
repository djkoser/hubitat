/**
 * Bathroom Humidity Fan — Hubitat App (install one instance per bathroom)
 *
 * Compares a bathroom humidity sensor to a reference sensor elsewhere in the house
 * (e.g. the AirGradient indoor monitor). The difference ("delta", in %RH) drives the fan:
 *
 *   - Auto on:   delta rises to bathFanStartDelta or more and the fan is off -> fan on.
 *   - Hold on:   while delta is above bathFanStopDelta, an "off" (paddle or remote) is
 *                refused: the fan comes straight back on, and for paddle presses the
 *                switch's LED blinks to show the press was heard.
 *   - Override:  a second off within bathFanOverrideSeconds of a refused one is obeyed,
 *                and the fan is left alone until delta drops back to bathFanStopDelta.
 *   - Auto off:  once delta is back within bathFanStopDelta, a fan the app turned on or
 *                held on is switched off. A fan you turned on yourself and never tried to
 *                turn off is left alone.
 *   - Time limit: the app stops holding/auto-running a fan after bathFanMaxMinutes,
 *                and turns it off if it was holding it.
 *
 * If either sensor has no reading (or the reference reports offline), the app neither
 * starts nor holds the fan: it fails open, back to a plain switch.
 *
 * LED blink: Zooz switches (jtp10181 drivers) have no LED-blink command (flash() cycles
 * the load), so the app alternates the LED mode On/Off and then restores the chosen
 * normal mode. For a ZEN30, pick the ZEN30 itself as the LED device and "Relay".
 *
 * Tunables are hub variables shared by all instances (Settings > Hub Variables).
 */

definition(
	name: "Bathroom Humidity Fan",
	namespace: "djkoser",
	author: "David Koser",
	description: "Runs a bathroom fan from humidity relative to a reference sensor, refuses off while humid, blinks the switch LED",
	category: "Convenience",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/bathroom-humidity-fan.groovy"
)

preferences { page(name: "mainPage") }

// name: [default, description]
@groovy.transform.Field static final Map PARAMS = [
	bathFanStartDelta:      [10, "%RH above the reference that turns the fan on"],
	bathFanStopDelta:       [3,  "%RH above the reference at which the fan may turn off"],
	bathFanMaxMinutes:      [60, "Minutes after which the app stops holding/auto-running the fan"],
	bathFanOverrideSeconds: [10, "A second off within this many seconds forces the fan off"],
	bathFanLedBlinks:       [3,  "LED blinks when an off is refused"],
]

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Bathroom") {
			label title: "Name this bathroom (e.g. Master Bathroom)", required: true
			input "fan", "capability.switch", title: "Exhaust fan switch", required: true
			input "bathSensor", "capability.relativeHumidityMeasurement", title: "Bathroom humidity sensor", required: true
			input "refSensor", "capability.relativeHumidityMeasurement", title: "Reference humidity sensor (rest of the house)", required: true
		}
		section("Switch LED (blinks when an off is refused)") {
			input "ledDevice", "capability.switch", title: "Device with the LED (leave blank to use the fan switch)", required: false
			input "ledChannel", "enum", title: "LED channel (ZEN30: Relay for the fan)", options: ["", "Relay", "Dimmer"], defaultValue: "", required: false
			input "ledNormalMode", "enum", title: "LED mode to restore after blinking", options: ["Default", "Reverse", "Off", "On"], defaultValue: "Default"
		}
		if (app.id) {
			section("Status") {
				def d = delta()
				paragraph "<b>Bathroom:</b> ${num(bathSensor?.currentValue('humidity')) ?: '—'}%  <b>Reference:</b> ${num(refSensor?.currentValue('humidity')) ?: '—'}%  " +
						  "<b>Delta:</b> ${d != null ? d.setScale(1, BigDecimal.ROUND_HALF_UP) + '%' : 'unavailable'}<br>" +
						  "<b>State:</b> ${stateText()}"
			}
		}
		section("Settings (hub variables, shared by all bathrooms)") {
			def rows = PARAMS.collect { n, d ->
				def gv = getGlobalVar(n)
				"<tr><td>${n}</td><td>${gv != null ? gv.value : "<span style='color:#c00'>missing — using ${d[0]}</span>"}</td><td>${d[1]}</td></tr>"
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
def updated()   { unsubscribe(); unschedule(); initialize() }
def uninstalled() { removeAllInUseGlobalVar() }

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	removeAllInUseGlobalVar()
	addInUseGlobalVar(PARAMS.keySet() as List)
	subscribe(bathSensor, "humidity", "humidityHandler")
	subscribe(refSensor, "humidity", "humidityHandler")
	subscribe(fan, "switch", "fanHandler")
	evaluate()
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ── State ────────────────────────────────────────────────────────────────────
// state.holding    – app owns the fan (auto-started it or refused an off); turns it off when dry
// state.holdSince  – when holding began (time limit)
// state.override   – user forced it off; leave the fan alone until dry
// state.expired    – time limit reached; leave the fan alone until dry
// state.refusedAt  – last refused off (for the double-press override)
// state.ignoreUntil – ignore our own switch events until this time

private BigDecimal delta() {
	def b = num(bathSensor?.currentValue("humidity"))
	def r = num(refSensor?.currentValue("humidity"))
	if (b == null || r == null) return null
	if (refSensor.hasAttribute("healthStatus") && refSensor.currentValue("healthStatus") == "offline") return null
	return b - r
}

private boolean humid() { def d = delta(); d != null && d > param("bathFanStopDelta") }

def humidityHandler(evt) { evaluate() }

def evaluate() {
	def d = delta()
	if (logEnable) log.debug "${app.label}: delta=${d} holding=${state.holding} override=${state.override} expired=${state.expired}"
	if (d == null) {
		// No usable reading: stop managing the fan (fail open)
		if (state.holding) log.warn "${app.label}: humidity reading unavailable; releasing the fan"
		state.holding = false
		return
	}
	if (d <= param("bathFanStopDelta")) {
		// Dry again: finish any hold, clear override/expiry
		if (state.holding && fan.currentValue("switch") == "on") {
			log.info "${app.label}: humidity back within ${param('bathFanStopDelta')}% of reference; fan off"
			fanOff()
		}
		state.holding = false
		state.override = false
		state.expired = false
		unschedule("timeLimit")
		return
	}
	if (d >= param("bathFanStartDelta") && !state.override && !state.expired && fan.currentValue("switch") != "on") {
		log.info "${app.label}: humidity ${d.setScale(1, BigDecimal.ROUND_HALF_UP)}% above reference; fan on"
		startHolding()
		fanOn()
	}
}

def fanHandler(evt) {
	if (state.ignoreUntil && now() < (state.ignoreUntil as Long)) return
	if (evt.value != "off") return
	if (!humid() || state.override || state.expired) {
		state.holding = false
		return
	}
	boolean physical = evt.type == "physical"
	def window = (param("bathFanOverrideSeconds") * 1000) as Long
	if (state.refusedAt && now() - (state.refusedAt as Long) <= window) {
		log.info "${app.label}: second off within ${param('bathFanOverrideSeconds')}s; fan off until the humidity clears"
		state.override = true
		state.holding = false
		state.refusedAt = null
		unschedule("timeLimit")
		return
	}
	log.info "${app.label}: off ${physical ? 'pressed' : 'requested'} but still ${delta().setScale(1, BigDecimal.ROUND_HALF_UP)}% above reference; keeping the fan on"
	state.refusedAt = now()
	if (!state.holding) startHolding()
	fanOn()
	if (physical) blinkLed()
}

private void startHolding() {
	state.holding = true
	state.holdSince = now()
	runIn(((param("bathFanMaxMinutes") * 60) as Integer), "timeLimit", [overwrite: true])
}

def timeLimit() {
	if (!state.holding) return
	log.warn "${app.label}: still humid after ${param('bathFanMaxMinutes')} minutes; turning the fan off and leaving it alone until it's dry"
	state.holding = false
	state.expired = true
	fanOff()
}

private void fanOn()  { state.ignoreUntil = now() + 3000; fan.on() }
private void fanOff() { state.ignoreUntil = now() + 3000; fan.off() }

// ── LED blink ────────────────────────────────────────────────────────────────

private void blinkLed() {
	def dev = ledDevice ?: fan
	if (!dev.hasCommand("setLEDMode")) { if (logEnable) log.debug "${dev.displayName} has no setLEDMode; no blink"; return }
	state.blinkStep = 0
	state.blinkTotal = ((param("bathFanLedBlinks") as Integer) * 2)
	blinkStep()
}

def blinkStep() {
	def dev = ledDevice ?: fan
	int step = (state.blinkStep ?: 0) as Integer
	int total = (state.blinkTotal ?: 6) as Integer
	String mode = step >= total ? (ledNormalMode ?: "Default") : (step % 2 == 0 ? "On" : "Off")
	if (ledChannel) dev.setLEDMode(ledChannel, mode) else dev.setLEDMode(mode)
	if (step >= total) return
	state.blinkStep = step + 1
	runInMillis(700, "blinkStep")
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private String stateText() {
	if (state.override) return "forced off by double press; waiting for humidity to clear"
	if (state.expired)  return "time limit reached; waiting for humidity to clear"
	if (state.holding)  return "holding the fan on since ${new Date(state.holdSince as Long).format('h:mm a', location.timeZone)}"
	return humid() ? "humid, fan not held (not turned off yet)" : "idle"
}

private BigDecimal param(String name) {
	def gv = getGlobalVar(name)
	try { if (gv?.value != null && gv.value.toString() != "") return new BigDecimal(gv.value.toString()) } catch (e) { }
	return new BigDecimal(PARAMS[name][0].toString())
}

private BigDecimal num(v) {
	if (v == null) return null
	try { return new BigDecimal(v.toString()) } catch (e) { return null }
}
