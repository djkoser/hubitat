/**
 * FanSync Light — Hubitat Child Driver
 *
 * Child of "FanSync Controller". One per FanSync device that has a light:
 *   - the light kit on a ceiling fan (keys H0B power, H0C brightness, H04 color temp)
 *   - a standalone FanSync light (keys H00 power, H02 brightness)
 *
 * The controller reads each device's model and supported fields from the cloud
 * and stores the key map in this device's data values (powerKey, levelKey, cctKey),
 * so the same driver handles on/off-only, dimmable and color-temperature lights.
 *
 * Key behavior follows Fanimation's information model (the device data model their
 * cloud serves at /api:1/info-model): brightness and color temperature are 1-100 and
 * 3000-5000 K, and each carries a "before" rule that the light must be switched on.
 * The app offers three color temperatures (Warm 3000, Natural 4000, Cool 5000), so
 * requested values snap to the nearest of those.
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

@groovy.transform.Field static final List CCT_PRESETS = [3000, 4000, 5000]

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
	if (cctKey())   f << "color temperature"
	sendEvent(name: "lightFeatures", value: f.join(", "))
}

// ── Internal Helpers ──────────────────────────────────────────────────────────

private boolean send(Map keys) { parent.componentSet(device, keys) }

private String powerKey() { getDataValue("powerKey") ?: "H0B" }
private String levelKey() { getDataValue("levelKey") ?: null }
private String cctKey()   { getDataValue("cctKey") ?: null }

private int snapCct(int k) { CCT_PRESETS.min { Math.abs(it - k) } as int }

private void sendCct(int k) {
	def name = k <= 3500 ? "Warm White" : k <= 4500 ? "Neutral White" : "Cool White"
	sendEvent(name: "colorTemperature", value: k, unit: "°K", descriptionText: "${device.displayName} color temperature is ${k}K")
	sendEvent(name: "colorName", value: name)
}

private int clamp(int value, int min, int max) { Math.max(min, Math.min(max, value)) }
