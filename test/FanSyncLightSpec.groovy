import spock.lang.Specification
import support.DeviceHarness

class FanSyncLightSpec extends Specification {
    List<Map> sent = []
    def parent = [componentSet: { dev, Map keys -> sent << keys; true }, componentRefresh: { dev -> }]

    // TorchAire up light (LIGHT2) per Fanimation's information model
    DeviceHarness upLight() {
        new DeviceHarness("fansync-light.groovy", [logEnable: false, levelStep: 10],
            [fansyncId: "ABC", powerKey: "H09", levelKey: "H0A", cctKey: "H04", cctMin: "2700", cctMax: "5000",
             cctPresets: "2700,3000,3500,4000,5000", standalone: "false"], parent)
    }

    def "on/off use the light's own power key"() {
        given: def h = upLight()
        when: h.script.on(); h.script.off()
        then: sent == [[H09: 1], [H09: 0]]
    }

    def "brightness also switches the light on (the model's before-rule); 0 means off"() {
        given: def h = upLight()
        when: h.script.setLevel(30)
        then:
        sent == [[H09: 1, H0A: 30]]
        h.last("level") == 30
        h.last("switch") == "on"
        when: h.script.setLevel(0)
        then: sent.last() == [H09: 0]
    }

    def "color temperature snaps to the model's presets"() {
        given: def h = upLight()
        when: h.script.setColorTemperature(3300)
        then:
        sent == [[H09: 1, H04: 3500]]
        h.last("colorTemperature") == 3500
        h.last("colorName") == "Bright White"
        when: h.script.setColorTemperature("2600.0", 40)
        then: sent.last() == [H09: 1, H04: 2700, H0A: 40]
    }

    def "a light without color temperature warns instead of sending"() {
        given:
        def h = new DeviceHarness("fansync-light.groovy", [logEnable: false], [fansyncId: "ABC", powerKey: "H0B", levelKey: "H0C"], parent)
        when: h.script.setColorTemperature(3000)
        then:
        sent.isEmpty()
        h.logsMatching("no color temperature control")
    }

    def "an on/off-only light treats setLevel as on"() {
        given: def h = new DeviceHarness("fansync-light.groovy", [logEnable: false], [fansyncId: "ABC", powerKey: "H00"], parent)
        when: h.script.setLevel(40)
        then: sent == [[H00: 1]]
    }

    def "unset brightness from the cloud is reported as 100% so a level always exists"() {
        given: def h = upLight()
        when: h.script.parseStatus([H09: 0, H0A: 0])
        then: h.last("level") == 100
        when: h.events.clear(); h.script.parseStatus([H0A: 0])
        then: "an existing level is kept"
        h.last("level") == null
    }

    def "features summary lists the color temperature range and presets"() {
        given: def h = upLight()
        when: h.script.configureFeatures()
        then: h.last("lightFeatures") == "on/off, dimming, color temperature 2700-5000K (2700/3000/3500/4000/5000)"
    }
}
