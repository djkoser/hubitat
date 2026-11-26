/**
 * Safety Status — Hubitat Driver (view only)
 *
 * Shows the Safety Monitor's state, led by intrusion monitoring: Armed, Disarmed or Alert
 * (a door opened while armed and the alert hasn't been cancelled). Created and kept up to
 * date by the Safety Monitor app; it has no commands, so nothing here can arm or disarm.
 *
 *   intrusion      Armed / Disarmed / Alert
 *   hazard         None / Water leak / Smoke / Carbon monoxide (the most serious active one)
 *   safetyStatus   one-line summary for dashboards, e.g. "Armed (1 device offline)"
 *
 * For Google Home (community app): Modes trait (query only) with intrusion as the main mode
 * (values Armed,Disarmed,Alert), plus hazard as a second mode (values None,Water leak,Smoke,Carbon monoxide).
 */

metadata {
	definition(name: "Safety Status", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/safety-status.groovy") {
		capability "Sensor"
		attribute "intrusion", "enum", ["Armed", "Disarmed", "Alert"]
		attribute "hazard", "enum", ["None", "Water leak", "Smoke", "Carbon monoxide"]
		attribute "safetyStatus", "string"
	}
}

def installed() { }
def updated() { device.deleteCurrentState("devicesOffline") }   // retired attribute
