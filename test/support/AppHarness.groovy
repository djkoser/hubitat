package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags

/**
 * Loads an app script under hubitat_ci with a scripted hub around it: a controllable clock,
 * persistent state, captured schedules/logs/HTTP, and the platform calls hubitat_ci 0.17
 * lacks (hub variables, runInMillis). Use from a Spock spec:
 *
 *   def hub = new AppHarness("bathroom-humidity-fan.groovy", [fan: fanDevice, ...])
 *   hub.script.fanHandler(hub.event(fanDevice, "switch", "off", "physical"))
 */
class AppHarness {
    static final List FLAGS = [Flags.DontValidateDefinition, Flags.DontValidatePreferences,
                               Flags.DontValidateSubscriptions, Flags.AllowWritingToSettings,
                               Flags.AllowReadingNonInputSettings]

    long clock = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-11-24 12:00").time
    Map state = [:]
    Map settings
    Map<String, Object> globals = [:]           // hub variables: name -> value
    List<Map> scheduled = []                    // [method, delaySeconds/cron, options]
    List<String> logs = []                      // "level: message"
    List<Map> http = []                         // [method, callback, params, data]
    Closure httpGetResponder = null             // (params) -> [status: n, data: body]; null = throw
    List<List> subscriptions = []
    Map<String, FakeDevice> children = [:]      // child devices by DNI
    String childDriverError = null              // set to make addChildDevice throw (driver not installed)
    def script

    AppHarness(String file, Map settings, Map globals = [:]) {
        this.settings = new LinkedHashMap(settings)
        this.globals.putAll(globals)
        def h = this
        def log = [debug: { m -> h.logs << "debug: $m" }, info: { m -> h.logs << "info: $m" },
                   warn: { m -> h.logs << "warn: $m" }, error: { m -> h.logs << "error: $m" },
                   trace: { m -> h.logs << "trace: $m" }] as me.biocomp.hubitat_ci.api.common_api.Log
        def location = [getTimeZone: { TimeZone.getTimeZone("America/Chicago") },
                        getName: { "Home" }, getTemperatureScale: { "F" }] as me.biocomp.hubitat_ci.api.common_api.Location
        def app = [getId: { 1L }, getLabel: { "Test App" },
                   updateSetting: { String n, v -> h.settings[n] = (v instanceof Map ? v.value : v) },
                   removeSetting: { String n -> h.settings.remove(n) }] as me.biocomp.hubitat_ci.api.common_api.InstalledAppWrapper

        // Plain map coercion (not a Spock mock, which can't be created outside a spec class):
        // only the calls listed here exist; anything else throws, which surfaces in the test.
        Map impl = [
            getState         : { -> h.state },
            getLog           : { -> log },
            getLocation      : { -> location },
            getApp           : { -> app },
            now              : { -> h.clock },
            runIn            : { Object[] a -> h.scheduled << [method: a[1], delay: a[0], options: a.length > 2 ? a[2] : null] },
            runEvery1Minute  : { Object[] a -> h.scheduled << [method: a[0], delay: "every1Minute"] },
            runEvery1Hour    : { Object[] a -> h.scheduled << [method: a[0], delay: "every1Hour"] },
            schedule         : { Object[] a -> h.scheduled << [method: a[1], delay: a[0]] },
            unschedule       : { Object[] a -> if (a) h.scheduled.removeAll { it.method == a[0] } else h.scheduled.clear() },
            subscribe        : { Object[] a -> h.subscriptions << (a as List) },
            unsubscribe      : { Object[] a -> },
            parseJson        : { String t -> new groovy.json.JsonSlurper().parseText(t) },
            httpGet          : { Object[] a ->      // sync: (params, closure)
                h.http << [method: "GET-SYNC", params: a[0]]
                if (!h.httpGetResponder) throw new RuntimeException("no responder")
                Map r = h.httpGetResponder.call(a[0]) as Map
                a[1].call(new Expando(status: r.status, data: r.data))
            },
            asynchttpPost    : { Object[] a -> h.http << [method: "POST", callback: a[0], params: a[1], data: a.length > 2 ? a[2] : null] },
            asynchttpGet     : { Object[] a -> h.http << [method: "GET", callback: a[0], params: a[1], data: a.length > 2 ? a[2] : null] },
            // Child devices are FakeDevices (in h.children) behind a ChildDeviceWrapper facade
            getChildDevice   : { String dni -> h.children[dni] ? h.childWrapper(h.children[dni]) : null },
            addChildDevice   : { Object[] a ->   // (namespace, typeName, dni, props)
                if (h.childDriverError) throw new IllegalArgumentException(h.childDriverError)
                def props = a.length > 3 ? a[3] : [:]
                h.children[a[2]] = new FakeDevice(id: 900L + h.children.size(), displayName: props?.label ?: a[1])
                h.childWrapper(h.children[a[2]])
            },
        ]
        AppExecutor api = impl as AppExecutor

        script = new HubitatAppSandbox(new File(file)).run(api: api, userSettingValues: this.settings,
            validationFlags: FLAGS,
            customizeScriptBeforeRun: { s ->
                // Platform calls newer than hubitat_ci 0.17
                s.metaClass.getGlobalVar = { String n -> h.globals.containsKey(n) ? [name: n, value: h.globals[n]] : null }
                s.metaClass.setGlobalVar = { String n, v -> if (h.globals.containsKey(n)) { h.globals[n] = v; true } else false }
                s.metaClass.addInUseGlobalVar = { v -> true }
                s.metaClass.removeAllInUseGlobalVar = { -> true }
                s.metaClass.runInMillis = { long ms, String m, Map o = null -> h.scheduled << [method: m, delay: ms / 1000.0] }
                s.metaClass.getSettings = { -> h.settings }
            })
    }

    /** A Hubitat Event-like object for handler calls */
    Map event(FakeDevice dev, String name, String value, String type = null) {
        dev.states[name] = value
        [name: name, value: value, displayName: dev.displayName, deviceId: dev.id, device: dev, type: type]
    }

    def childWrapper(FakeDevice d) {
        [currentValue: { Object[] a -> d.currentValue(a[0] as String) },
         sendEvent   : { Map e -> d.sendEvent(e) },
         getId       : { -> d.id },
         getLabel    : { -> d.displayName },
         setLabel    : { String l -> d.displayName = l }] as me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
    }

    void advance(long seconds) { clock += seconds * 1000L }
    boolean isScheduled(String method) { scheduled.any { it.method == method } }
    List<String> logsMatching(String text) { logs.findAll { it.contains(text) } }
}
