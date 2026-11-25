import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll
import support.DeviceHarness

class AirGradientLocalSpec extends Specification {
    DeviceHarness ag(Map settings = [:]) {
        new DeviceHarness("airgradient-local.groovy", [ip: "192.168.86.55", pollSeconds: 60, offlineAfter: 3,
                                                       autoCorrect: true, logEnable: false] + settings)
    }

    @Unroll
    def "US AQI for PM2.5 #pm is #aqi (EPA 2024 breakpoints)"() {
        expect: ag().script.aqi(pm) == aqi
        where:
        pm    | aqi
        0     | 0
        9.0   | 50
        9.1   | 51
        35.4  | 100
        35.5  | 101
        55.5  | 151
        125.5 | 201
        600   | 500
    }

    def "reports corrected values from a real AirGradient ONE reading"() {
        given:
        def h = ag()
        def json = new JsonSlurper().parseText(new File("test/fixtures/airgradient-one-measures.json").text)

        when: h.script.pollResponse(DeviceHarness.response(200, json), null)

        then:
        h.last("pm25") == 3.7            // pm02Compensated, not raw pm02 (2)
        h.last("pm25Raw") == 2.0
        h.last("humidity") == 36.1       // rhumCompensated
        h.last("temperature") == 71.4    // 21.91 °C -> °F
        h.last("carbonDioxide") == 572
        h.last("airQualityIndex") == 20
        h.last("airQualityDescriptive") == "healthy"
        h.last("healthStatus") == "online"
        h.last("model") == "I-9PSL"
        h.last("lastUpdate") != null
    }

    @Unroll
    def "AQI #idx is category '#category'"() {
        expect: ag().script.aqiCategory(idx) == category
        where:
        idx | category
        0   | "healthy"
        50  | "healthy"
        51  | "moderate"
        101 | "unhealthy for sensitive groups"
        151 | "unhealthy"
        201 | "very unhealthy"
        301 | "hazardous"
    }

    def "marks the monitor offline only after the configured number of failed polls"() {
        given: def h = ag()
        when: 2.times { h.script.pollResponse(DeviceHarness.response(500), null) }
        then: h.last("healthStatus") == null
        when: h.script.pollResponse(DeviceHarness.response(500), null)
        then: h.last("healthStatus") == "offline"
    }

    def "indoor monitor: one request with the PM2.5 correction only"() {
        given: def h = ag(); h.state.model = "I-9PSL"
        when: h.script.applyRecommendedCorrections()
        then:
        h.http.size() == 1
        h.http[0].params.body == '{"corrections":{"pm02":{"correctionAlgorithm":"epa_2021","slr":{}}}}'
    }

    def "Open Air: PM2.5 first, then temperature/humidity as a separate request"() {
        given: def h = ag(); h.state.model = "O-1PST"
        when: h.script.applyRecommendedCorrections()
        then: h.http*.data.step == ["pm02"]

        when: h.script.correctionResponse(DeviceHarness.response(200), [step: "pm02"])
        then:
        h.http.size() == 2
        h.http[1].params.body.contains('"atmp"') && !h.http[1].params.body.contains('"pm02"')

        when: h.script.correctionResponse(DeviceHarness.response(200), [step: "temperature/humidity"])
        then: h.scheduled*.method.contains("fetchConfig")
    }

    def "does not touch corrections on a cloud-only monitor"() {
        given: def h = ag(); h.state.configurationControl = "cloud"
        when: h.script.applyRecommendedCorrections()
        then: h.http.isEmpty()
    }
}
