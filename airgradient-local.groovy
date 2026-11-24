/**
 * AirGradient Local — Hubitat Driver
 *
 * Polls an AirGradient ONE (indoor) or Open Air (outdoor) monitor over its
 * local HTTP API (firmware 3.0.10+). Reports CORRECTED values:
 *   - PM2.5: pm02Compensated (falls back to pm02 on old firmware)
 *   - Temperature / humidity: atmpCompensated / rhumCompensated
 *
 * pm02Compensated only differs from raw if a correction algorithm is set on
 * the monitor (AirGradient's default is "none"). With "Apply recommended
 * corrections" on, the driver sets EPA 2021 for PM2.5 (and AirGradient's
 * standard temp/humidity correction on Open Air units) whenever it finds
 * no PM2.5 correction configured. The active algorithm is shown in the
 * pm25Correction attribute.
 *
 * API: https://github.com/airgradienthq/arduino/blob/master/docs/local-server.md
 */

import groovy.json.JsonOutput

metadata {
	definition(name: "AirGradient Local", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/airgradient-local.groovy") {
		capability "Sensor"
		capability "Refresh"
		capability "Initialize"
		capability "TemperatureMeasurement"
		capability "RelativeHumidityMeasurement"
		capability "CarbonDioxideMeasurement"
		capability "AirQuality"   // airQualityIndex = US EPA AQI computed from corrected PM2.5

		attribute "pm25",           "number"   // corrected PM2.5 µg/m³
		attribute "pm25Raw",        "number"   // uncorrected PM2.5 µg/m³
		attribute "pm01",           "number"
		attribute "pm10",           "number"
		attribute "pm003Count",     "number"
		attribute "vocIndex",       "number"   // Sensirion VOC index (100 = baseline)
		attribute "noxIndex",       "number"   // Sensirion NOx index (1 = baseline)
		attribute "rssi",           "number"
		attribute "healthStatus",   "enum", ["online", "offline", "unknown"]
		attribute "lastUpdate",     "string"
		attribute "pm25Correction", "string"
		attribute "model",          "string"
		attribute "firmware",       "string"
		attribute "serial",         "string"

		command "applyRecommendedCorrections"
	}

	preferences {
		input "ip", "text", title: "IP address of the monitor", description: "Give it a DHCP reservation", required: true
		input "pollSeconds", "number", title: "Poll interval (seconds)", defaultValue: 60, range: "15..3600"
		input "offlineAfter", "number", title: "Mark offline after this many failed polls", defaultValue: 3, range: "1..60"
		input "autoCorrect", "bool", title: "Apply recommended corrections when none are set", defaultValue: true
		input "logEnable", "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: false
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
	sendEvent(name: "healthStatus", value: "unknown")
	initialize()
}

def updated() {
	if (logEnable) runIn(1800, "logsOff")
	initialize()
}

def initialize() {
	unschedule("poll")
	state.failures = 0
	if (!ip) {
		log.warn "${device.displayName}: set the IP address in Preferences"
		return
	}
	fetchConfig()
	runIn(2, "poll")
}

def logsOff() {
	log.warn "${device.displayName}: debug logging disabled"
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
	if (!ip) { log.warn "${device.displayName}: no IP address set"; return }
	fetchConfig()
	poll()
}

// ── Polling ──────────────────────────────────────────────────────────────────

def poll() {
	if (!ip) return
	asynchttpGet("pollResponse", [uri: "http://${ip}", path: "/measures/current",
								  contentType: "application/json", timeout: 10])
	runIn(Math.max((pollSeconds ?: 60) as Integer, 15), "poll", [overwrite: true])
}

def pollResponse(resp, data) {
	if (resp.hasError() || resp.status != 200) {
		state.failures = ((state.failures ?: 0) as Integer) + 1
		if (logEnable) log.debug "${device.displayName}: poll failed (${resp.status}) ${resp.hasError() ? resp.getErrorMessage() : ''}"
		if (state.failures >= ((offlineAfter ?: 3) as Integer) && device.currentValue("healthStatus") != "offline") {
			log.warn "${device.displayName}: offline after ${state.failures} failed polls"
			sendEvent(name: "healthStatus", value: "offline")
		}
		return
	}
	def j
	try { j = resp.json } catch (e) { log.warn "${device.displayName}: unparseable response"; return }
	if (!(j instanceof Map)) return
	if (logEnable) log.debug "${device.displayName}: ${j}"
	state.failures = 0
	if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online")

	// PM2.5 — corrected first
	def pmRaw  = num(j.pm02)
	def pmCorr = j.containsKey("pm02Compensated") ? num(j.pm02Compensated) : null
	def pm25   = pmCorr != null ? pmCorr : pmRaw
	if (pm25 != null) {
		send("pm25", round1(pm25), "µg/m³")
		send("airQualityIndex", aqi(pm25), "AQI")
	}
	if (pmRaw != null) send("pm25Raw", round1(pmRaw), "µg/m³")
	if (num(j.pm01) != null) send("pm01", round1(num(j.pm01)), "µg/m³")
	if (num(j.pm10) != null) send("pm10", round1(num(j.pm10)), "µg/m³")
	if (num(j.pm003Count) != null) send("pm003Count", num(j.pm003Count) as Integer, "/dL")

	// CO2 (ONE only); negative = no sensor / not ready
	def co2 = num(j.rco2)
	if (co2 != null && co2 >= 0) send("carbonDioxide", co2 as Integer, "ppm")

	// Sensirion indices; absent or negative = no sensor / still learning
	def voc = num(j.tvocIndex)
	if (voc != null && voc >= 0) send("vocIndex", voc as Integer, "")
	def nox = num(j.noxIndex)
	if (nox != null && nox >= 0) send("noxIndex", nox as Integer, "")

	// Temperature / humidity — corrected first
	def t = num(j.atmpCompensated) != null ? num(j.atmpCompensated) : num(j.atmp)
	if (t != null && t > -40) {
		def scale = location.temperatureScale ?: "F"
		def tv = scale == "F" ? round1(t * 9 / 5 + 32) : round1(t)
		send("temperature", tv, "°${scale}")
	}
	def h = num(j.rhumCompensated) != null ? num(j.rhumCompensated) : num(j.rhum)
	if (h != null && h >= 0) send("humidity", round1(h), "%")

	if (num(j.wifi) != null) send("rssi", num(j.wifi) as Integer, "dBm")
	if (j.model)    send("model", j.model as String, null)
	if (j.firmware) send("firmware", j.firmware as String, null)
	if (j.serialno) send("serial", j.serialno as String, null)

	// Changes every poll on purpose: consumers use it as a freshness heartbeat
	sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// ── Configuration / corrections ──────────────────────────────────────────────

def fetchConfig() {
	if (!ip) return
	asynchttpGet("configResponse", [uri: "http://${ip}", path: "/config", contentType: "application/json", timeout: 10])
}

def configResponse(resp, data) {
	if (resp.hasError() || resp.status != 200) {
		if (logEnable) log.debug "${device.displayName}: config fetch failed"
		return
	}
	def c
	try { c = resp.json } catch (e) { return }
	if (!(c instanceof Map)) return
	state.model = c.model
	state.configurationControl = c.configurationControl
	def algo = c.corrections?.pm02?.correctionAlgorithm ?: "none"
	sendEvent(name: "pm25Correction", value: algo)
	if (algo == "none") {
		if (autoCorrect != false) {
			log.info "${device.displayName}: no PM2.5 correction set on the monitor; applying recommended corrections"
			applyRecommendedCorrections()
		} else {
			log.warn "${device.displayName}: monitor has no PM2.5 correction; pm25 equals raw"
		}
	}
}

def applyRecommendedCorrections() {
	if (!ip) return
	if (state.configurationControl == "cloud") {
		log.warn "${device.displayName}: monitor only accepts cloud configuration; set corrections in the AirGradient dashboard"
		return
	}
	def corr = [pm02: [correctionAlgorithm: "epa_2021", slr: [:]]]
	if ((state.model ?: "").toString().startsWith("O-")) {   // Open Air outdoor monitor
		corr.atmp = [correctionAlgorithm: "ag_pms5003t_2024", slr: [:]]
		corr.rhum = [correctionAlgorithm: "ag_pms5003t_2024", slr: [:]]
	}
	asynchttpPut("correctionResponse", [uri: "http://${ip}", path: "/config",
										requestContentType: "application/json", contentType: "application/json",
										body: JsonOutput.toJson([corrections: corr]), timeout: 10])
}

def correctionResponse(resp, data) {
	if (resp.hasError() || !(resp.status in [200, 204])) {
		log.warn "${device.displayName}: setting corrections failed (${resp.status})"
		return
	}
	log.info "${device.displayName}: corrections applied"
	runIn(5, "fetchConfig")
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private void send(String name, value, String unit) {
	def m = [name: name, value: value]
	if (unit) m.unit = unit
	sendEvent(m)
}

private num(v) {
	if (v == null) return null
	try { return new BigDecimal(v.toString()) } catch (e) { return null }
}

private round1(v) { (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP) }

// US EPA AQI for PM2.5 (2024 breakpoints)
private int aqi(pm) {
	def c = ((pm as BigDecimal).setScale(1, BigDecimal.ROUND_DOWN)) as double
	def bp = [[0.0, 9.0, 0, 50], [9.1, 35.4, 51, 100], [35.5, 55.4, 101, 150],
			  [55.5, 125.4, 151, 200], [125.5, 225.4, 201, 300], [225.5, 325.4, 301, 500]]
	for (b in bp) {
		if (c <= b[1]) return Math.round((b[3] - b[2]) / (b[1] - b[0]) * (c - b[0]) + b[2]) as int
	}
	return 500
}
