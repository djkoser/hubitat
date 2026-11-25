import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class ErvAutoControllerSpec extends Specification {
    FakeDevice auto = new FakeDevice(id: 334, displayName: "ERV Auto", states: [switch: "on"])
    FakeDevice living = new FakeDevice(id: 337, displayName: "Living Room", states: [carbonDioxide: 600, vocIndex: 100, noxIndex: 1, pm25: 3, healthStatus: "online"])
    FakeDevice bedroom = new FakeDevice(id: 339, displayName: "Master Bedroom", states: [carbonDioxide: 650, vocIndex: 100, noxIndex: 1, pm25: 3, healthStatus: "online"])
    FakeDevice outside = new FakeDevice(id: 338, displayName: "Outdoor", states: [vocIndex: 100, noxIndex: 1, pm25: 4, healthStatus: "online"])
    FakeDevice ervRelay = new FakeDevice(id: 330, displayName: "ERV Stop Relay", states: [switch: "on"])
    FakeDevice ahRelay = new FakeDevice(id: 331, displayName: "Air Handler Stop Relay", states: [switch: "on"])
    AppHarness hub

    def setup() {
        hub = new AppHarness("erv-auto-controller.groovy",
            [autoSwitch: auto, indoor: [living, bedroom], outdoor: [outside], ervStopRelay: ervRelay, ahStopRelay: ahRelay,
             ervAutoMinVentMinutesPerHour: 0, logEnable: false],
            [ervAutoState: "", ervAutoReason: ""])
        [living, bedroom, outside].each { it.lastActivity = new Date(hub.clock) }
    }

    private String evaluate() { hub.script.evaluate(); hub.state.current }
    // Relays are normally closed: relay off = equipment runs
    private boolean ervRuns() { ervRelay.states.switch == "off" }
    private boolean ahRuns()  { ahRelay.states.switch == "off" }

    def "idles when the air is good"() {
        expect:
        evaluate() == "Off"
        !ervRuns() && !ahRuns()
        hub.globals.ervAutoState == "Off"
        hub.globals.ervAutoReason == "air good"
    }

    def "ventilates for high CO2 in the worst room and names that room"() {
        given: bedroom.states.carbonDioxide = 1100
        expect:
        evaluate() == "On"
        ervRuns() && ahRuns()
        hub.state.reason == "indoor CO2 1100 (Master Bedroom) high"
    }

    def "turns the air handler on before the ERV"() {
        given: living.states.carbonDioxide = 1100
        when: evaluate()
        then:
        ahRelay.commandNames() == ["off"]
        ervRelay.commandNames() == ["off"]
    }

    def "recirculates instead when outdoor gases are bad"() {
        given:
        living.states.vocIndex = 300
        outside.states.noxIndex = 80
        expect:
        evaluate() == "Recirculate"
        ahRuns() && !ervRuns()
    }

    def "PM2.5: ventilates when outdoor air is cleaner by the margin, otherwise filters"() {
        given: living.states.pm25 = 20

        expect: "outdoor 4 is more than 5 below 20"
        evaluate() == "On"

        when: "outdoor smoke"
        hub.state.lastChange = 0
        outside.states.pm25 = 40
        then:
        evaluate() == "Recirculate"
    }

    def "critical CO2 overrides the minimum dwell"() {
        given: evaluate()                       // Off, just changed
        living.states.carbonDioxide = 1600
        expect: evaluate() == "On"
    }

    def "a non-urgent change waits for the minimum dwell"() {
        given: evaluate()                       // Off at t0
        living.states.carbonDioxide = 1100
        when: hub.advance(5 * 60); evaluate()
        then: hub.state.current == "Off"
        when: hub.advance(11 * 60); evaluate()
        then: hub.state.current == "On"
    }

    def "CO2 hysteresis: trips at 1000, clears only at or below 850"() {
        when: living.states.carbonDioxide = 1000; evaluate()
        then: hub.state.flags.inCo2Bad
        when: living.states.carbonDioxide = 900; evaluate()
        then: hub.state.flags.inCo2Bad
        when: living.states.carbonDioxide = 850; evaluate()
        then: !hub.state.flags.inCo2Bad
    }

    def "an offline monitor is ignored and named while others still work"() {
        given:
        bedroom.states.healthStatus = "offline"
        bedroom.states.carbonDioxide = 2000      // would be critical if it counted
        expect:
        evaluate() == "Off"
        hub.state.reason == "air good (ignoring offline: Master Bedroom)"
    }

    def "fail-safe only when every indoor monitor is offline"() {
        given: living.states.healthStatus = "offline"; bedroom.states.healthStatus = "offline"
        expect:
        evaluate() == "On"
        hub.state.reason.startsWith("all indoor monitors offline/stale")
    }

    def "does nothing while another ERV mode is selected"() {
        given: auto.states.switch = "off"
        when: hub.script.evaluate()
        then:
        hub.state.current == null
        ervRelay.calls.isEmpty() && ahRelay.calls.isEmpty()
    }

    def "uses its own settings over defaults"() {
        given:
        hub.settings.ervAutoCo2High = 700
        living.states.carbonDioxide = 720
        expect: evaluate() == "On"
    }

    // On the hub an input the page hasn't saved reads as null; hubitat_ci returns its
    // defaultValue instead, so these tests pass explicit nulls for the "never saved" inputs.
    private AppHarness freshInstall(Map globals) {
        new AppHarness("erv-auto-controller.groovy",
            [autoSwitch: auto, indoor: [living], outdoor: [outside], ervStopRelay: ervRelay, ahStopRelay: ahRelay,
             ervAutoVocHigh: null, ervAutoIdleState: null, ervAutoPm25Margin: null, ervAutoMinVentMinutesPerHour: null,
             ervAutoCo2High: null, logEnable: false], globals)
    }

    def "carries settings over from the hub variables they replaced"() {
        given:
        def h = freshInstall([ervAutoVocHigh: "200", ervAutoIdleState: "Recirculate", ervAutoPm25Margin: "2.5",
                              ervAutoMinVentMinutesPerHour: "0"])

        when: h.script.migrateFromHubVariables()

        then:
        h.settings.ervAutoVocHigh == 200
        h.settings.ervAutoIdleState == "Recirculate"
        h.settings.ervAutoPm25Margin == 2.5
        h.settings.ervAutoMinVentMinutesPerHour == 0
        h.settings.ervAutoCo2High == null     // no hub variable: stays unset, default applies
        h.script.param("ervAutoVocHigh") == 200
        h.script.param("ervAutoCo2High") == 1000
    }

    def "the hub variable wins on the first run even over a just-saved default, and only once"() {
        given: "the first Done on the new page saved the field's default"
        hub.settings.ervAutoVocHigh = 250
        hub.globals.ervAutoVocHigh = "200"

        when: hub.script.migrateFromHubVariables()
        then: hub.settings.ervAutoVocHigh == 200

        when: "the user later changes it; the leftover hub variable is not applied again"
        hub.settings.ervAutoVocHigh = 300
        hub.script.migrateFromHubVariables()
        then: hub.settings.ervAutoVocHigh == 300
    }
}
