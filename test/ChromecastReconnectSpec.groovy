import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class ChromecastReconnectSpec extends Specification {
	FakeDevice entry = new FakeDevice(id: 85, displayName: "Entryway speaker", states: [networkStatus: "online"])
	FakeDevice kitchen = new FakeDevice(id: 89, displayName: "Kitchen display", states: [networkStatus: "online"])
	AppHarness hub

	def setup() {
		hub = new AppHarness("chromecast-reconnect.groovy",
			[speakers: [entry, kitchen], reconnectDelaySeconds: 60, retryMinutes: 15, logEnable: false])
		hub.state.clear()
	}

	private void status(FakeDevice d, String v) { hub.script.networkStatusHandler(hub.event(d, "networkStatus", v)) }

	def "an offline report schedules a reconnect after the delay"() {
		when: status(entry, "offline")
		then: hub.scheduled.find { it.method == "reconnectOffline" }?.delay == 60
	}

	def "an online report schedules nothing"() {
		when: status(entry, "online")
		then: hub.scheduled.isEmpty()
	}

	def "reconnect initializes only the speakers still offline, then schedules a retry"() {
		given: entry.states.networkStatus = "offline"
		when: hub.script.reconnectOffline()
		then:
		entry.commandNames() == ["initialize"]
		kitchen.calls.isEmpty()
		hub.scheduled.find { it.method == "reconnectOffline" }?.delay == 900
		hub.state.lastReconnect["85"] == hub.clock
		hub.logsMatching("Entryway speaker is offline")
	}

	def "a speaker that came back on its own is left alone and nothing is rescheduled"() {
		given: status(entry, "offline"); hub.scheduled.clear(); entry.states.networkStatus = "online"
		when: hub.script.reconnectOffline()
		then:
		entry.calls.isEmpty()
		hub.scheduled.isEmpty()
	}

	def "install subscribes to networkStatus and hub restarts, and checks right away"() {
		when: hub.script.installed()
		then:
		hub.subscriptions.any { it[1] == "networkStatus" }
		hub.subscriptions.any { it[1] == "systemStart" }
		hub.isScheduled("reconnectOffline")
	}
}
