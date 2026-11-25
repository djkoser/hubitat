import spock.lang.Specification
import spock.lang.Unroll
import support.DeviceHarness

class FanSyncControllerSpec extends Specification {
    DeviceHarness ctl = new DeviceHarness("fansync-controller.groovy", [email: "a@b.c", password: "x", pollSecs: 60, logEnable: false])

    def setup() {
        def text = new File("test/fixtures/fanimation-info-model.json").text
        ctl.script.infoModelResponse(DeviceHarness.response(200, null, text), null)
    }

    def "reduces Fanimation's information model to per-model light and speed specs"() {
        expect:
        ctl.state.infoModelFormat == 2
        def fd6 = ctl.state.infoModel["FAN-FD6R1LD5"]
        fd6.fan
        fd6.speeds == [20, 35, 50, 65, 80, 100]
        fd6.lights*.id == ["LIGHT2", "LIGHT"]
        fd6.lights.find { it.id == "LIGHT" }.subMap(["name", "power", "level", "cct"]) == [name: "Down Light", power: "H0B", level: "H0C", cct: "H04"]
        fd6.lights.find { it.id == "LIGHT2" }.subMap(["name", "power", "level"]) == [name: "Up Light", power: "H09", level: "H0A"]
        fd6.lights.find { it.id == "LIGHT" }.cctPresets == [2700, 3000, 3500, 4000, 5000]
    }

    @Unroll
    def "model #model matches spec #spec"() {
        expect: ctl.script.specFor(model)?.specName == spec
        where:
        model                    | spec
        "TorchAire52-FD6R1LD5"   | "FAN-FD6R1LD5"
        "OdynCustom-FDR1L3"      | "FAN-FDR1L3"
        "Something-FDR1L0"       | "FAN-FDR1L0"
        "Porch-L2"               | "LIGHT-L2"
        "Unknown-XYZ"            | null
    }

    def "standalone lights use H00/H02 and are not fans"() {
        when: def s = ctl.script.specFor("Porch-L2")
        then:
        !s.fan
        s.lights.size() == 1
        s.lights[0].subMap(["power", "level"]) == [power: "H00", level: "H02"]
    }

    @Unroll
    def "fallback without the information model: #model with #fields"() {
        when: def s = ctl.script.guessSpec(model, fields)
        then:
        s.fan == fan
        s.lights*.id == lights
        where:
        model                  | fields                              | fan  | lights
        "TorchAire-FD6R1LD5"   | ["H00", "H0B", "H0C", "H04", "H09"] | true | ["LIGHT", "LIGHT2"]
        "Odyn-FDR1L0"          | ["H00", "H02"]                      | true | []
        "Odyn-FDR1L2"          | ["H00", "H0B", "H0C"]               | true | ["LIGHT"]
        "Lamp-L1"              | ["H00"]                             | false| ["LIGHT"]
    }

    def "an expired session token is detected locally"() {
        given:
        def b64 = { String s -> s.bytes.encodeBase64().toString().replace('=', '') }
        def token = { long exp -> "${b64('{"alg":"HS256"}')}.${b64("{\"exp\":${exp}}")}.sig" }
        when: ctl.state.token = token((ctl.clock / 1000 + 3600) as long)
        then: !ctl.script.tokenExpired()
        when: ctl.state.token = token((ctl.clock / 1000 + 30) as long)
        then: ctl.script.tokenExpired()           // within the 60 s margin
        when: ctl.state.token = "not-a-jwt"
        then: !ctl.script.tokenExpired()          // let the server judge it
    }
}
