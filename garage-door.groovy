/**
 * Garage Door — Hubitat Driver (child of the Garage Doors app)
 *
 * A standard garage door device (GarageDoorControl + ContactSensor) for a door that has no
 * native integration: the Garage Doors app sets its door/contact state from a tilt sensor and
 * presses a button robot on the wall button for open()/close(). Commands are handed to the
 * parent app; this driver only holds state.
 *
 *   door     open / closed / opening / closing (GarageDoorControl)
 *   contact  closed when the door is closed, open otherwise (ContactSensor)
 */

metadata {
	definition(name: "Garage Door", namespace: "djkoser", author: "David Koser",
			   importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/garage-door.groovy") {
		capability "GarageDoorControl"
		capability "ContactSensor"
		capability "Actuator"
		capability "Sensor"
	}
}

def installed() { }
def updated() { }

def open()  { parent?.doorCommand(device.deviceNetworkId, "open") }
def close() { parent?.doorCommand(device.deviceNetworkId, "close") }
