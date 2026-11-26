/**
 * Lock Codes — Hubitat App
 *
 * Manages lock codes per person, replacing the built-in Lock Code Manager for anyone whose
 * access follows a calendar (Lock Code Manager can't be switched on or off by another app).
 *
 * Locks: the app's one list of locks it manages; each person picks theirs from it. (An app can
 * only command devices in its inputs, so a lock dropped from a person's list can still have that
 * person's code removed.)
 *
 * People: each has a name, a code, the locks it works on, a code slot, and when it applies:
 *   - Always:   the code stays on those locks.
 *   - Calendar: the code is added when an event in the Lock Schedules calendar names the
 *               person, and removed when the last such event ends.
 *
 * Calendar: a Google Calendar read through its private iCal address (Settings → the calendar →
 * Integrate calendar → "Secret address in iCal format"). Event titles list people,
 * comma-separated, e.g. "Sharon, Jacob". Names match case-insensitively. Recurring events
 * (daily/weekly/monthly RRULEs with INTERVAL, COUNT, UNTIL, BYDAY, EXDATE and moved
 * instances) and all-day events are supported. The feed is fetched every pollMinutes; event
 * starts and ends are applied on time from the last good fetch, so a failed fetch never
 * removes or adds a code early. Cancelled events are ignored.
 *
 * Texts (notification devices): when calendar codes are added or removed, when an active
 * event names someone who isn't set up here, when a lock reports a failed code change, and
 * when the calendar can't be read for an hour (and when it recovers).
 *
 * Codes are entered in password inputs and are never logged or texted. state.applied records
 * which person's code is on which locks and slot (no codes), so changes to a person's locks or
 * slot move the code, and removing a person removes their code.
 */

definition(
	name: "Lock Codes",
	namespace: "djkoser",
	author: "David Koser",
	description: "Lock codes per person: always on, or on during Lock Schedules calendar events, with texts",
	category: "Security",
	iconUrl: "", iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/djkoser/hubitat/refs/heads/main/lock-codes.groovy",
	singleThreaded: true
)

preferences { page(name: "mainPage") }

@groovy.transform.Field static final int MAX_PEOPLE = 20
@groovy.transform.Field static final long DAY_MS = 86400000L
@groovy.transform.Field static final int LOOKAHEAD_DAYS = 14

private String small(String t) { "<br><small style='color:#666'>${t}</small>" }

def mainPage() {
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
		section("Calendar") {
			input "icsUrl", "password", title: "Lock Schedules iCal address" +
				small("Google Calendar → Settings → Lock Schedules → Integrate calendar → Secret address in iCal format. Event titles list people, comma-separated."), required: false
			input "pollMinutes", "number", title: "Fetch every (minutes)" + small("How often the calendar is re-read. Starts and ends are applied on time between fetches."), range: "1..60", defaultValue: 5
			if (app.id) input "fetchNow", "button", title: "Fetch calendar now"
		}
		section("Locks") {
			input "locks", "capability.lock", title: "Locks" + small("Every lock this app manages codes on. Each person picks theirs from these."), multiple: true, required: true, submitOnChange: true
		}
		section("Texts") {
			input "notifiers", "capability.notification", title: "Text" + small("Who is told when calendar codes are added or removed, and about problems."), multiple: true, required: fals, submitOnChange: true
		}
		section("People") {
			input "peopleCount", "number", title: "People" + small("How many people this app manages (1–${MAX_PEOPLE})."), range: "1..${MAX_PEOPLE}", defaultValue: 1, required: true, submitOnChange: true
		}
		(1..count()).each { int i ->
			section(settings["personName_${i}"] ?: "Person ${i}") {
				input "personName_${i}", "text", title: "Name" + small("As written in calendar event titles."), required: true, submitOnChange: true
				input "personCode_${i}", "password", title: "Code" + small("Digits only; must match the locks' code length."), required: true
				if (lockOptions()) input "personLocks_${i}", "enum", title: "Locks", options: lockOptions(), multiple: true, required: true
				else paragraph "<b>Locks:</b> choose the Locks above first."
				input "personWhen_${i}", "enum", title: "When", options: ["Calendar", "Always"], defaultValue: "Calendar", required: true
				input "personSlot_${i}", "number", title: "Code slot" + small("Lock memory position for this code (1–250). Slots 1–2 hold the codes Lock Code Manager set."), range: "1..250", defaultValue: 9 + i, required: true
				if (app.id) paragraph personStatus(i)
			}
		}
		if (app.id) section("Status") { paragraph statusText() }
		section("Logging") {
			input "logEnable", "bool", title: "Debug logging" + small("Turns itself off after 30 minutes."), defaultValue: false
		}
	}
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }
def uninstalled() { }

def initialize() {
	if (logEnable) runIn(1800, "logsOff")
	if (state.applied == null) state.applied = [:]
	if (state.events == null) state.events = []
	if (settings.locks) subscribe(settings.locks, "codeChanged", "codeChangedHandler")
	int m = Math.max(1, Math.min(60, ((settings.pollMinutes ?: 5) as Integer)))
	if (settings.icsUrl) schedule("0 */${m} * ? * *", "poll")
	reconcile(true)   // settings may have changed codes, locks or slots: re-apply everything
	if (settings.icsUrl) runIn(3, "poll")
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

def appButtonHandler(String btn) { if (btn == "fetchNow") poll() }

// ── People ───────────────────────────────────────────────────────────────────

private int count() { Math.max(1, Math.min(MAX_PEOPLE, ((settings.peopleCount ?: 1) as Integer))) }
private List<Integer> people() { (1..count()).findAll { settings["personName_${it}"] && settings["personCode_${it}"] && settings["personLocks_${it}"] } }
private String pname(int i) { settings["personName_${i}"].toString().trim() }
private String key(String name) { name.trim().toLowerCase() }
private boolean always(int i) { settings["personWhen_${i}"] == "Always" }
private int slot(int i) { ((settings["personSlot_${i}"] ?: (9 + i)) as Integer) }
private Map lockOptions() { ((settings.locks ?: []) as List).collectEntries { [(it.id.toString()): it.displayName] } }
private lockDev(def id) { ((settings.locks ?: []) as List).find { it.id.toString() == id.toString() } }
// A person's locks as devices (ids no longer in the Locks list are skipped)
private List personLocks(int i) { ((settings["personLocks_${i}"] ?: []) as List).collect { lockDev(it) }.findAll { it } }

// ── Calendar ─────────────────────────────────────────────────────────────────

def poll() {
	if (!settings.icsUrl) return
	asynchttpGet("icsHandler", [uri: settings.icsUrl.toString().trim().replaceFirst(/^webcal:/, "https:"),
								 contentType: "text/plain", textParser: true, timeout: 30])
}

def icsHandler(resp, data) {
	String err = null
	String body = null
	try {
		if (resp.hasError()) err = resp.getErrorMessage() ?: "HTTP ${resp.getStatus()}"
		else if (resp.getStatus() != 200) err = "HTTP ${resp.getStatus()}"
		else body = resp.getData()?.toString()
	} catch (e) { err = e.message }
	if (body != null && !body.contains("BEGIN:VCALENDAR")) err = "the address didn't return a calendar"
	if (err) { calendarFailed(err); return }

	long t = now()
	List evs
	try { evs = parseIcs(body, t - DAY_MS, t + LOOKAHEAD_DAYS * DAY_MS) }
	catch (e) { calendarFailed("couldn't read the calendar (${e.message})"); return }
	if (state.calendarError && state.calendarErrorTexted) notify("Lock Codes: the Lock Schedules calendar can be read again.")
	state.remove("calendarError"); state.remove("calendarErrorSince"); state.remove("calendarErrorTexted")
	state.lastFetch = t
	state.events = evs
	if (logEnable) log.debug "Lock Codes: ${evs.size()} event(s) in the next ${LOOKAHEAD_DAYS} days"
	reconcile(false)
}

private void calendarFailed(String err) {
	log.warn "Lock Codes: calendar fetch failed: ${err}"
	if (!state.calendarErrorSince) state.calendarErrorSince = now()
	state.calendarError = err
	if (!state.calendarErrorTexted && now() - (state.calendarErrorSince as Long) >= 3600000L) {
		notify("Lock Codes: the Lock Schedules calendar couldn't be read for an hour (${err}). Codes already on stay on until their events end; new events won't be picked up.")
		state.calendarErrorTexted = true
	}
	reconcile(false)   // cached events still start and end on time
}

// ── Applying codes ───────────────────────────────────────────────────────────

def applySchedule() { reconcile(false) }

// Brings the locks in line with who should have a code now. resend: also re-send codes that are
// already on (after a settings change, when a code itself may have changed).
private void reconcile(boolean resend) {
	long t = now()
	List active = ((state.events ?: []) as List).findAll { (it.start as Long) <= t && t < (it.end as Long) }
	Map activeUntil = [:]   // person key -> latest end among active events naming them
	Set named = [] as Set
	active.each { ev -> names(ev.summary).each { n -> named << n; activeUntil[key(n)] = Math.max((activeUntil[key(n)] ?: 0L) as Long, ev.end as Long) } }

	Map applied = (state.applied ?: [:]) as Map
	Set known = people().collect { key(pname(it)) } as Set
	List added = [], removed = []

	people().each { int i ->
		String pk = "p${i}"
		def locks = personLocks(i)
		boolean want = always(i) || activeUntil.containsKey(key(pname(i)))
		Map cur = applied[pk] as Map
		if (cur && (cur.name != pname(i) || (cur.slot as Integer) != slot(i))) {   // renamed or moved: clear the old entry
			clearCode(cur); applied.remove(pk); cur = null
		}
		if (want) {
			List curIds = ((cur?.locks ?: []) as List).collect { it as String }
			List newIds = locks.collect { it.id as String }
			((cur?.locks ?: []) as List).findAll { !newIds.contains(it as String) }.each { id -> deleteOn(id as String, slot(i)) }
			locks.each { lk -> if (resend || !curIds.contains(lk.id as String)) setOn(lk, i) }
			if (!cur && !always(i)) added << i
			applied[pk] = [name: pname(i), slot: slot(i), locks: newIds]
		} else if (cur) {
			clearCode(cur)
			applied.remove(pk)
			if (!always(i)) removed << cur.name
		}
	}
	// People removed from the app: take their codes off
	applied.keySet().findAll { pk -> !people().contains((pk.toString().substring(1)) as Integer) }.each { pk ->
		clearCode(applied[pk] as Map); applied.remove(pk)
	}
	state.applied = applied

	if (added) {
		def parts = added.collect { int i -> "${pname(i)} (${personLocks(i)*.displayName.join(', ')})" }
		long until = added.collect { activeUntil[key(pname(it))] as Long }.max()
		notify("Lock codes on: ${parts.join('; ')}. Until ${fmtTime(until)}.")
	}
	if (removed) notify("Lock codes off: ${removed.join(', ')}.")

	// Names in active events that aren't set up here: tell once per event
	Set warned = ((state.warned ?: []) as List) as Set
	active.each { ev ->
		def unknown = names(ev.summary).findAll { !known.contains(key(it)) }
		String wk = "${ev.uid}|${ev.start}"
		if (unknown && !warned.contains(wk)) {
			notify("Lock Schedules event \"${ev.summary}\" names ${unknown.join(', ')}, who ${unknown.size() == 1 ? "isn't" : "aren't"} set up in Lock Codes. No code was added for ${unknown.size() == 1 ? 'them' : 'those people'}.")
			warned << wk
		}
	}
	state.warned = warned.findAll { w -> active.any { "${it.uid}|${it.start}" == w } } as List

	scheduleNextBoundary()
}

private void setOn(lk, int i) {
	if (logEnable) log.debug "Lock Codes: setting ${pname(i)}'s code in slot ${slot(i)} on ${lk.displayName}"
	lk.setCode(slot(i), settings["personCode_${i}"].toString(), pname(i))
}

private void clearCode(Map cur) {
	((cur?.locks ?: []) as List).each { id -> deleteOn(id as String, cur.slot as Integer) }
}

private void deleteOn(String lockId, int slot) {
	def lk = lockDev(lockId)
	if (lk) lk.deleteCode(slot)
	else log.warn "Lock Codes: lock ${lockId} is no longer in the Locks list; its code in slot ${slot} must be removed by hand"
}

// Runs reconcile at the next event start or end (between fetches)
private void scheduleNextBoundary() {
	long t = now()
	def next = ((state.events ?: []) as List).collectMany { [it.start as Long, it.end as Long] }.findAll { it > t }.min()
	if (next != null) runIn(Math.max(1L, (long) Math.ceil((next - t) / 1000d)), "applySchedule", [overwrite: true])
}

def codeChangedHandler(evt) {
	if (evt.value?.toString()?.contains("failed")) {
		log.warn "Lock Codes: ${evt.displayName} reported a failed code change: ${evt.descriptionText ?: evt.value}"
		notify("Lock Codes: ${evt.displayName} couldn't save a code (${evt.descriptionText ?: evt.value}).")
	}
}

// ── iCalendar parsing ────────────────────────────────────────────────────────

// Comma-separated names from an event title (iCal escapes are already removed)
private List<String> names(def summary) {
	(summary ?: "").toString().split(",").collect { it.trim() }.findAll { it }
}

// Events overlapping [from, to): [uid, summary, start, end] (ms), recurring events expanded
List<Map> parseIcs(String text, long from, long to) {
	def lines = text.replaceAll(/\r?\n[ \t]/, "").split(/\r?\n/)
	List<Map> raw = []
	Map cur = null
	lines.each { String line ->
		if (line == "BEGIN:VEVENT") { cur = [exdates: []]; return }
		if (line == "END:VEVENT") { if (cur) raw << cur; cur = null; return }
		if (cur == null) return
		int c = line.indexOf(":")
		if (c < 0) return
		String head = line.substring(0, c), value = line.substring(c + 1)
		String name = head.tokenize(";")[0].toUpperCase()
		Map params = head.tokenize(";").drop(1).collectEntries { p -> def kv = p.split("=", 2); [(kv[0].toUpperCase()): kv.size() > 1 ? kv[1].replace('"', '') : ""] }
		switch (name) {
			case "UID": cur.uid = value; break
			case "SUMMARY": cur.summary = unescape(value); break
			case "STATUS": cur.status = value.toUpperCase(); break
			case "DTSTART": cur.start = icsTime(value, params); cur.allDay = isDate(value, params); cur.tz = params.TZID; break
			case "DTEND": cur.end = icsTime(value, params); break
			case "DURATION": cur.duration = duration(value); break
			case "RRULE": cur.rrule = value.tokenize(";").collectEntries { def kv = it.split("=", 2); [(kv[0].toUpperCase()): kv.size() > 1 ? kv[1] : ""] }; break
			case "EXDATE": value.split(",").each { cur.exdates << icsTime(it, params) }; break
			case "RECURRENCE-ID": cur.recurrenceId = icsTime(value, params); break
		}
	}
	// Moved/edited instances of a recurring event replace the generated one at RECURRENCE-ID
	Map<String, List> overridden = [:]
	raw.findAll { it.recurrenceId != null }.each { overridden.get(it.uid, []) << (it.recurrenceId as Long) }

	List<Map> out = []
	raw.each { ev ->
		if (ev.start == null || ev.status == "CANCELLED") return
		long len = ev.end != null ? (ev.end as Long) - (ev.start as Long) : (ev.duration != null ? ev.duration as Long : (ev.allDay ? DAY_MS : 0L))
		if (len <= 0) return
		List<Long> starts = (ev.rrule && ev.recurrenceId == null) ? expand(ev, from - len, to) : [ev.start as Long]
		starts.each { long s ->
			if (ev.recurrenceId == null && overridden[ev.uid]?.contains(s)) return
			if (ev.exdates.contains(s)) return
			if (s < to && s + len > from) out << [uid: ev.uid, summary: ev.summary, start: s, end: s + len]
		}
	}
	return out.sort { it.start }
}

// Occurrence starts of a recurring event up to `to` (keeps wall-clock time across DST)
private List<Long> expand(Map ev, long from, long to) {
	Map r = ev.rrule
	String freq = r.FREQ
	if (!(freq in ["DAILY", "WEEKLY", "MONTHLY"])) return [ev.start as Long]
	int interval = (r.INTERVAL ?: "1") as Integer
	Integer cnt = r.COUNT ? (r.COUNT as Integer) : null
	Long until = r.UNTIL ? icsTime(r.UNTIL, r.UNTIL.length() == 8 ? [VALUE: "DATE"] : [:]) : null
	TimeZone tz = ev.tz ? TimeZone.getTimeZone(ev.tz) : (location?.timeZone ?: TimeZone.getTimeZone("UTC"))
	def cal = Calendar.getInstance(tz)
	cal.setFirstDayOfWeek(Calendar.SUNDAY)
	cal.setTimeInMillis(ev.start as Long)
	Map dayNums = [SU: 1, MO: 2, TU: 3, WE: 4, TH: 5, FR: 6, SA: 7]
	List<Integer> byDay = r.BYDAY ? r.BYDAY.split(",").collect { dayNums[it.replaceAll(/[^A-Z]/, "")] }.findAll { it } : []

	List<Long> starts = []
	int made = 0
	for (int guard = 0; guard < 2000; guard++) {
		List<Long> batch = []
		if (freq == "WEEKLY" && byDay) {
			def wk = cal.clone()
			wk.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
			byDay.sort().each { d -> def x = wk.clone(); x.set(Calendar.DAY_OF_WEEK, d); if (x.getTimeInMillis() >= (ev.start as Long)) batch << x.getTimeInMillis() }
		} else batch << cal.getTimeInMillis()
		for (long s in batch.sort()) {
			if ((until != null && s > until) || (cnt != null && made >= cnt) || s >= to) return starts
			made++
			if (s >= from) starts << s
		}
		if (freq == "DAILY") cal.add(Calendar.DAY_OF_MONTH, interval)
		else if (freq == "WEEKLY") cal.add(Calendar.WEEK_OF_YEAR, interval)
		else cal.add(Calendar.MONTH, interval)
	}
	return starts
}

private boolean isDate(String v, Map p) { p.VALUE == "DATE" || v.trim().length() == 8 }

// DTSTART/DTEND/EXDATE value -> epoch ms. Date-only values are local midnight; "Z" is UTC;
// TZID uses that zone; floating times use the hub's zone.
private Long icsTime(String v, Map p) {
	v = v.trim()
	TimeZone tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
	if (isDate(v, p)) {
		def f = new java.text.SimpleDateFormat("yyyyMMdd"); f.setTimeZone(tz)
		return f.parse(v.substring(0, 8)).time
	}
	if (v.endsWith("Z")) { tz = TimeZone.getTimeZone("UTC"); v = v[0..-2] }
	else if (p.TZID) tz = TimeZone.getTimeZone(p.TZID)
	def f = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss"); f.setTimeZone(tz)
	return f.parse(v).time
}

// ISO 8601 duration (P1D, PT2H30M) -> ms
private Long duration(String v) {
	def m = v =~ /P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?/
	if (!m.find()) return null
	def n = { idx -> m.group(idx) ? (m.group(idx) as Long) : 0L }
	return ((n(1) * 7 + n(2)) * 86400L + n(3) * 3600L + n(4) * 60L + n(5)) * 1000L
}

private String unescape(String v) { v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\") }

// ── Helpers ──────────────────────────────────────────────────────────────────

private void notify(String msg) {
	log.info msg
	notifiers*.deviceNotification(msg)
}

private String fmtTime(Long ms) {
	if (ms == null) return "—"
	def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
	def d = new Date(ms)
	String day = d.format("yyyyMMdd", tz) == new Date(now()).format("yyyyMMdd", tz) ? "" : d.format("EEE MMM d ", tz)
	return day + d.format("h:mm a", tz)
}

private String personStatus(int i) {
	if (!people().contains(i)) return "<i>Incomplete: needs a name, code and locks.</i>"
	def a = (state.applied ?: [:])["p${i}"]
	String onOff = a ? "<b>On</b> (slot ${a.slot})" : "Off"
	if (always(i)) return "<b>Now:</b> ${onOff}, always"
	def next = ((state.events ?: []) as List).find { ev -> (ev.end as Long) > now() && names(ev.summary).any { key(it) == key(pname(i)) } }
	return "<b>Now:</b> ${onOff}" + (next ? "  <b>Next event:</b> ${fmtTime(next.start as Long)} – ${fmtTime(next.end as Long)}" : "  <b>Next event:</b> none in the next ${LOOKAHEAD_DAYS} days")
}

private String statusText() {
	def lines = []
	lines << "<b>Calendar:</b> " + (!settings.icsUrl ? "not set" :
		(state.calendarError ? "<span style='color:#c00'>can't be read: ${state.calendarError}</span>" :
		 (state.lastFetch ? "read ${fmtTime(state.lastFetch as Long)}" : "not read yet")))
	def upcoming = ((state.events ?: []) as List).findAll { (it.end as Long) > now() }.take(5)
	lines << "<b>Upcoming events:</b> " + (upcoming ? upcoming.collect { "${it.summary} (${fmtTime(it.start as Long)} – ${fmtTime(it.end as Long)})" }.join("; ") : "none")
	return lines.join("<br>")
}
