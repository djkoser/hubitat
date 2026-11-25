import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class ErvAutoControllerSpec extends Specification {
    FakeDevice auto = new FakeDevice(id: 334, displayName: "ERV Auto", states: [switch: "on"])
    FakeDevice living = new FakeDevice(id: 337, displayName: "Living Room", states: [carbonDioxide: 600, vocIndex: 100, noxIndex: 1, pm25: 3, healthStatus: "online"])
    FakeDevice bedroom = new FakeDevice(id: 339, displayName: "Master Bedroom", states: [carbonDioxide: 650, vocIndex: 100, noxIndex: 1, pm25: 3, healthStatus: "online"])
    FakeDevice outside = new FakeDevice(id: 338, displayName: "Outdoor", states: [carbonDioxide: 420, vocIndex: 100, noxIndex: 1, pm25: 4, healthStatus: "online"])
    FakeDevice ervRelay = new FakeDevice(id: 330, displayName: "ERV Stop Relay", states: [switch: "on"])
    FakeDevice ahRelay = new FakeDevice(id: 331, displayName: "Air Handler Stop Relay", states: [switch: "on"])
    AppHarness hub

    def setup() {
        hub = new AppHarness("erv-auto-controller.groovy",
            [autoSwitch: auto, indoor: [living, bedroom], outdoor: [outside], ervStopRelay: ervRelay, ahStopRelay: ahRelay,
             ervAutoMinRunMinutes: 30, ervAutoMinOffMinutes: 30, logEnable: false],
            [:])
        [living, bedroom, outside].each { it.lastActivity = new Date(hub.clock) }
    }

    // Monitors report on each evaluation, so time skips don't make them stale
    private String evaluate() {
        [living, bedroom, outside].each { it.lastActivity = new Date(hub.clock) }
        hub.script.evaluate(); hub.state.current
    }
    // Relays are normally closed: relay off = equipment runs
    private boolean ervRuns() { ervRelay.states.switch == "off" }
    private boolean ahRuns()  { ahRelay.states.switch == "off" }

    def "idles when the air is good"() {
        expect:
        evaluate() == "Off"
        !ervRuns() && !ahRuns()
        hub.state.cause == "Air good"
        hub.state.reason == "air good: CO2 over outdoor 230 ppm < 600 ppm (650 ppm in Master Bedroom vs 420 ppm outdoors), VOC index 100 < 200, NOx index 1 < 100, PM2.5 3 µg/m³ < 15 µg/m³"
    }

    def "ventilates for high CO2 in the worst room and names that room"() {
        given: bedroom.states.carbonDioxide = 1100
        expect:
        evaluate() == "On"
        ervRuns() && ahRuns()
        hub.state.reason == "indoor CO2 over outdoor 680 ppm ≥ 600 ppm (1100 ppm in Master Bedroom vs 420 ppm outdoors); outdoor PM2.5 4 µg/m³ < 35 µg/m³, NOx index 1 < 100, VOC index 100 < 400"
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
        outside.states.noxIndex = 150
        expect:
        evaluate() == "Recirculate"
        ahRuns() && !ervRuns()
    }

    def "PM2.5: ventilates when outdoor air is cleaner by the margin, otherwise filters"() {
        given: living.states.pm25 = 20

        expect: "outdoor 4 is more than 5 below 20"
        evaluate() == "On"

        when: "outdoor smoke: the ERV stops at once, inside its minimum run, the air handler keeps filtering"
        outside.states.pm25 = 40
        then:
        evaluate() == "Recirculate"
        ahRuns() && !ervRuns()
    }

    def "CO2 is judged relative to outdoor: trips at outdoor + 600, clears at outdoor + 450"() {
        when: living.states.carbonDioxide = 1020; evaluate()
        then: hub.state.flags.inCo2Bad
        when: living.states.carbonDioxide = 900; evaluate()
        then: hub.state.flags.inCo2Bad
        when: living.states.carbonDioxide = 870; evaluate()
        then: !hub.state.flags.inCo2Bad
        when: "the same indoor level with higher outdoor CO2 is no longer a problem"
        living.states.carbonDioxide = 1020; outside.states.carbonDioxide = 500; evaluate()
        then: !hub.state.flags.inCo2Bad
    }

    def "assumes 420 ppm outdoors when no outdoor monitor reports CO2"() {
        given: outside.states.remove("carbonDioxide"); living.states.carbonDioxide = 1020
        expect: evaluate() == "On"
    }

    def "once started, runs at least the minimum run time"() {
        given: living.states.carbonDioxide = 1100; evaluate()
        living.states.carbonDioxide = 600
        when: hub.advance(29 * 60); evaluate()
        then:
        hub.state.current == "On" && ervRuns()
        hub.state.reason.endsWith("— holding On: ERV ran 29 of 30 min, air handler ran 29 of 30 min")
        when: hub.advance(61); evaluate()
        then: hub.state.current == "Off" && !ervRuns() && !ahRuns()
    }

    def "once stopped, stays off at least the minimum off time"() {
        given: living.states.carbonDioxide = 1100; evaluate(); hub.advance(31 * 60)
        living.states.carbonDioxide = 600; evaluate()          // Off
        living.states.carbonDioxide = 1100
        when: hub.advance(10 * 60); evaluate()
        then: hub.state.current == "Off" && hub.state.reason.contains("ERV off 10 of 30 min")
        when: hub.advance(20 * 60); evaluate()
        then: hub.state.current == "On"
    }

    def "critical CO2 starts equipment inside its minimum off time"() {
        given: living.states.carbonDioxide = 1100; evaluate(); hub.advance(31 * 60)
        living.states.carbonDioxide = 600; evaluate()          // Off
        when: living.states.carbonDioxide = 1600; hub.advance(60); evaluate()
        then: hub.state.current == "On"
    }

    def "the fail-safe starts equipment inside its minimum off time, then holds the minimum run"() {
        given: living.states.carbonDioxide = 1100; evaluate(); hub.advance(31 * 60)
        living.states.carbonDioxide = 600; evaluate()          // Off
        when: living.states.healthStatus = "offline"; bedroom.states.healthStatus = "offline"; hub.advance(60); evaluate()
        then: hub.state.current == "On"
        when: "monitors return with good air"
        living.states.healthStatus = "online"; bedroom.states.healthStatus = "online"; hub.advance(5 * 60); evaluate()
        then: hub.state.current == "On"
        when: hub.advance(26 * 60); evaluate()
        then: hub.state.current == "Off"
    }

    def "a PM2.5 run is held for the minimum run too"() {
        given: "PM2.5 high, outdoor cleaner: ventilating"
        living.states.pm25 = 20; evaluate()
        when: "indoor clears after 5 minutes"
        living.states.pm25 = 3; hub.advance(5 * 60); evaluate()
        then: "both held until the minimum run"
        hub.state.current == "On"
        when: hub.advance(26 * 60); evaluate()
        then: hub.state.current == "Off"
    }

    def "takes the equipment as it is when Auto starts, without holding it"() {
        given: "ERV was running under another mode"
        ervRelay.states.switch = "off"; ahRelay.states.switch = "off"
        expect: evaluate() == "Off"
    }

    def "an offline monitor is ignored and named while others still work"() {
        given:
        bedroom.states.healthStatus = "offline"
        bedroom.states.carbonDioxide = 2000      // would be critical if it counted
        expect:
        evaluate() == "Off"
        hub.state.reason.startsWith("air good: ") && hub.state.reason.endsWith("(ignoring offline: Master Bedroom)")
    }

    def "fail-safe only when every indoor monitor is offline"() {
        given: living.states.healthStatus = "offline"; bedroom.states.healthStatus = "offline"
        expect:
        evaluate() == "On"
        hub.state.reason.startsWith("all indoor monitors offline/stale")
    }

    def "the status table shows each reading against its trigger and clear levels"() {
        given:
        bedroom.states.carbonDioxide = 1100
        hub.settings.ervAutoVocHigh = 200
        evaluate()

        when: def html = hub.script.readingsTable()

        then:
        html.contains("<td>Indoor CO₂ above outdoor (ppm)</td><td>680 <small>(1100 Master Bedroom vs outdoor 420)</small></td><td>≥ 600</td><td>≤ 450</td><td><b style='color:#c00'>high</b></td>")
        html.contains("<td>Indoor VOC index</td><td>100 <small>(Living Room)</small></td><td>≥ 200</td><td>≤ 150</td><td>ok</td>")
        html.contains("<td>Outdoor PM2.5 (µg/m³)</td><td>4 <small>(Outdoor)</small></td><td>≥ 35</td><td>≤ 30</td><td>ok</td>")
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
        hub.settings.ervAutoCo2AboveOutdoor = 300
        living.states.carbonDioxide = 720
        expect: evaluate() == "On"
    }

    def "removes retired settings and timing state on update"() {
        given:
        hub.settings.ervAutoCo2High = 1000; hub.settings.ervAutoMinDwellMinutes = 15
        hub.state.ventMs = 5
        when: hub.script.updated()
        then:
        !hub.settings.containsKey("ervAutoCo2High") && !hub.settings.containsKey("ervAutoMinDwellMinutes")
        !hub.state.containsKey("ventMs")
    }

    def "a view-only status device follows the relays in every mode"() {
        when: hub.script.initialize()
        def status = hub.children.values().first()
        then:
        status.displayName == "ERV Status"
        status.states.ervStatus == "Off"

        when: "relays change from anywhere (manual modes, Auto)"
        hub.script.relayHandler(hub.event(ahRelay, "switch", "off"))
        then: status.states.ervStatus == "Recirculate"

        when: hub.script.relayHandler(hub.event(ervRelay, "switch", "off"))
        then:
        status.states.ervStatus == "On"

        when: "a second initialize doesn't create another device"
        hub.script.initialize()
        then: hub.children.size() == 1
    }

    def "the status device also says why"() {
        given: hub.settings.ervAutoVocHigh = 200
        hub.script.initialize()
        def status = hub.children.values().first()

        when: "indoor VOC high, outdoor VOC high: recirculating"
        living.states.vocIndex = 215; outside.states.vocIndex = 450; evaluate()
        then:
        status.states.ervStatus == "Recirculate"
        status.states.ervCause == "Outdoor VOC index ≥ 400 (clears ≤ 375)"
        status.states.ervReason == "indoor VOC index 215 ≥ 200 (Living Room), but outdoor VOC index 450 ≥ 400"

        when: "air clears inside its minimum run"
        living.states.vocIndex = 100; hub.advance(5 * 60); evaluate()
        then: status.states.ervCause == "Minimum run (30 min)"

        when: "outdoor NOx bad too"
        living.states.vocIndex = 215; outside.states.noxIndex = 150; hub.advance(60); evaluate()
        then: status.states.ervCause == "Outdoor NOx index ≥ 100 (clears ≤ 75)"

        when: "after the minimum run, then high again inside the minimum off"
        living.states.vocIndex = 100; outside.states.noxIndex = 1; outside.states.vocIndex = 100
        hub.advance(26 * 60); evaluate()
        living.states.carbonDioxide = 1100; hub.advance(60); evaluate()
        then:
        status.states.ervCause == "Minimum off (30 min)"

        when: "a manual mode is selected"
        hub.script.autoSwitchHandler(hub.event(auto, "switch", "off"))
        then:
        status.states.ervCause == "Manual"
        status.states.ervReason == "manual ERV mode (Auto is off)"
    }

    def "the reason shows a flag that is still clearing against its clear level"() {
        given: hub.settings.ervAutoVocHigh = 200
        living.states.vocIndex = 215; evaluate()
        when: living.states.vocIndex = 180; hub.advance(60); evaluate()
        then: hub.state.reason.startsWith("indoor VOC index 180 (high until ≤ 150) (Living Room); outdoor PM2.5 4 µg/m³ < 35 µg/m³")
    }

    def "stage 1: an elevated outdoor VOC index alone doesn't block ventilation; a spike does"() {
        given: hub.settings.ervAutoVocHigh = 200; living.states.vocIndex = 215
        when: outside.states.vocIndex = 300; evaluate()
        then: hub.state.current == "On"
        when: outside.states.vocIndex = 420; hub.advance(60); evaluate()
        then: hub.state.current == "Recirculate" && hub.state.cause == "Outdoor VOC spike"
    }

    def "stage 1: outdoor smoke (PM2.5) blocks ventilation for indoor VOC"() {
        given: hub.settings.ervAutoVocHigh = 200; living.states.vocIndex = 215; outside.states.pm25 = 40
        expect: evaluate() == "Recirculate"
        hub.state.cause == "Outdoor PM2.5 high"
    }



    def "a monitor that stops updating counts as offline after the stale time"() {
        given: evaluate()
        when: "the outdoor monitor goes quiet for 16 minutes (the indoor ones keep reporting)"
        hub.advance(16 * 60)
        [living, bedroom].each { it.lastActivity = new Date(hub.clock) }
        hub.script.evaluate()
        then:
        hub.state.cause == "Monitors offline"
        hub.state.reason.startsWith("all outdoor monitors offline/stale (Outdoor)")
    }

    def "every setting explains its default with a citation that exists"() {
        given:
        def params = hub.script.PARAMS
        def refs = hub.script.REFERENCES
        expect:
        params.every { n, d -> d.size() == 4 && d[3].startsWith("Default ") && d[3] =~ /\[\d+\]/ }
        params.values().collectMany { d -> (d[3] =~ /\[(\d+)\]/).collect { it[1] as Integer } }.every { refs.containsKey(it) }
        refs.values().every { it[1].startsWith("https://") }
    }

    def "the status device carries the readings and Google's value lists"() {
        given: hub.script.initialize()
        def status = hub.children.values().first()
        living.states.vocIndex = 203; living.states.noxIndex = 1; bedroom.states.carbonDioxide = 672
        when: evaluate()
        then:
        status.states.carbonDioxide == 672
        status.states.pm25 == 3
        status.states.vocIndex == "203"
        status.states.noxIndex == "1"
        new groovy.json.JsonSlurper().parseText(status.states.indexValues).size() == 500
        new groovy.json.JsonSlurper().parseText(status.states.indexValues).last() == "500"
        new groovy.json.JsonSlurper().parseText(status.states.ervCauseValues).contains("Indoor VOC index ≥ 200 (clears ≤ 150)")

        when: "manual mode: readings still update"
        hub.script.autoSwitchHandler(hub.event(auto, "switch", "off"))
        living.states.vocIndex = 260; hub.script.evaluate()
        then:
        status.states.vocIndex == "260"
        status.states.ervCause == "Manual"
    }
}
