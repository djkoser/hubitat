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
         nvrIpAddress: null, reolinkUsername: null, reolinkPassword: null, cameraChannels: null, logEnable: false]
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

    def "camera channels come from the comma-separated setting"() {
        when: hub.settings.cameraChannels = "0, 1,x, 4"
        then: hub.script.channels() == [0, 1, 4]
        when: hub.settings.camerasEnabled = false
        then: hub.script.channels() == []
    }

    def "arming turns camera email alerts on with one batched NVR login"() {
        given:
        hub.settings.nvrIpAddress = "192.168.86.20"
        hub.settings.reolinkUsername = "admin"
        hub.settings.reolinkPassword = "secret"
        hub.settings.cameraChannels = "0,1"

        when: hub.script.armIfAllLocked()

        then:
        hub.http.size() == 1
        hub.http[0].params.uri == "https://192.168.86.20/cgi-bin/api.cgi?cmd=Login"
        !hub.http[0].params.uri.contains("secret")          // credentials go in the body, not the URL
        hub.http[0].data.body.contains('"cmd":"SetEmailV20"')
    }

    def "carries timings, NVR login and camera channels over from the old hub variables"() {
        given:
        def s = baseSettings() + [cameraChannelVars: "drivewayCameraChannel,southCameraChannel",
                                  safetyArmDelayMinutes: null, safetyArmReminderTime: null]
        def h = new AppHarness("safety-monitor.groovy", s,
            [safetyArmDelayMinutes: "5", safetyArmReminderTime: "21:30", nvrIpAddress: "192.168.86.20",
             reolinkUsername: "admin", reolinkPassword: "secret", drivewayCameraChannel: 0, southCameraChannel: 1])

        when: h.script.migrateFromHubVariables()

        then:
        h.settings.safetyArmDelayMinutes == 5
        h.settings.safetyArmReminderTime == "21:30"
        h.settings.nvrIpAddress == "192.168.86.20"
        h.settings.reolinkPassword == "secret"
        h.settings.cameraChannels == "0,1"
        !h.settings.containsKey("cameraChannelVars")
        !h.logs.any { it.contains("secret") }             // never logs the password
    }
}
