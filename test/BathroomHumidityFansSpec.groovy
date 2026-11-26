import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class BathroomHumidityFansSpec extends Specification {
    FakeDevice fan = new FakeDevice(id: 266, displayName: "Master Shower Fan", states: [switch: "off"])
    FakeDevice bath = new FakeDevice(id: 400, displayName: "Master Bath Sensor", states: [humidity: 45])
    FakeDevice ref = new FakeDevice(id: 337, displayName: "AirGradient Indoor", states: [humidity: 40, healthStatus: "online"])
    FakeDevice guestFan = new FakeDevice(id: 258, displayName: "Guest Bathroom Fan", states: [switch: "off"])
    FakeDevice guestBath = new FakeDevice(id: 360, displayName: "Guest Bath Sensor", states: [humidity: 41])
    AppHarness hub

    private Map st() { hub.state.b1 ?: [:] }
    private Map guest() { hub.state.b2 ?: [:] }
    private void expireTimeLimit() { hub.advance(3601); hub.script.checkTimeLimits() }

    def setup() {
        hub = new AppHarness("bathroom-humidity-fans.groovy",
            [refSensor: ref, bathroomCount: 2, logEnable: false,
             bathName_1: "Master Bathroom", fan_1: fan, bathSensor_1: bath, ledDevice_1: null, ledChannel_1: null, ledNormalMode_1: "Default",
             bathName_2: "Guest Bathroom", fan_2: guestFan, bathSensor_2: guestBath, ledDevice_2: null, ledChannel_2: null, ledNormalMode_2: "Default"])
        hub.state.clear()
    }

    private void humidity(Number v) { hub.script.humidityHandler(hub.event(bath, "humidity", v.toString())) }
    private void fanEvent(String v, String type = "physical") { hub.script.fanHandler(hub.event(fan, "switch", v, type)) }
    private void echoOwnCommand() { fanEvent(fan.states.switch as String, "digital") }

    def "starts the fan when the bathroom is 10% above the reference"() {
        when: humidity(51)
        then:
        fan.commandNames() == ["on"]
        st().holding
        hub.isScheduled("checkTimeLimits")
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
        st().override
        !st().holding

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
        st().override
    }

    def "turns the fan off once humidity is back within the stop delta"() {
        given: humidity(60); echoOwnCommand(); fan.calls.clear()
        when: humidity(43)
        then:
        fan.commandNames() == ["off"]
        !st().holding
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
        when: expireTimeLimit()
        then:
        fan.commandNames() == ["off"]
        st().expired
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
        hub.settings.ledDevice_1 = parent
        hub.settings.ledChannel_1 = "Relay"
        hub.state.b1 = [blinkStep: 6, blinkTotal: 6]
        when: hub.script.blinkStep([i: 1])
        then: parent.calls == [["setLEDMode", "Relay", "Default"]]
    }

    def "a fast rise starts the fan before the full start delta"() {
        given: humidity(42)                      // 2% above reference
        when: hub.advance(120); humidity(45); hub.advance(60); humidity(47)   // +5 in 3 min, 7% above
        then:
        fan.commandNames() == ["on"]
        st().holding
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

    def "a colder bathroom holding the same moisture as the house does not start the fan"() {
        given: "house 37% at 74°F, which is ~50% at 65°F"
        ref.states.humidity = 37; ref.states.temperature = 74
        bath.states.temperature = 65
        when: humidity(49)
        then: fan.calls.isEmpty()
    }

    def "a shower in a colder bathroom still starts the fan"() {
        given: ref.states.humidity = 37; ref.states.temperature = 74
        bath.states.temperature = 65
        when: humidity(61)                       // ~11 above the temperature-corrected 50
        then: fan.commandNames() == ["on"]
    }

    def "without a bathroom temperature the raw reference is used"() {
        given: ref.states.humidity = 37; ref.states.temperature = 74
        when: humidity(49)
        then: fan.commandNames() == ["on"]
    }

    // ── Several bathrooms in one app ─────────────────────────────────────────

    private void guestHumidity(Number v) { hub.script.humidityHandler(hub.event(guestBath, "humidity", v.toString())) }

    def "a shower in one bathroom starts only that bathroom's fan"() {
        when: guestHumidity(55)
        then:
        guestFan.commandNames() == ["on"]
        fan.calls.isEmpty()
        guest().holding
        !st().holding
        hub.logsMatching("Guest Bathroom: humidity")
    }

    def "an off in one bathroom is refused without touching the other"() {
        given: guestHumidity(60); hub.script.fanHandler(hub.event(guestFan, "switch", "on", "digital")); guestFan.calls.clear()
        when: hub.script.fanHandler(hub.event(guestFan, "switch", "off", "physical"))
        then:
        guestFan.commandNames().first() == "on"
        fan.calls.isEmpty()
    }

    def "a reference change re-evaluates every bathroom"() {
        given: ref.states.humidity = 30
        when: hub.script.refHandler(hub.event(ref, "humidity", "30"))
        then: "both are now 10+ above the reference"
        fan.commandNames() == ["on"]
        guestFan.commandNames() == ["on"]
    }

    def "each bathroom's time limit is its own"() {
        given: humidity(60); echoOwnCommand()
        hub.advance(1800)
        guestHumidity(60); hub.script.fanHandler(hub.event(guestFan, "switch", "on", "digital"))
        fan.calls.clear(); guestFan.calls.clear()

        when: "the master's hour is up, the guest's isn't"
        hub.advance(1801); hub.script.checkTimeLimits()

        then:
        fan.commandNames() == ["off"]
        st().expired
        guestFan.calls.isEmpty()
        guest().holding
        hub.scheduled.findAll { it.method == "checkTimeLimits" }.last().delay == 1800 - 1   // the guest's deadline is next
    }

    def "events from devices that aren't a configured bathroom are ignored"() {
        given: def other = new FakeDevice(id: 999, displayName: "Kitchen Sensor", states: [humidity: 90])
        when: hub.script.humidityHandler(hub.event(other, "humidity", "90"))
        then: fan.calls.isEmpty() && guestFan.calls.isEmpty()
    }

    def "a one-bathroom setup ignores leftover settings for bathroom 2"() {
        given: hub.settings.bathroomCount = 1
        when: hub.script.updated()
        then:
        hub.subscriptions.count { it[1] == "switch" } == 1
        !hub.state.containsKey("b2")
    }
}
