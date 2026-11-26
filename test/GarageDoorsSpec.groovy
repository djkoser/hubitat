import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class GarageDoorsSpec extends Specification {
    FakeDevice tilt = new FakeDevice(id: 370, displayName: "Front Garage Door Tilt Sensor", states: [contact: "closed"])
    FakeDevice bot = new FakeDevice(id: 367, displayName: "Front Garage Door Button Robot", states: [switch: "off"])
    FakeDevice david = new FakeDevice(id: 159, displayName: "David's SMS")
    AppHarness hub

    def setup() {
        hub = new AppHarness("garage-doors.groovy", [doorCount: 1, doorName_1: "Front Garage Door", tilt_1: tilt, button_1: bot,
                                                     travel_1: 20, notifiers: [david], logEnable: false])
        hub.script.installed()
    }

    private FakeDevice door() { hub.children.values().first() }
    private String dni() { hub.children.keySet().first() }
    private void tiltTo(String v) { hub.script.tiltHandler(hub.event(tilt, "contact", v)) }
    private void finishTravel() {
        def job = hub.scheduled.findAll { it.method == "travelDone" }.last()
        hub.advance(20); hub.script.travelDone(job.options.data)
    }
    private List texts() { david.calls.findAll { it[0] == "deviceNotification" }.collect { it[1] } }

    def "install creates the door device and reads the sensor"() {
        expect:
        hub.children.size() == 1
        door().displayName == "Front Garage Door"
        door().states.door == "closed"
        door().states.contact == "closed"
    }

    def "open presses the button once and shows opening until the travel time has passed"() {
        when: hub.script.doorCommand(dni(), "open")
        then:
        bot.commandNames() == ["push"]
        door().states.door == "opening"
        door().states.contact == "open"

        when: "the tilt sensor trips early in the travel"
        tiltTo("open")
        then: door().states.door == "opening"

        when: finishTravel()
        then:
        door().states.door == "open"
        texts().isEmpty()
    }

    def "close shows closing until the tilt sensor reports closed"() {
        given: tilt.states.contact = "open"; hub.script.updated(); bot.calls.clear()
        when: hub.script.doorCommand(dni(), "close")
        then:
        bot.commandNames() == ["push"]
        door().states.door == "closing"

        when: tiltTo("closed")
        then: door().states.door == "closed"

        when: "the travel timer fires afterwards"
        finishTravel()
        then:
        door().states.door == "closed"
        texts().isEmpty()
    }

    def "a door that doesn't close in time shows open and texts (e.g. it reversed on an obstruction)"() {
        given: tilt.states.contact = "open"; hub.script.updated()
        hub.script.doorCommand(dni(), "close")
        when: finishTravel()
        then:
        door().states.door == "open"
        texts() == ["Front Garage Door didn't finish closing after 20 seconds; it reads open."]
    }

    def "a press that doesn't register leaves the door closed and texts"() {
        given: hub.script.doorCommand(dni(), "open")
        when: finishTravel()
        then:
        door().states.door == "closed"
        texts()[0].contains("didn't finish opening")
    }

    def "commands that match the door's state don't press the button"() {
        when: hub.script.doorCommand(dni(), "close")
        then:
        bot.calls.isEmpty()
        door().states.door == "closed"
    }

    def "commands while the door is moving are ignored"() {
        given: hub.script.doorCommand(dni(), "open"); bot.calls.clear()
        when: hub.script.doorCommand(dni(), "close")
        then:
        bot.calls.isEmpty()
        door().states.door == "opening"
    }

    def "moving the door with MyQ or the wall button updates the status"() {
        when: tiltTo("open")
        then: door().states.door == "open"
        when: tiltTo("closed")
        then: door().states.door == "closed"
        and: bot.calls.isEmpty()
    }

    def "an old travel timer doesn't override a newer command"() {
        given:
        hub.script.doorCommand(dni(), "open")
        def oldJob = hub.scheduled.findAll { it.method == "travelDone" }.last()
        tiltTo("open"); finishTravel()                 // opened fine
        hub.script.doorCommand(dni(), "close")         // new command, new token
        when: hub.script.travelDone(oldJob.options.data)
        then: door().states.door == "closing"
    }

    def "events from other sensors are ignored"() {
        given: def other = new FakeDevice(id: 213, displayName: "Garage Door", states: [contact: "open"])
        when: hub.script.tiltHandler(hub.event(other, "contact", "open"))
        then: door().states.door == "closed"
    }
}
