import spock.lang.Specification
import support.AppHarness
import support.FakeDevice

class LockCodesSpec extends Specification {
    // Slots 1-2 are in use on the front door by codes set outside this app
    FakeDevice front = new FakeDevice(id: 287, displayName: "Front Door Lock", states: [lockCodes: '{"1":{"name":"David"},"2":{"name":"Savanna"}}'])
    FakeDevice garage = new FakeDevice(id: 284, displayName: "Garage Lock", states: [lockCodes: "{}"])
    FakeDevice david = new FakeDevice(id: 159, displayName: "David's SMS")
    FakeDevice savanna = new FakeDevice(id: 160, displayName: "Savanna's SMS")
    AppHarness hub

    static final long HOUR = 3600000L

    def setup() {
        hub = new AppHarness("lock-codes.groovy", [
            icsUrl: "https://calendar.example/private/basic.ics", pollMinutes: 5, locks: [front, garage], notifiers: [david, savanna], peopleCount: 3,
            personName_1: "Sharon", personCode_1: "1111", personLocks_1: ["287"], personWhen_1: "Calendar",
            personName_2: "Jacob", personCode_2: "2222", personLocks_2: ["287", "284"], personWhen_2: "Calendar",
            personName_3: "Chad", personCode_3: "3333", personLocks_3: ["287"], personWhen_3: "Always",
            logEnable: false])
    }

    // ── helpers ──
    private String utc(long ms) { def f = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'"); f.setTimeZone(TimeZone.getTimeZone("UTC")); f.format(new Date(ms)) }
    private String event(String uid, String summary, long start, long end, String extra = "") {
        "BEGIN:VEVENT\r\nUID:${uid}\r\nSUMMARY:${summary}\r\nDTSTART:${utc(start)}\r\nDTEND:${utc(end)}\r\n${extra}END:VEVENT\r\n"
    }
    private String ics(String... events) { "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.join("") + "END:VCALENDAR\r\n" }
    private def resp(String body, int status = 200) {
        new Expando(hasError: { -> status != 200 }, getStatus: { -> status }, getData: { -> body }, getErrorMessage: { -> status == 200 ? null : "HTTP ${status}" })
    }
    private void fetch(String body) { hub.script.icsHandler(resp(body), null) }
    private List texts() { david.calls.findAll { it[0] == "deviceNotification" }.collect { it[1] } }
    private List setCalls(FakeDevice lk) { lk.calls.findAll { it[0] == "setCode" } }
    private List delCalls(FakeDevice lk) { lk.calls.findAll { it[0] == "deleteCode" } }
    private void clearCalls() { [front, garage, david, savanna].each { it.calls.clear() } }

    // ── always-on people ──

    def "an Always person's code is set on install, without a text"() {
        when: hub.script.installed()
        then:
        setCalls(front).contains(["setCode", 5, "3333", "Chad"])
        texts().isEmpty()
        hub.isScheduled("poll")
    }

    // ── calendar ──

    def "an event naming people adds their codes on their locks and texts both of you"() {
        given: hub.script.installed(); clearCalls()
        long t = hub.clock
        when: fetch(ics(event("e1", "Sharon\\, Jacob", t - HOUR, t + 2 * HOUR)))
        then:
        setCalls(front).findAll { it[1] in [3, 4] }*.getAt(1) as Set == [3, 4] as Set
        setCalls(garage) == [["setCode", 4, "2222", "Jacob"]]
        texts().size() == 1
        texts()[0].startsWith("Lock codes on: Sharon (Front Door Lock); Jacob (Front Door Lock, Garage Lock). Until ")
        savanna.calls.size() == 1
        !texts()[0].contains("1111") && !texts()[0].contains("2222")
    }

    def "codes come off when the event ends, with a text"() {
        given: hub.script.installed()
        long t = hub.clock
        fetch(ics(event("e1", "Sharon", t - HOUR, t + HOUR)))
        clearCalls()
        when: hub.advance(3601); hub.script.applySchedule()
        then:
        delCalls(front) == [["deleteCode", 3]]
        texts() == ["Lock codes off: Sharon."]
    }

    def "the next start or end is scheduled between fetches"() {
        given: hub.script.installed()
        long t = hub.clock
        when: fetch(ics(event("e1", "Sharon", t + 90 * 60000L, t + 3 * HOUR)))
        then:
        hub.scheduled.findAll { it.method == "applySchedule" }.last().delay == 90 * 60
        setCalls(front).every { it[1] != 3 }
    }

    def "names match regardless of case and spacing"() {
        given: hub.script.installed(); clearCalls()
        long t = hub.clock
        when: fetch(ics(event("e1", "  sharon ", t - HOUR, t + HOUR)))
        then: setCalls(front) == [["setCode", 3, "1111", "Sharon"]]
    }

    def "overlapping events keep a code on until the last one ends, and text no extra on"() {
        given: hub.script.installed()
        long t = hub.clock
        fetch(ics(event("e1", "Sharon", t - HOUR, t + HOUR), event("e2", "Sharon", t + 30 * 60000L, t + 3 * HOUR)))
        clearCalls()
        when: hub.advance(3601); hub.script.applySchedule()
        then:
        delCalls(front).isEmpty()
        texts().isEmpty()
    }

    def "someone named in an event but not set up is texted once"() {
        given: hub.script.installed(); clearCalls()
        long t = hub.clock
        String cal = ics(event("e1", "Sharon, Bob", t - HOUR, t + HOUR))
        when: fetch(cal); fetch(cal)
        then:
        texts().count { it.contains("names Bob, who isn't set up") } == 1
        setCalls(front)*.getAt(1) == [3]
    }

    def "a calendar error keeps codes on and still ends them on time; it texts after an hour, and on recovery"() {
        given: hub.script.installed()
        long t = hub.clock
        String cal = ics(event("e1", "Sharon", t - HOUR, t + 2 * HOUR))
        fetch(cal); clearCalls()

        when: hub.script.icsHandler(resp("", 500), null)
        then:
        delCalls(front).isEmpty()
        texts().isEmpty()

        when: hub.advance(3600); hub.script.icsHandler(resp("", 500), null)
        then: texts().count { it.contains("couldn't be read for an hour") } == 1

        when: hub.advance(3601); hub.script.applySchedule()
        then: "the cached event still ends on time"
        delCalls(front) == [["deleteCode", 3]]

        when: fetch(ics())
        then: texts().last().contains("can be read again")
    }

    def "a page that isn't a calendar counts as an error and changes nothing"() {
        given: hub.script.installed()
        long t = hub.clock
        fetch(ics(event("e1", "Sharon", t - HOUR, t + HOUR))); clearCalls()
        when: fetch("<html>Sign in</html>")
        then:
        hub.state.calendarError.contains("didn't return a calendar")
        delCalls(front).isEmpty()
    }

    def "cancelled events are ignored"() {
        given: hub.script.installed(); clearCalls()
        long t = hub.clock
        when: fetch(ics(event("e1", "Sharon", t - HOUR, t + HOUR, "STATUS:CANCELLED\r\n")))
        then: setCalls(front).every { it[1] != 3 }
    }

    def "the Fetch now button reads the calendar before returning, so the page shows the result"() {
        given: hub.script.installed(); clearCalls()
        long t = hub.clock
        hub.httpGetResponder = { p -> [status: 200, data: new StringReader(ics(event("e1", "Sharon", t - HOUR, t + HOUR)))] }
        when: hub.script.appButtonHandler("fetchNow")
        then:
        hub.http.last().method == "GET-SYNC"
        hub.state.lastFetch == hub.clock
        setCalls(front).contains(["setCode", 3, "1111", "Sharon"])
    }

    def "a failed Fetch now is recorded like a scheduled failure"() {
        given: hub.script.installed(); hub.httpGetResponder = null
        when: hub.script.appButtonHandler("fetchNow")
        then: hub.state.calendarError == "no responder"
    }

    // ── settings changes ──

    def "removing a lock from a person whose code is on takes it off that lock"() {
        given: hub.script.installed()
        long t = hub.clock
        fetch(ics(event("e1", "Jacob", t - HOUR, t + HOUR))); clearCalls()
        when: hub.settings.personLocks_2 = ["287"]; hub.script.updated()
        then:
        delCalls(garage) == [["deleteCode", 4]]
        texts().isEmpty()
    }

    def "slots are assigned automatically, skipping positions the locks report in use, and kept"() {
        when: hub.script.installed()
        then:
        hub.state.slots == [p1: 3, p2: 4, p3: 5]

        when: "later reconciles keep the same slots"
        front.states.lockCodes = '{"1":{"name":"David"},"2":{"name":"Savanna"},"5":{"name":"Chad"}}'
        hub.script.updated()
        then: hub.state.slots == [p1: 3, p2: 4, p3: 5]
    }

    def "an Always person whose slot is taken on a newly added lock moves to a free slot"() {
        given: hub.script.installed(); clearCalls()
        garage.states.lockCodes = '{"5":{"name":"Someone"}}'
        when: hub.settings.personLocks_3 = ["287", "284"]; hub.script.updated()
        then:
        hub.state.slots.p3 == 6
        delCalls(front) == [["deleteCode", 5]]
        setCalls(front).contains(["setCode", 6, "3333", "Chad"])
        setCalls(garage).contains(["setCode", 6, "3333", "Chad"])
    }

    def "removing a slot setting left from an older version"() {
        given: hub.settings.personSlot_1 = 10
        when: hub.script.updated()
        then: !hub.settings.containsKey("personSlot_1")
    }

    def "removing a person removes their code"() {
        given: hub.script.installed(); clearCalls()
        when: hub.settings.peopleCount = 2; hub.script.updated()
        then:
        delCalls(front) == [["deleteCode", 5]]
        !hub.state.slots.containsKey("p3")
    }

    def "a failed code change on a lock is texted"() {
        when: hub.script.codeChangedHandler([value: "1 failed", displayName: "Front Door Lock", descriptionText: "code 1 failed"])
        then: texts() == ["Lock Codes: Front Door Lock couldn't save a code (code 1 failed)."]
    }

    // ── iCalendar parsing ──

    private List parse(String body, long from = hub.clock - 86400000L, long to = hub.clock + 14 * 86400000L) { hub.script.parseIcs(body, from, to) }
    private long chicago(String ymdhm) { def f = new java.text.SimpleDateFormat("yyyyMMddHHmm"); f.setTimeZone(TimeZone.getTimeZone("America/Chicago")); f.parse(ymdhm).time }

    def "TZID times and all-day events are read in their zones"() {
        given:
        String body = "BEGIN:VCALENDAR\r\n" +
            "BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Sharon\r\nDTSTART;TZID=America/Chicago:20261125T090000\r\nDTEND;TZID=America/Chicago:20261125T170000\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Jacob\r\nDTSTART;VALUE=DATE:20261126\r\nDTEND;VALUE=DATE:20261128\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        when: def evs = parse(body)
        then:
        evs[0].start == chicago("202611250900") && evs[0].end == chicago("202611251700")
        evs[1].start == chicago("202611260000") && evs[1].end == chicago("202611280000")
    }

    def "folded lines and escaped commas in titles are unfolded and unescaped"() {
        given: String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Sharon\\, Ja\r\n cob\r\nDTSTART:20261125T150000Z\r\nDTEND:20261125T160000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        expect: parse(body)[0].summary == "Sharon, Jacob"
    }

    def "a weekly recurring event expands on its days, skips EXDATEs and uses moved instances"() {
        given: "Mon/Wed 9-10am from Mon Nov 23; Wed Nov 25 excluded; Mon Nov 30 moved to 1pm"
        String body = "BEGIN:VCALENDAR\r\n" +
            "BEGIN:VEVENT\r\nUID:w\r\nSUMMARY:Sharon\r\nDTSTART;TZID=America/Chicago:20261123T090000\r\nDTEND;TZID=America/Chicago:20261123T100000\r\n" +
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6\r\nEXDATE;TZID=America/Chicago:20261125T090000\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:w\r\nSUMMARY:Sharon\r\nRECURRENCE-ID;TZID=America/Chicago:20261130T090000\r\nDTSTART;TZID=America/Chicago:20261130T130000\r\nDTEND;TZID=America/Chicago:20261130T140000\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        when: def starts = parse(body, chicago("202611220000"), chicago("202612310000"))*.start
        then:
        starts == [chicago("202611230900"), chicago("202611301300"), chicago("202612020900"),
                   chicago("202612070900"), chicago("202612090900")]
    }

    def "a daily event keeps its wall-clock time across the DST change"() {
        given: String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:d\r\nSUMMARY:Sharon\r\nDTSTART;TZID=America/Chicago:20270313T090000\r\nDTEND;TZID=America/Chicago:20270313T100000\r\nRRULE:FREQ=DAILY;UNTIL=20270315T235959Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        when: def starts = parse(body, chicago("202703120000"), chicago("202703200000"))*.start
        then: starts == [chicago("202703130900"), chicago("202703140900"), chicago("202703150900")]
    }

    def "DURATION is used when there is no DTEND"() {
        given: String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:Sharon\r\nDTSTART:20261125T150000Z\r\nDURATION:PT2H30M\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        when: def ev = parse(body)[0]
        then: ev.end - ev.start == (2 * 60 + 30) * 60000L
    }
}
