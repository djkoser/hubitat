/**
 * ERV Status — Hubitat Driver (view only)
 *
 * Shows what the ventilation equipment is doing right now: On (ERV + air handler),
 * Recirculate (air handler only) or Off. Created and kept up to date by the
 * ERV Auto Controller app from the ERV and air handler stop relays, whatever mode
 * is selected. It has no commands; for Google Home, expose ervStatus through the
 * community app's Modes trait with "query only".
 */

metadata {
	definition(name: "ERV Status", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/erv-status.groovy") {
		capability "Sensor"
		attribute "ervStatus", "enum", ["On", "Recirculate", "Off"]
		attribute "ervRunning", "enum", ["true", "false"]
		attribute "airHandlerRunning", "enum", ["true", "false"]
	}
}

def installed() { }
def updated() { }
