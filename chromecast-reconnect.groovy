/**
 * Chromecast Reconnect — Hubitat App
 *
 * WHY THIS EXISTS (a workaround, not a feature):
 * Hubitat's built-in Chromecast Integration keeps an open connection to each Google speaker
 * or display. Every few hours the speaker closes that connection; the integration then sets
 * the device's networkStatus to "offline" and never reconnects on its own. From then on,
 * speak() and playTrack() to that speaker do nothing, and nothing in the UI says why.
 * Pressing Initialize on the device reconnects it.
 *
 * Seen on this hub (C-8 Pro, platform 2.5.3.133), Nov 2026: an hourly "Initialize every
 * speaker" automation had been hiding this. Once it was removed (Nov 23), every speaker
 * dropped within a day and stayed offline, while all of them still answered on the network
 * at the addresses the hub had saved.
 *
 * What it does: when a selected speaker reports networkStatus "offline", wait
 * reconnectDelaySeconds, then Initialize each selected speaker that is still offline.
 * If any are still offline afterwards, try again every retryMinutes until they come back
 * (a speaker that is unplugged just gets a harmless Initialize every retryMinutes).
 * Speakers that stay connected are never touched. Also checks once after a hub restart.
 *
 * Remove this app if a platform update makes the integration reconnect by itself: watch a
 * speaker's networkStatus events with the app paused for a day.
 */

definition(
	name: "Chromecast Reconnect",
	namespace: "djkoser",
	author: "David Koser",
	description: "Reconnects Chromecast speakers that the built-in integration leaves offline",
	category: "Convenience",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/chromecast-reconnect.groovy",
	singleThreaded: true
)

preferences { page(name: "mainPage") }

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Why this app exists") {
			paragraph "Hubitat's built-in Chromecast Integration marks a speaker offline when the speaker " +
				"closes its connection (every few hours), and never reconnects it. Announcements to an " +
				"offline speaker silently do nothing. This app presses Initialize on a speaker shortly " +
				"after it goes offline, which reconnects it. Remove it if Hubitat fixes the integration."
		}
		section("Speakers") {
			input "speakers", "capability.initialize", title: "Chromecast speakers and displays", multiple: true, required: true
			input "reconnectDelaySeconds", "number", title: "Seconds to wait after a speaker goes offline", defaultValue: 60, range: "10..3600"
			input "retryMinutes", "number", title: "Minutes between retries while a speaker stays offline", defaultValue: 15, range: "1..1440"
			input "logEnable", "bool", title: "Debug logging", defaultValue: false
		}
		if (speakers) {
			section("Status") { paragraph statusText() }
		}
	}
}

def installed() { initialize() }
def updated() { unsubscribe(); unschedule(); initialize() }

def initialize() {
	if (state.lastReconnect == null) state.lastReconnect = [:]
	subscribe(speakers, "networkStatus", networkStatusHandler)
	subscribe(location, "systemStart", startupHandler)
	runIn(5, "reconnectOffline")
}

def networkStatusHandler(evt) {
	logDebug "${evt.displayName} networkStatus is ${evt.value}"
	if (evt.value == "offline") runIn(delaySeconds(), "reconnectOffline")
}

def startupHandler(evt) { runIn(120, "reconnectOffline") }

def reconnectOffline() {
	List offline = speakers?.findAll { it.currentValue("networkStatus") == "offline" } ?: []
	if (!offline) return
	if (state.lastReconnect == null) state.lastReconnect = [:]
	offline.each { dev ->
		log.info "${dev.displayName} is offline; reconnecting (Initialize)"
		state.lastReconnect[dev.id as String] = now()
		dev.initialize()
	}
	runIn(retrySeconds(), "reconnectOffline")
}

private String statusText() {
	speakers.collect { dev ->
		Long t = state.lastReconnect?.get(dev.id as String) as Long
		String last = t ? new Date(t).format("MMM d h:mm a", location.timeZone) : "never"
		"${dev.displayName}: ${dev.currentValue('networkStatus') ?: 'unknown'} (last reconnect: ${last})"
	}.join("<br>")
}

private Integer delaySeconds() { (settings.reconnectDelaySeconds ?: 60) as Integer }
private Integer retrySeconds() { ((settings.retryMinutes ?: 15) as Integer) * 60 }
private void logDebug(msg) { if (settings.logEnable) log.debug msg }
