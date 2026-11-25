/**
 * ERV Status — Hubitat Driver (view only)
 *
 * Shows what the ventilation equipment is doing right now: On (ERV + air handler),
 * Recirculate (air handler only) or Off. Created and kept up to date by the
 * ERV Auto Controller app from the ERV and air handler stop relays, whatever mode
 * is selected, plus why: ervCause is a short category (for Google Home) and ervReason the
 * full explanation (for dashboards). It has no commands; for Google Home, expose ervStatus
 * and ervCause through the community app's query-only Modes trait.
 */

metadata {
	definition(name: "ERV Status", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/erv-status.groovy") {
		capability "Sensor"
		attribute "ervStatus", "enum", ["On", "Recirculate", "Off"]
		attribute "ervRunning", "enum", ["true", "false"]
		attribute "airHandlerRunning", "enum", ["true", "false"]
		attribute "ervCause", "enum", ["Air good", "Indoor CO2 high", "Indoor VOC high", "Indoor NOx high", "Indoor PM2.5 high",
		                               "CO2 critical", "Outdoor VOC high", "Outdoor NOx high", "Outdoor VOC and NOx high",
		                               "Filtering particles", "Minimum run", "Minimum off", "Monitors offline", "Manual"]
		attribute "ervReason", "string"
	}
}

def installed() { }
def updated() { }
