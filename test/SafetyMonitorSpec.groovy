import spock.lang.Specification
import spock.lang.Unroll
import support.AppHarness
import support.FakeDevice

class SafetyMonitorSpec extends Specification {
    FakeDevice front = new FakeDevice(id: 287, displayName: "Front Door Lock", states: [lock: "locked"])
    FakeDevice garage = new FakeDevice(id: 284, displayName: "Garage Lock", states: [lock: "locked"])
    FakeDevice frontDoor = new FakeDevice(id: 215, displayName: "Front Door", states: [contact: "closed"])
    FakeDevice leak = new FakeDevice(id: 108, displayName: "Northwest Water Sensor", states: [water: "dry"])
    FakeDevice valve = new FakeDevice(id: 500, displayName: "Main Water Valve", states: [valve: "open"])
    FakeDevice david = new FakeDevice(id: 159, displayName: "David's SMS")
    FakeDevice speaker = new FakeDevice(id: 88, displayName: "Sound System")
    FakeDevice cam = new FakeDevice(id: 340, displayName: "Driveway (SW)", states: [spotlight: "off"])
    FakeDevice cancel = new FakeDevice(id: 179, displayName: "Cancel Active Alerts", states: [switch: "off"])
    AppHarness hub

    Map baseSettings() {
        [locks: [front, garage], doors: [frontDoor], cancelSwitch: cancel, sirenSwitch: null,
         smokeDetectors: null, coDetectors: null, leakSensors: [leak], waterValve: valve,
         notifiers: [david], speakers: [speaker], camerasEnabled: true, cameras: [cam],
         alertDetections: ["person", "vehicle"], logEnable: false]
    }

    def setup() {
        hub = new AppHarness("safety-monitor.groovy", baseSettings(), [intrusionMonitoringEnabled: false, safetyStatus: ""])
    }

    private List texts() { david.calls.findAll { it[0] == "deviceNotification" }.collect { it[1] } }
    private List spoken() { speaker.calls.findAll { it[0] == "speak" }.collect { it[1] } }

    def "arms after all locks stay locked for the arm delay"() {
        when: hub.script.lockHandler(hub.event(front, "lock", "locked"))
        then: hub.scheduled.find { it.method == "armIfAllLocked" }?.delay == 120

        when: hub.script.armIfAllLocked()
        then:
        hub.state.armed
        hub.globals.intrusionMonitoringEnabled == true
        spoken() == ["Intrusion monitoring has been armed."]
    }

    def "does not arm while any lock is unlocked"() {
        given: garage.states.lock = "unlocked"
        when: hub.script.armIfAllLocked()
        then: !hub.state.armed
    }

    def "any unlock disarms and names the lock"() {
        given: hub.script.armIfAllLocked(); speaker.calls.clear()
        when: hub.script.lockHandler(hub.event(front, "lock", "unlocked"))
        then:
        !hub.state.armed
        spoken() == ["Intrusion monitoring has been disarmed by Front Door Lock."]
    }

    def "a door opening while armed texts, announces, repeats and lights up the cameras"() {
        given: hub.script.armIfAllLocked(); speaker.calls.clear()

        when: hub.script.doorOpenHandler(hub.event(frontDoor, "contact", "open"))

        then:
        texts().any { it.startsWith("ALERT: Front Door opened while intrusion monitoring was armed") }
        spoken() == ["Alert. Front Door opened while armed."]
        cam.calls.contains(["setSpotlight", "on"])
        cam.calls.contains(["setSiren", "on"])
        hub.state.alertActive
        hub.isScheduled("repeatAlerts")
        hub.globals.safetyStatus == "ALERT"
    }

    def "a door opening while disarmed does nothing"() {
        when: hub.script.doorOpenHandler(hub.event(frontDoor, "contact", "open"))
        then: texts().isEmpty() && cam.calls.isEmpty()
    }

    def "the cancel switch stops sirens and spotlights and all repeating announcements"() {
        given:
        hub.script.armIfAllLocked()
        hub.script.doorOpenHandler(hub.event(frontDoor, "contact", "open"))
        cam.calls.clear()

        when: hub.script.cancelHandler(hub.event(cancel, "switch", "on"))

        then:
        cam.calls.contains(["setSpotlight", "off"])
        cam.calls.contains(["setSiren", "off"])
        !hub.state.alertActive
        hub.state.activeAlerts == [:]
        !hub.isScheduled("repeatAlerts")
    }

    def "repeats an alert the configured number of times, then stops"() {
        given:
        hub.settings.safetyRepeatCount = 2
        hub.script.leakHandler(hub.event(leak, "water", "wet"))
        speaker.calls.clear()

        when: 3.times { hub.script.repeatAlerts() }
        then: spoken().size() == 2
    }

    def "a leak closes the main valve; drying clears the alert but leaves the valve closed"() {
        when: hub.script.leakHandler(hub.event(leak, "water", "wet"))
        then:
        valve.commandNames() == ["close"]
        texts().any { it.contains("Water leak detected by Northwest Water Sensor. The main water valve is being closed.") }

        when: hub.script.leakHandler(hub.event(leak, "water", "dry"))
        then:
        hub.state.activeAlerts == [:]
        valve.commandNames() == ["close"]
        texts().last().contains("stays closed until you reopen it")
    }

    @Unroll
    def "reminder time '#value' parses to #expected"() {
        expect: hub.script.parseHhMm(value) == expected
        where:
        value   | expected
        "22:00" | [22, 0]
        "7:05"  | [7, 5]
        "23:59" | [23, 59]
        "24:00" | null
        "22:60" | null
        "10pm"  | null
    }

    def "schedules the nightly reminder, or not when set to off or invalid"() {
        when: hub.settings.safetyArmReminderTime = "22:30"; hub.script.scheduleReminder()
        then: hub.scheduled.find { it.method == "armReminder" }?.delay == "0 30 22 ? * *"

        when: hub.settings.safetyArmReminderTime = "off"; hub.script.scheduleReminder()
        then: !hub.isScheduled("armReminder")

        when: hub.settings.safetyArmReminderTime = "25:00"; hub.script.scheduleReminder()
        then:
        !hub.isScheduled("armReminder")
        hub.logsMatching("not a valid 24-hour HH:mm")
    }



    def "device health trusts the driver's healthStatus, and falls back to silence without one"() {
        given:
        def old = new Date(hub.clock - 90L * 24 * 3600 * 1000)          // 90 days ago
        def smokeRelay = new FakeDevice(id: 281, displayName: "Fire Alarm Sensor", states: [smoke: "clear", healthStatus: "online"], lastActivity: old)
        hub.settings.smokeDetectors = [smokeRelay]
        leak.lastActivity = old                                           // no healthStatus attribute
        [front, garage, frontDoor, valve].each { it.lastActivity = new Date(hub.clock) }

        when: hub.script.healthCheck()

        then:
        hub.state.unhealthy.keySet() == ["108"] as Set
        texts().size() == 1
        texts()[0].startsWith("Northwest Water Sensor appears offline (no activity since Aug 26 ")

        when: smokeRelay.states.healthStatus = "offline"; hub.script.healthCheck()
        then: hub.state.unhealthy.containsKey("281")
    }


    def "while armed, a person detection is texted, then throttled per camera"() {
        given: hub.script.armIfAllLocked(); david.calls.clear()
        def porch = new FakeDevice(id: 342, displayName: "Porch (SE)")

        when: hub.script.cameraDetectionHandler(hub.event(cam, "person", "active"))
        then: texts().size() == 1 && texts()[0].startsWith("Person detected at Driveway (SW) while armed (")

        when: "the same camera again within the cooldown"
        hub.advance(60); hub.script.cameraDetectionHandler(hub.event(cam, "vehicle", "active"))
        then: texts().size() == 1

        when: "another camera is texted independently"
        hub.script.cameraDetectionHandler(hub.event(porch, "vehicle", "active"))
        then: texts().last().startsWith("Vehicle detected at Porch (SE)")

        when: "after the cooldown"
        hub.advance(61); hub.script.cameraDetectionHandler(hub.event(cam, "person", "active"))
        then: texts().size() == 3
    }

    def "detections are ignored while disarmed and when they clear"() {
        when: hub.script.cameraDetectionHandler(hub.event(cam, "person", "active"))
        then: texts().isEmpty()
        when: hub.script.armIfAllLocked(); hub.script.cameraDetectionHandler(hub.event(cam, "person", "inactive"))
        then: texts().isEmpty()
    }

    def "subscribes only to the chosen detection types"() {
        when: hub.settings.alertDetections = ["person", "package", "bogus"]; hub.script.initialize()
        then: hub.subscriptions.findAll { it[2] == "cameraDetectionHandler" }.collect { it[1] } as Set == ["person", "package"] as Set
    }

    private Map status() { hub.children.values().first().states }

    def "creates the Safety Status device and keeps intrusion and hazard current"() {
        when: hub.script.installed()
        then:
        hub.children.size() == 1
        hub.children.values().first().displayName == "Safety Status"

        when: hub.script.armIfAllLocked()
        then:
        status().intrusion == "Armed"
        status().hazard == "None"
        status().safetyStatus == "Armed"

        when: hub.script.doorOpenHandler(hub.event(frontDoor, "contact", "open"))
        then: status().intrusion == "Alert"

        when: hub.script.leakHandler(hub.event(leak, "water", "wet"))
        then: status().hazard == "Water leak"

        when: hub.script.cancelHandler(hub.event(cancel, "switch", "on"))
        then:
        status().intrusion == "Armed"
        status().hazard == "None"

        when: hub.script.lockHandler(hub.event(garage, "lock", "unlocked"))
        then: status().intrusion == "Disarmed"
    }

    def "the most serious hazard is shown, and a cleared leak returns it to None"() {
        given: hub.script.installed()
        when: hub.script.leakHandler(hub.event(leak, "water", "wet"))
        then: status().hazard == "Water leak"
        when: hub.script.leakHandler(hub.event(leak, "water", "dry"))
        then: status().hazard == "None"
    }
}
