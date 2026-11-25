import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class BathroomHumidityFanSpec extends Specification {
    FakeDevice fan = new FakeDevice(id: 266, displayName: "Master Shower Fan", states: [switch: "off"])
    FakeDevice bath = new FakeDevice(id: 400, displayName: "Master Bath Sensor", states: [humidity: 45])
    FakeDevice ref = new FakeDevice(id: 337, displayName: "AirGradient Indoor", states: [humidity: 40, healthStatus: "online"])
    AppHarness hub

    def setup() {
        hub = new AppHarness("bathroom-humidity-fan.groovy",
            [fan: fan, bathSensor: bath, refSensor: ref, ledDevice: null, ledChannel: null, ledNormalMode: "Default", logEnable: false])
        hub.state.clear()
    }

    private void humidity(Number v) { hub.script.humidityHandler(hub.event(bath, "humidity", v.toString())) }
    private void fanEvent(String v, String type = "physical") { hub.script.fanHandler(hub.event(fan, "switch", v, type)) }
    private void echoOwnCommand() { fanEvent(fan.states.switch as String, "digital") }

    def "starts the fan when the bathroom is 10% above the reference"() {
        when: humidity(51)
        then:
        fan.commandNames() == ["on"]
        hub.state.holding
        hub.isScheduled("timeLimit")
    }

    def "does not start below the start delta"() {
        when: humidity(49)
        then: fan.calls.isEmpty()
    }

    def "refuses an off while humid, turns the fan back on and blinks the LED for a paddle press"() {
        given: humidity(60); echoOwnCommand(); fan.calls.clear()

        when: fanEvent("off")

        then:
        fan.commandNames().first() == "on"
        fan.calls.any { it[0] == "setLEDMode" && it[1] == "On" }
        hub.isScheduled("blinkStep")
        hub.logsMatching("keeping the fan on")
    }

    def "a remote off is refused too, without blinking"() {
        given: humidity(60); echoOwnCommand(); fan.calls.clear()
        when: fanEvent("off", "digital")
        then:
        fan.commandNames() == ["on"]
        !hub.isScheduled("blinkStep")
    }

    def "a second off within the override window forces the fan off and leaves it alone until dry"() {
        given: humidity(60); echoOwnCommand()
        fanEvent("off"); echoOwnCommand()
        hub.advance(4); fan.calls.clear()

        when: fanEvent("off")

        then:
        fan.calls.isEmpty()
        hub.state.override
        !hub.state.holding

        when: "humidity keeps rising"
        humidity(70)
        then: "the override holds"
        fan.calls.isEmpty()
    }

    def "a quick second press is not swallowed by the app ignoring its own echo"() {
        given: humidity(60); echoOwnCommand()
        fanEvent("off")                 // refused: app sends on(), its echo is still pending
        fan.calls.clear()

        when: "the user presses off again before the app's own on-echo arrives"
        hub.advance(1)
        fanEvent("off")

        then: "it counts as the override, not as the app's echo"
        hub.state.override
    }

    def "turns the fan off once humidity is back within the stop delta"() {
        given: humidity(60); echoOwnCommand(); fan.calls.clear()
        when: humidity(43)
        then:
        fan.commandNames() == ["off"]
        !hub.state.holding
    }

    def "leaves a fan the user turned on alone when it gets dry"() {
        given: fan.states.switch = "on"
        humidity(60)
        fan.calls.clear()
        when: humidity(42)
        then: fan.calls.isEmpty()
    }

    def "time limit turns the fan off and stops managing it until dry"() {
        given: humidity(60); echoOwnCommand(); fan.calls.clear()
        when: hub.script.timeLimit()
        then:
        fan.commandNames() == ["off"]
        hub.state.expired
        when: echoOwnCommand(); humidity(65)
        then: "no restart while expired"
        fan.commandNames() == ["off"]
    }

    def "fails open when the reference is offline"() {
        given: ref.states.healthStatus = "offline"
        when: humidity(80)
        then: fan.calls.isEmpty()
        when: fan.states.switch = "on"; fanEvent("off")
        then: "an off is allowed"
        fan.calls.isEmpty()
    }

    def "settings override the defaults"() {
        given: hub.settings.bathFanStartDelta = 20
        when: humidity(55)
        then: fan.calls.isEmpty()
        when: humidity(61)
        then: fan.commandNames() == ["on"]
    }

    def "blink restores the configured LED mode on the channel device"() {
        given:
        def parent = new FakeDevice(id: 256, displayName: "ZEN30")
        hub.settings.ledDevice = parent
        hub.settings.ledChannel = "Relay"
        hub.state.blinkStep = 6; hub.state.blinkTotal = 6
        when: hub.script.blinkStep()
        then: parent.calls == [["setLEDMode", "Relay", "Default"]]
    }

    def "a fast rise starts the fan before the full start delta"() {
        given: humidity(42)                      // 2% above reference
        when: hub.advance(120); humidity(45); hub.advance(60); humidity(47)   // +5 in 3 min, 7% above
        then:
        fan.commandNames() == ["on"]
        hub.state.holding
        hub.logsMatching("rose 5")
    }

    def "a slow rise does not trigger it"() {
        given: humidity(42)
        when: [43, 44, 45, 46, 47].each { hub.advance(180); humidity(it) }
        then: fan.calls.isEmpty()
    }

    def "a fast rise while still within the stop delta does not start the fan"() {
        given: ref.states.humidity = 50; humidity(40)
        when: hub.advance(60); humidity(46)      // +6, but still below the reference
        then: fan.calls.isEmpty()
    }

    def "a rise setting of 0 turns the trigger off"() {
        given: hub.settings.bathFanRiseDelta = 0; humidity(42)
        when: hub.advance(60); humidity(48)
        then: fan.calls.isEmpty()
    }

    def "a fan started by a fast rise turns off when dry"() {
        given: humidity(42); hub.advance(60); humidity(48); echoOwnCommand(); fan.calls.clear()
        when: hub.advance(600); humidity(43)
        then: fan.commandNames() == ["off"]
    }
}
