/**
 * ERV Status — Hubitat Driver (view only)
 *
 * Shows what the ventilation equipment is doing right now: On (ERV + air handler),
 * Recirculate (air handler only) or Off. Created and kept up to date by the
 * ERV Auto Controller app from the ERV and air handler stop relays, whatever mode
 * is selected, plus why: ervCause is a short category (for Google Home) and ervReason the
 * full explanation (for dashboards), plus the worst indoor readings it is reacting to. It has no
 * commands. For Google Home (community app): Modes trait (query only) for ervStatus, ervCause
 * (values @ervCauseValues) and vocIndex / noxIndex (values @indexValues); SensorState
 * for carbonDioxide and pm25.
 */

metadata {
	definition(name: "ERV Status", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/erv-status.groovy") {
		capability "Sensor"
		capability "CarbonDioxideMeasurement"   // carbonDioxide = worst indoor CO2, ppm
		attribute "ervStatus", "enum", ["On", "Recirculate", "Off"]
		attribute "ervCause", "string"          // short reason with its threshold, e.g. "Indoor VOC index ≥ 200"
		attribute "ervCauseValues", "string"    // JSON list of every ervCause value (for Google Home)
		attribute "ervReason", "string"         // full reasoning with readings, limits and units
		attribute "pm25", "number"              // worst indoor PM2.5, µg/m³
		attribute "vocIndex", "string"          // worst indoor VOC index (1–500)
		attribute "noxIndex", "string"          // worst indoor NOx index (1–500)
		attribute "indexValues", "string"       // JSON list "1".."500" (for Google Home)
	}
}

def installed() { }
def updated() { }
