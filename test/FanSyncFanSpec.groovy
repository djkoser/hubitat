import spock.lang.Specification
import spock.lang.Unroll
import support.DeviceHarness

class FanSyncFanSpec extends Specification {
    List<Map> sent = []
    boolean connected = true
    def parent = [componentSet: { dev, Map keys -> if (connected) sent << keys; connected }, componentRefresh: { dev -> }]

    DeviceHarness fan(String presets = "20,35,50,65,80,100") {
        new DeviceHarness("fansync-fan.groovy", [logEnable: false],
            presets ? [fansyncId: "ABC", speedPresets: presets] : [fansyncId: "ABC"], parent)
    }

    @Unroll
    def "speed #speed sends #pct with FD6 presets"() {
        given: def h = fan()
        when: h.script.setSpeed(speed)
        then:
        sent == [[H00: 1, H01: 0, H02: pct]]
        h.last("speed") == speed
        where:
        speed         | pct
        "low"         | 35
        "medium-low"  | 50
        "medium"      | 65
        "medium-high" | 80
        "high"        | 100
    }

    def "without presets, named speeds use the generic percentages"() {
        given: def h = fan(null)
        when: h.script.setSpeed("low")
        then: sent == [[H00: 1, H01: 0, H02: 25]]
    }

    def "auto turns on breeze mode"() {
        given: def h = fan()
        when: h.script.setSpeed("auto")
        then:
        sent == [[H00: 1, H01: 1]]
        h.last("preset") == "fresh_air"
        h.last("speed") == "auto"
        h.last("switch") == "on"
    }

    def "levels snap to the nearest preset; 0 turns the fan off; strings are accepted"() {
        given: def h = fan()
        when: h.script.setLevel("47.6")
        then: sent.last() == [H00: 1, H01: 0, H02: 50]
        when: h.script.setLevel(0)
        then: sent.last() == [H00: 0]
    }

    def "reports breeze mode as speed auto, and the nearest named speed otherwise"() {
        given: def h = fan()
        when: h.script.parseStatus([H00: 1, H01: 1, H02: 35])
        then: h.last("speed") == "auto"
        when: h.script.parseStatus([H01: 0])
        then: h.last("speed") == "low"
        when: h.script.parseStatus([H02: 66])
        then: h.last("speed") == "medium"
    }

    def "publishes supported speeds without on/off"() {
        given: def h = fan()
        when: h.script.parseStatus([H00: 0])
        then: h.last("supportedFanSpeeds") == '["low","medium-low","medium","medium-high","high","auto"]'
    }

    def "translates fault codes from the information model"() {
        given: def h = fan()
        when: h.script.parseStatus([H0E: 262])
        then:
        h.last("errorCode") == 262
        h.last("errorText") == "Stalled fault"
        h.logsMatching("fan reports error 262")
        when: h.script.parseStatus([H0E: 0])
        then: h.last("errorText") == "none"
    }

    def "a dropped command leaves state alone"() {
        given: def h = fan(); connected = false
        when: h.script.on()
        then: h.events.isEmpty()
    }

    def "Home Shield"() {
        given: def h = fan()
        when: h.script.setHomeShield("on")
        then:
        sent == [[H0D: 1]]
        h.last("homeShield") == "on"
    }
}
