/**
 * Garage Doors — Hubitat App
 *
 * Turns a tilt sensor plus a button robot (pressing the opener's wall button) into a standard
 * garage door device, without MyQ. One install handles every door; each door gets a child
 * "Garage Door" device (driver garage-door.groovy) with open/close commands and
 * open/closed/opening/closing status.
 *
 * Status follows the tilt sensor, so the door stays right when it is moved by MyQ, a remote or
 * the wall button:
 *   - tilt opens  -> open        - tilt closes -> closed
 *
 * Commands press the button only when the door is in the other state:
 *   - open():  closed -> press, "opening"; it becomes "open" once the tilt sensor reports open
 *              and travelSeconds have passed (tilt sensors trip early in the travel).
 *   - close(): open   -> press, "closing"; it becomes "closed" when the tilt sensor reports closed.
 *   If the tilt sensor hasn't confirmed the move after travelSeconds, the door shows what the
 *   sensor says and a text goes out (e.g. the door reversed on an obstruction, or the press
 *   didn't register). Commands while the door is moving are ignored: a press mid-travel stops
 *   or reverses most openers.
 *
 * Wall-button presses close the door without the warning lights/beeps an opener gives for a
 * remote (MyQ) close. Close it remotely only when you can see the door is clear.
 */

definition(
	name: "Garage Doors",
	namespace: "djkoser",
	author: "David Koser",
	description: "Garage door devices from a tilt sensor and a button robot, without MyQ",
	category: "Convenience",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/garage-doors.groovy",
	singleThreaded: true
)

preferences { page(name: "mainPage") }

@groovy.transform.Field static final int MAX_DOORS = 4

private String small(String t) { "<br><small style='color:#666'>${t}</small>" }

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Doors") {
			input "doorCount", "number", title: "Doors" + small("How many garage doors this app runs (1–${MAX_DOORS})."), range: "1..${MAX_DOORS}", defaultValue: 1, required: true, submitOnChange: true
		}
		(1..count()).each { int i ->
			section(settings["doorName_${i}"] ?: "Door ${i}") {
				input "doorName_${i}", "text", title: "Name" + small("Also the name of the door device it creates."), required: true, submitOnChange: true
				input "tilt_${i}", "capability.contactSensor", title: "Tilt sensor" + small("Reports closed when the door is closed."), required: true
				input "button_${i}", "capability.momentary", title: "Button robot" + small("Pushes the opener's wall button."), required: true
				input "travel_${i}", "number", title: "Travel time (seconds)" + small("How long the door takes to open or close fully."), range: "5..120", defaultValue: 20, required: true
				if (app.id && child(i)) paragraph "<b>Now:</b> ${child(i).currentValue('door') ?: '—'}"
			}
		}
		section("Texts") {
			input "notifiers", "capability.notification", title: "Text" + small("Who is told when a door didn't finish opening or closing."), multiple: true, required: false, submitOnChange: true
		}
		section("Logging") {
			input "logEnable", "bool", title: "Debug logging" + small("Turns itself off after 30 minutes."), defaultValue: false
		}
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }
def uninstalled() { getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) } }

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	((count() + 1)..MAX_DOORS).each { int i ->
		state.remove("d${i}".toString())
		if (getChildDevice(dni(i))) deleteChildDevice(dni(i))
	}
	doors().each { int i ->
		ensureChild(i)
		subscribe(settings["tilt_${i}"], "contact", "tiltHandler")
		Map s = st(i); s.remove("moving"); save(i, s)   // a restart mid-move just resyncs to the sensor
		sync(i)
	}
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ── Doors ────────────────────────────────────────────────────────────────────

private int count() { Math.max(1, Math.min(MAX_DOORS, ((settings.doorCount ?: 1) as Integer))) }
private List<Integer> doors() { (1..count()).findAll { settings["doorName_${it}"] && settings["tilt_${it}"] && settings["button_${it}"] } }
private String dni(int i) { "garage-door-${app.id}-${i}".toString() }
private child(int i) { getChildDevice(dni(i)) }
private String dname(int i) { settings["doorName_${i}"].toString().trim() }
private int travel(int i) { ((settings["travel_${i}"] ?: 20) as Integer) }
private String tiltValue(int i) { settings["tilt_${i}"]?.currentValue("contact")?.toString() }
private Map st(int i) { (state["d${i}".toString()] ?: [:]) as Map }
private void save(int i, Map s) { state["d${i}".toString()] = s }

private void ensureChild(int i) {
	def c = child(i)
	if (!c) {
		try {
			c = addChildDevice("djkoser", "Garage Door", dni(i), [label: dname(i), isComponent: false])
			log.info "Garage Doors: created ${dname(i)}"
		} catch (e) {
			log.warn "Garage Doors: couldn't create ${dname(i)} (is the Garage Door driver installed?): ${e.message}"
		}
	} else if (c.label != dname(i)) c.setLabel(dname(i))
}

private Integer doorFor(evt) {
	doors().find { settings["tilt_${it}"]?.id?.toString() == evt.deviceId?.toString() }
}

// ── Status ───────────────────────────────────────────────────────────────────

def tiltHandler(evt) {
	def i = doorFor(evt)
	if (!i) return
	Map s = st(i)
	if (logEnable) log.debug "${dname(i)}: tilt ${evt.value} (moving: ${s.moving ?: 'no'})"
	if (s.moving == "opening" && evt.value == "open") return   // wait out the travel time
	if (s.moving) { s.remove("moving"); save(i, s) }            // closing finished, or the door went the other way
	sync(i)
}

// Door state from the tilt sensor (when nothing is in progress)
private void sync(int i) {
	String v = tiltValue(i)
	setDoor(i, v == "closed" ? "closed" : (v == "open" ? "open" : "unknown"))
}

private void setDoor(int i, String door) {
	def c = child(i)
	if (!c) return
	if (c.currentValue("door") != door) c.sendEvent(name: "door", value: door, descriptionText: "${dname(i)} is ${door}")
	String contact = door == "closed" ? "closed" : "open"
	if (c.currentValue("contact") != contact) c.sendEvent(name: "contact", value: contact)
}

// ── Commands ─────────────────────────────────────────────────────────────────

// Called by the child device's open()/close()
def doorCommand(String childDni, String cmd) {
	Integer i = doors().find { dni(it) == childDni }
	if (!i) return
	Map s = st(i)
	if (s.moving) { log.info "${dname(i)}: ${cmd} ignored, the door is already ${s.moving}"; return }
	String want = cmd == "open" ? "open" : "closed"
	if (tiltValue(i) == want) { log.info "${dname(i)}: already ${want}"; sync(i); return }
	log.info "${dname(i)}: ${cmd == 'open' ? 'opening' : 'closing'} (pressing ${settings["button_${i}"].displayName})"
	s.moving = cmd == "open" ? "opening" : "closing"
	s.token = now()
	save(i, s)
	setDoor(i, s.moving as String)
	settings["button_${i}"].push()
	runIn(travel(i), "travelDone", [data: [i: i, token: s.token], overwrite: false])
}

def travelDone(Map data) {
	int i = data.i as Integer
	Map s = st(i)
	if (!s.moving || s.token != data.token) return   // finished already, or a newer command
	String moving = s.moving
	s.remove("moving"); save(i, s)
	String v = tiltValue(i)
	boolean ok = (moving == "opening" && v == "open") || (moving == "closing" && v == "closed")
	sync(i)
	if (!ok) {
		String msg = "${dname(i)} didn't finish ${moving} after ${travel(i)} seconds; it reads ${v ?: 'unknown'}."
		log.warn msg
		notifiers*.deviceNotification(msg)
	}
}
