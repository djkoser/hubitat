/**
 * FanSync Light — Hubitat Child Driver
 *
 * Child of "FanSync Controller". One per light on a FanSync device:
 *   - a fan's light kit: LIGHT (H0B power, H0C brightness)
 *   - the second light on dual-light fans: LIGHT2 (H09 power, H0A brightness)
 *   - a standalone FanSync light (H00 power, H02 brightness)
 * Color temperature (H04) is shared by both lights of a dual-light fan.
 *
 * The controller configures this device from Fanimation's information model
 * (/api:1/info-model) and stores it in data values: powerKey, levelKey, cctKey,
 * cctMin, cctMax, cctPresets. Every brightness/color change also sends power=1,
 * per the model's "before" rules. Color temperature snaps to the model's presets
 * (e.g. 2700/3000/3500/4000/5000 K) when it defines them, otherwise clamps to its range.
 */

metadata {
	definition(name: "FanSync Light", namespace: "fansync", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/fansync-light.groovy") {
		capability "Actuator"
		capability "Light"
		capability "Switch"
		capability "SwitchLevel"
		capability "ChangeLevel"
		capability "ColorTemperature"
		capability "Refresh"

		attribute "lightFeatures", "string"   // e.g. "on/off, dimming, color temperature"
		attribute "onTimer",  "number"        // standalone lights only (H04), raw device value
		attribute "offTimer", "number"        // standalone lights only (H05), raw device value
	}

	preferences {
		input "levelStep", "number", title: "Brightness step for raise/lower (start/stop level change)", defaultValue: 10, range: "1..50"
		input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() { }

def updated() {
	if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
	log.warn "${device.displayName}: debug logging disabled"
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── Commands ──────────────────────────────────────────────────────────────────
// State is only updated when the parent actually sent the command.

def on() {
	if (!send([(powerKey()): 1])) return
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} is on")
}

def off() {
	if (!send([(powerKey()): 0])) return
	sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} is off")
}

def setLevel(def level, def duration = null) {
	def pct = level as Integer
	if (pct <= 0) { off(); return }
	if (!levelKey()) { on(); return }   // on/off-only light
	pct = clamp(pct, 1, 100)
	if (!send([(powerKey()): 1, (levelKey()): pct])) return
	sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${device.displayName} level is ${pct}%")
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} is on")
}

def setColorTemperature(def temp, def level = null, def duration = null) {
	if (!cctKey()) {
		log.warn "${device.displayName}: this light has no color temperature control"
		if (level != null) setLevel(level)
		return
	}
	def k = snapCct(temp as Integer)
	def keys = [(powerKey()): 1, (cctKey()): k]
	if (level != null && levelKey() && (level as Integer) > 0) keys[levelKey()] = clamp(level as Integer, 1, 100)
	if (!send(keys)) return
	sendCct(k)
	if (keys[levelKey()] != null) sendEvent(name: "level", value: keys[levelKey()], unit: "%")
	sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} is on")
}

// Raise/lower in steps once a second until stopLevelChange (the cloud has no ramp command).
def startLevelChange(String direction) {
	if (!levelKey()) return
	state.levelDir = (direction == "down") ? -1 : 1
	levelStepJob()
}

def stopLevelChange() {
	state.remove("levelDir")
	unschedule("levelStepJob")
}

def levelStepJob() {
	def dir = state.levelDir
	if (!dir) return
	def cur = (device.currentValue("level") ?: 50) as Integer
	def next = clamp(cur + dir * ((levelStep ?: 10) as Integer), 1, 100)
	setLevel(next)
	if (next > 1 && next < 100) runIn(1, "levelStepJob") else stopLevelChange()
}

def refresh() {
	parent.componentRefresh(device)
}

// ── Status from Parent ────────────────────────────────────────────────────────

void parseStatus(Map status) {
	if (logEnable) log.debug "${device.displayName} status: ${status}"
	def dn = device.displayName
	def pk = powerKey(), lk = levelKey(), ck = cctKey()
	if (status[pk] != null) {
		def v = (status[pk] as Integer) == 1 ? "on" : "off"
		sendEvent(name: "switch", value: v, descriptionText: "${dn} is ${v}")
	}
	if (lk && status[lk] != null) {
		def pct = status[lk] as Integer
		if (pct > 0) sendEvent(name: "level", value: pct, unit: "%", descriptionText: "${dn} level is ${pct}%")
	}
	if (ck && status[ck] != null) {
		def k = status[ck] as Integer
		if (k >= 2000) sendCct(k)
	}
	if (getDataValue("standalone") == "true") {
		if (status["H04"] != null) sendEvent(name: "onTimer", value: status["H04"] as Integer)
		if (status["H05"] != null) sendEvent(name: "offTimer", value: status["H05"] as Integer)
	}
}

// Called by the controller after it reads the device's model/fields
void configureFeatures() {
	def f = ["on/off"]
	if (levelKey()) f << "dimming"
	if (cctKey())   f << "color temperature ${cctMin()}-${cctMax()}K" + (cctPresets() ? " (${cctPresets().join('/')})" : "")
	sendEvent(name: "lightFeatures", value: f.join(", "))
}

// ── Internal Helpers ──────────────────────────────────────────────────────────

private boolean send(Map keys) { parent.componentSet(device, keys) }

private String powerKey() { getDataValue("powerKey") ?: "H0B" }
private String levelKey() { getDataValue("levelKey") ?: null }
private String cctKey()   { getDataValue("cctKey") ?: null }

private int cctMin() { (getDataValue("cctMin") ?: "2700") as Integer }
private int cctMax() { (getDataValue("cctMax") ?: "5000") as Integer }
private List cctPresets() { (getDataValue("cctPresets") ?: "").split(",").findAll { it }.collect { it as Integer } }

private int snapCct(int k) {
	def p = cctPresets()
	if (p) return p.min { Math.abs(it - k) } as int
	return clamp(k, cctMin(), cctMax())
}

private void sendCct(int k) {
	// Fanimation's preset names: 2700 Warm White, 3000 Soft White, 3500 Bright White, 4000 Cool White, 5000 Daylight
	def name = k < 2850 ? "Warm White" : k < 3250 ? "Soft White" : k < 3750 ? "Bright White" : k < 4500 ? "Cool White" : "Daylight"
	sendEvent(name: "colorTemperature", value: k, unit: "°K", descriptionText: "${device.displayName} color temperature is ${k}K")
	sendEvent(name: "colorName", value: name)
}

private int clamp(int value, int min, int max) { Math.max(min, Math.min(max, value)) }
