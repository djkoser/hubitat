/**
 * Bathroom Humidity Fans — Hubitat App (one install for every bathroom)
 *
 * Each bathroom's humidity sensor is compared to one reference sensor elsewhere in the house
 * (e.g. the AirGradient indoor monitor). The difference ("delta", in %RH) drives that
 * bathroom's fan. When both sensors report temperature, the reference is first converted to
 * the %RH the same air would read at the bathroom's temperature (Magnus formula): a bathroom
 * 9°F colder than the reference room otherwise reads ~13 points "more humid" with no shower.
 *
 * Per bathroom:
 *   - Auto on:   delta rises to bathFanStartDelta or more and the fan is off -> fan on.
 *   - Fast rise: bathroom humidity climbs bathFanRiseDelta or more within bathFanRiseMinutes
 *                (a shower starting) while delta is above bathFanStopDelta -> fan on, without
 *                waiting for the full start delta. Set the rise to 0 to turn this off.
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
 * If a bathroom's sensor or the reference has no reading (or the reference reports offline),
 * the app neither starts nor holds that fan: it fails open, back to a plain switch.
 *
 * LED blink: Zooz switches (jtp10181 drivers) have no LED-blink command (flash() cycles
 * the load), so the app alternates the LED mode On/Off and then restores the chosen
 * normal mode. For a ZEN30, pick the ZEN30 itself as the LED device and "Relay".
 *
 * Thresholds and timings are shared by all bathrooms (Settings section). Each bathroom's
 * state lives in state."b<n>"; timers are per-bathroom deadlines checked by one schedule.
 * Replaces the one-instance-per-bathroom "Bathroom Humidity Fan" app (Nov 2026).
 */

definition(
	name: "Bathroom Humidity Fans",
	namespace: "djkoser",
	author: "David Koser",
	description: "Runs bathroom fans from humidity relative to a reference sensor, refuses off while humid, blinks the switch LED",
	category: "Convenience",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/bathroom-humidity-fans.groovy",
	singleThreaded: true   // sensors and switches can report at once
)

preferences { page(name: "mainPage") }

@groovy.transform.Field static final int MAX_BATHROOMS = 6

// Shared settings: name: [default, short title, description]
@groovy.transform.Field static final Map PARAMS = [
	bathFanStartDelta:      [10, "Start delta (%RH)", "Turn the fan on when the bathroom is this much above the reference."],
	bathFanStopDelta:       [3,  "Stop delta (%RH)", "The fan may turn off once the bathroom is back within this much of the reference."],
	bathFanRiseDelta:       [5,  "Fast-rise trigger (%RH)", "Turn the fan on early when the bathroom rises this much within the rise window (0 = off)."],
	bathFanRiseMinutes:     [5,  "Rise window (minutes)", "The period over which the fast rise is measured."],
	bathFanMaxMinutes:      [60, "Time limit (minutes)", "After this long the app stops holding or auto-running a fan, and turns it off if it was holding it."],
	bathFanOverrideSeconds: [10, "Override window (seconds)", "A second off within this long of a refused one forces the fan off until it's dry."],
	bathFanLedBlinks:       [3,  "LED blinks", "How many times the switch LED blinks when an off is refused."],
]

private String small(String t) { "<br><small style='color:#666'>${t}</small>" }

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Reference") {
			input "refSensor", "capability.relativeHumidityMeasurement", title: "Reference sensor" + small("Humidity (and temperature) for the rest of the house."), required: true
			input "bathroomCount", "number", title: "Bathrooms" + small("How many bathrooms this app runs (1–${MAX_BATHROOMS})."), range: "1..${MAX_BATHROOMS}", defaultValue: 1, required: true, submitOnChange: true
		}
		(1..count()).each { int i ->
			section(settings["bathName_${i}"] ?: "Bathroom ${i}") {
				input "bathName_${i}", "text", title: "Name", required: true, submitOnChange: true
				input "fan_${i}", "capability.switch", title: "Exhaust fan", required: true
				input "bathSensor_${i}", "capability.relativeHumidityMeasurement", title: "Humidity sensor", required: true
				input "ledDevice_${i}", "capability.switch", title: "LED device" + small("The switch whose LED blinks when an off is refused; blank = the fan switch."), required: false
				input "ledChannel_${i}", "enum", title: "LED channel" + small("ZEN30: Relay for the fan. Blank for single switches."), options: ["", "Relay", "Dimmer"], defaultValue: "", required: false
				input "ledNormalMode_${i}", "enum", title: "LED mode after blinking", options: ["Default", "Reverse", "Off", "On"], defaultValue: "Default"
				if (app.id && settings["fan_${i}"] && settings["bathSensor_${i}"]) paragraph statusText(i)
			}
		}
		section("Settings (all bathrooms)") {
			PARAMS.each { n, d -> input n, "number", title: d[1] + small(d[2]), defaultValue: d[0], required: false }
		}
		section("Logging") {
			input "logEnable", "bool", title: "Debug logging" + small("Turns itself off after 30 minutes."), defaultValue: false
		}
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }
def uninstalled() { }

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	// Drop state for bathrooms that were removed
	((count() + 1)..MAX_BATHROOMS).each { state.remove("b${it}") }
	subscribe(refSensor, "humidity", "refHandler")
	bathrooms().each { int i ->
		subscribe(bathSensor(i), "humidity", "humidityHandler")
		subscribe(fan(i), "switch", "fanHandler")
		evaluate(i)
	}
	scheduleDeadlineCheck()
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ── Bathrooms ────────────────────────────────────────────────────────────────

private int count() { Math.max(1, Math.min(MAX_BATHROOMS, ((settings.bathroomCount ?: 1) as Integer))) }
private List<Integer> bathrooms() { (1..count()).findAll { fan(it) && bathSensor(it) } }
private fan(int i)        { settings["fan_${i}"] }
private bathSensor(int i) { settings["bathSensor_${i}"] }
private String name(int i) { settings["bathName_${i}"] ?: "Bathroom ${i}" }

private Integer bathroomFor(evt, String role) {
	bathrooms().find { int i ->
		def d = role == "fan" ? fan(i) : bathSensor(i)
		d?.id?.toString() == evt.deviceId?.toString()
	}
}

// Per-bathroom state, in state."b<n>":
//   holding    – app owns the fan (auto-started it or refused an off); turns it off when dry
//   holdSince  – when holding began; holdUntil – its time-limit deadline
//   override   – user forced it off; leave the fan alone until dry
//   expired    – time limit reached; leave the fan alone until dry
//   refusedAt  – last refused off (for the double-press override)
//   rh         – recent bathroom readings [[time, %RH], ...] for the rate-of-rise trigger
//   expect     – [value, until]: the switch event our own on()/off() will produce, ignored
//                so the app doesn't react to itself (an opposite event is still handled)
//   blinkStep / blinkTotal – LED blink progress
private Map st(int i) { (state["b${i}"] ?: [:]) as Map }
private void save(int i, Map s) { state["b${i}"] = s }

// ── Humidity ─────────────────────────────────────────────────────────────────

private BigDecimal delta(int i) {
	def b = num(bathSensor(i)?.currentValue("humidity"))
	def r = refHumidity(i)
	if (b == null || r == null) return null
	if (refSensor.hasAttribute("healthStatus") && refSensor.currentValue("healthStatus") == "offline") return null
	return b - r
}

// Reference %RH converted to the bathroom's temperature (same moisture, different air
// temperature), or the raw reference reading when either temperature is unavailable.
private BigDecimal refHumidity(int i) {
	def r = num(refSensor?.currentValue("humidity"))
	if (r == null) return null
	def tb = celsius(bathSensor(i)?.currentValue("temperature"))
	def tr = celsius(refSensor?.currentValue("temperature"))
	if (tb == null || tr == null) return r
	double adj = r.toDouble() * satPressure(tr) / satPressure(tb)
	return new BigDecimal(Math.min(adj, 100d)).setScale(1, BigDecimal.ROUND_HALF_UP)
}

private Double celsius(v) {
	def t = num(v)
	if (t == null) return null
	return location?.temperatureScale == "C" ? t.toDouble() : (t.toDouble() - 32d) * 5d / 9d
}

private double satPressure(double c) { 6.112d * Math.exp(17.62d * c / (243.12d + c)) }

private boolean humid(int i) { def d = delta(i); d != null && d > param("bathFanStopDelta") }

def humidityHandler(evt) { def i = bathroomFor(evt, "sensor"); if (i) evaluate(i) }
def refHandler(evt) { bathrooms().each { evaluate(it) } }

// Bathroom readings over the rise window, plus the last one before it (the value at the
// window's start, since sensors report on change)
private void recordHumidity(int i, Map s) {
	def v = num(bathSensor(i)?.currentValue("humidity"))
	if (v == null) return
	long t = now()
	long cutoff = t - ((param("bathFanRiseMinutes") * 60000) as Long)
	def rh = (s.rh ?: []) + [[t, v]]
	def before = rh.findAll { (it[0] as Long) <= cutoff }
	s.rh = (before ? [before.last()] : []) + rh.findAll { (it[0] as Long) > cutoff }
}

// How far the bathroom has risen over the window: current reading minus the lowest
// reading in it (including the value at its start)
private BigDecimal rise(Map s) {
	def rh = s.rh
	if (!rh || rh.size() < 2) return null
	return num(rh.last()[1]) - rh.collect { num(it[1]) }.min()
}

def evaluate(int i) {
	Map s = st(i)
	recordHumidity(i, s)
	def d = delta(i)
	def fanDev = fan(i)
	if (logEnable) log.debug "${name(i)}: delta=${d} holding=${s.holding} override=${s.override} expired=${s.expired}"
	if (d == null) {
		// No usable reading: stop managing the fan (fail open)
		if (s.holding) log.warn "${name(i)}: humidity reading unavailable; releasing the fan"
		s.holding = false
	} else if (d <= param("bathFanStopDelta")) {
		// Dry again: finish any hold, clear override/expiry
		if (s.holding && fanDev.currentValue("switch") == "on") {
			log.info "${name(i)}: humidity back within ${param('bathFanStopDelta')}% of reference; fan off"
			fanOff(i, s)
		}
		s.holding = false; s.override = false; s.expired = false; s.holdUntil = null
	} else if (!(s.override || s.expired || fanDev.currentValue("switch") == "on")) {
		boolean start = false
		if (d >= param("bathFanStartDelta")) {
			log.info "${name(i)}: humidity ${d.setScale(1, BigDecimal.ROUND_HALF_UP)}% above reference; fan on"
			start = true
		} else {
			def r = rise(s)
			def riseDelta = param("bathFanRiseDelta")
			if (riseDelta > 0 && r != null && r >= riseDelta) {
				log.info "${name(i)}: humidity rose ${r.setScale(1, BigDecimal.ROUND_HALF_UP)}% in ${param('bathFanRiseMinutes')} min " +
						 "(${d.setScale(1, BigDecimal.ROUND_HALF_UP)}% above reference); fan on"
				start = true
			}
		}
		if (start) { startHolding(s); fanOn(i, s) }
	}
	save(i, s)
}

def fanHandler(evt) {
	def i = bathroomFor(evt, "fan")
	if (!i) return
	Map s = st(i)
	def ex = s.expect
	if (ex && evt.value == ex.value && now() < (ex.until as Long)) { s.remove("expect"); save(i, s); return }
	if (evt.value != "off") return
	if (!humid(i) || s.override || s.expired) {
		s.holding = false
		save(i, s)
		return
	}
	boolean physical = evt.type == "physical"
	def window = (param("bathFanOverrideSeconds") * 1000) as Long
	if (s.refusedAt && now() - (s.refusedAt as Long) <= window) {
		log.info "${name(i)}: second off within ${param('bathFanOverrideSeconds')}s; fan off until the humidity clears"
		s.override = true; s.holding = false; s.refusedAt = null; s.holdUntil = null
		save(i, s)
		return
	}
	log.info "${name(i)}: off ${physical ? 'pressed' : 'requested'} but still ${delta(i).setScale(1, BigDecimal.ROUND_HALF_UP)}% above reference; keeping the fan on"
	s.refusedAt = now()
	if (!s.holding) startHolding(s)
	fanOn(i, s)
	save(i, s)
	if (physical) blinkLed(i)
}

// ── Time limit ───────────────────────────────────────────────────────────────

private void startHolding(Map s) {
	s.holding = true
	s.holdSince = now()
	s.holdUntil = now() + ((param("bathFanMaxMinutes") * 60000) as Long)
	scheduleDeadlineCheck(s.holdUntil as Long)
}

// One schedule for every bathroom's deadline: runs at the earliest pending one
private void scheduleDeadlineCheck(Long extra = null) {
	def due = bathrooms().collect { st(it).holdUntil }.findAll { it != null }.collect { it as Long }
	if (extra != null) due << extra
	if (!due) return
	long secs = Math.max(1L, (long) Math.ceil((due.min() - now()) / 1000d))
	runIn(secs, "checkTimeLimits", [overwrite: true])
}

def checkTimeLimits() {
	bathrooms().each { int i ->
		Map s = st(i)
		if (s.holding && s.holdUntil != null && now() >= (s.holdUntil as Long)) {
			log.warn "${name(i)}: still humid after ${param('bathFanMaxMinutes')} minutes; turning the fan off and leaving it alone until it's dry"
			s.holding = false; s.expired = true; s.holdUntil = null
			fanOff(i, s)
			save(i, s)
		}
	}
	scheduleDeadlineCheck()
}

private void fanOn(int i, Map s)  { s.expect = [value: "on",  until: now() + 5000]; fan(i).on() }
private void fanOff(int i, Map s) { s.expect = [value: "off", until: now() + 5000]; fan(i).off() }

// ── LED blink ────────────────────────────────────────────────────────────────

private ledDev(int i) { settings["ledDevice_${i}"] ?: fan(i) }

private void blinkLed(int i) {
	def dev = ledDev(i)
	if (!dev.hasCommand("setLEDMode")) { if (logEnable) log.debug "${dev.displayName} has no setLEDMode; no blink"; return }
	Map s = st(i)
	s.blinkStep = 0
	s.blinkTotal = ((param("bathFanLedBlinks") as Integer) * 2)
	save(i, s)
	blinkStep([i: i])
}

def blinkStep(Map data) {
	int i = data.i as Integer
	Map s = st(i)
	def dev = ledDev(i)
	int step = (s.blinkStep ?: 0) as Integer
	int total = (s.blinkTotal ?: 6) as Integer
	String channel = settings["ledChannel_${i}"]
	String mode = step >= total ? (settings["ledNormalMode_${i}"] ?: "Default") : (step % 2 == 0 ? "On" : "Off")
	if (channel) dev.setLEDMode(channel, mode) else dev.setLEDMode(mode)
	if (step >= total) return
	s.blinkStep = step + 1
	save(i, s)
	runInMillis(700, "blinkStep", [data: [i: i], overwrite: false])
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private String statusText(int i) {
	Map s = st(i)
	def d = delta(i)
	def r = rise(s)
	def fmt1 = { v -> v != null ? v.setScale(1, BigDecimal.ROUND_HALF_UP) + "%" : "—" }
	"<b>Now:</b> ${num(bathSensor(i)?.currentValue('humidity')) ?: '—'}% vs reference ${refHumidity(i) ?: '—'}% at this room's temperature " +
	"(${num(refSensor?.currentValue('humidity')) ?: '—'}% raw)  <b>Delta:</b> ${fmt1(d)}  " +
	"<b>Rise (${param('bathFanRiseMinutes')} min):</b> ${fmt1(r)}<br><b>State:</b> ${stateText(i, s)}"
}

private String stateText(int i, Map s) {
	if (s.override) return "forced off by double press; waiting for humidity to clear"
	if (s.expired)  return "time limit reached; waiting for humidity to clear"
	if (s.holding)  return "holding the fan on since ${new Date(s.holdSince as Long).format('h:mm a', location.timeZone)}"
	return humid(i) ? "humid, fan not held (not turned off yet)" : "idle"
}

private BigDecimal param(String name) {
	def v = settings[name]
	try { if (v != null && v.toString() != "") return new BigDecimal(v.toString()) } catch (e) { }
	return new BigDecimal(PARAMS[name][0].toString())
}

private BigDecimal num(v) {
	if (v == null) return null
	try { return new BigDecimal(v.toString()) } catch (e) { return null }
}
