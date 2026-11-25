package support

import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags

/**
 * Loads a driver script under hubitat_ci with a scripted device around it: sendEvent() is
 * captured and becomes the device's current state, data values are a map, the parent is
 * any object you pass (e.g. a map of closures), HTTP and schedules are recorded.
 * The script body (metadata {}) isn't run: hubitat_ci's metadata rules predate current
 * Hubitat syntax (see CompileSpec), but every method is callable.
 */
class DeviceHarness {
    static final List FLAGS = [Flags.DontValidateDefinition, Flags.DontValidatePreferences,
                               Flags.DontRequireParseMethodInDevice, Flags.DontRequireCapabilityImplementationMethods,
                               Flags.DontValidateCapabilities, Flags.DontValidateAttributes,
                               Flags.DontValidateMetadata, Flags.DontRunScript]

    long clock = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-11-24 12:00").time
    Map state = [:]
    Map settings
    Map<String, String> data = [:]
    List<Map> events = []
    Map<String, Object> current = [:]
    List<Map> http = []
    List<Map> scheduled = []
    List<String> logs = []
    def parent
    def script

    DeviceHarness(String file, Map settings = [:], Map data = [:], def parent = null) {
        this.settings = new LinkedHashMap(settings)
        this.data.putAll(data)
        this.parent = parent
        def h = this
        def log = [debug: { m -> h.logs << "debug: $m" }, info: { m -> h.logs << "info: $m" },
                   warn: { m -> h.logs << "warn: $m" }, error: { m -> h.logs << "error: $m" },
                   trace: { m -> h.logs << "trace: $m" }] as me.biocomp.hubitat_ci.api.common_api.Log
        def location = [getTimeZone: { TimeZone.getTimeZone("America/Chicago") },
                        getTemperatureScale: { "F" }] as me.biocomp.hubitat_ci.api.common_api.Location
        def device = [getDisplayName: { "Test Device" }, getLabel: { "Test Device" }, getName: { "Test Device" },
                      getId: { 1L }, getDeviceNetworkId: { "test-dni" },
                      currentValue: { Object[] a -> h.current[a[0]] },
                      getDataValue: { String k -> h.data[k] },
                      updateDataValue: { String k, String v -> h.data[k] = v },
                      removeDataValue: { String k -> h.data.remove(k) },
                      updateSetting: { String n, v -> h.settings[n] = (v instanceof Map ? v.value : v) }
                     ] as me.biocomp.hubitat_ci.api.common_api.DeviceWrapper
        Map impl = [
            getState       : { -> h.state },
            getLog         : { -> log },
            getLocation    : { -> location },
            getDevice      : { -> device },
            getParent      : { -> h.parent },
            now            : { -> h.clock },
            sendEvent      : { Map m -> h.events << m; h.current[m.name] = m.value },
            getDataValue   : { String k -> h.data[k] },
            updateDataValue: { String k, String v -> h.data[k] = v },
            removeDataValue: { String k -> h.data.remove(k) },
            runIn          : { Object[] a -> h.scheduled << [method: a[1], delay: a[0]] },
            unschedule     : { Object[] a -> if (a) h.scheduled.removeAll { it.method == a[0] } else h.scheduled.clear() },
            asynchttpGet   : { Object[] a -> h.http << [method: "GET", callback: a[0], params: a[1], data: a.length > 2 ? a[2] : null] },
            asynchttpPut   : { Object[] a -> h.http << [method: "PUT", callback: a[0], params: a[1], data: a.length > 2 ? a[2] : null] },
            asynchttpPost  : { Object[] a -> h.http << [method: "POST", callback: a[0], params: a[1], data: a.length > 2 ? a[2] : null] },
        ]
        script = new HubitatDeviceSandbox(new File(file).text.replaceAll(/@(groovy\.transform\.)?CompileStatic\b/, ''))
            .run(api: impl as DeviceExecutor, userSettingValues: this.settings, validationFlags: FLAGS)
    }

    /** Value of the last event with this name */
    def last(String name) { events.reverse().find { it.name == name }?.value }
    List<String> logsMatching(String text) { logs.findAll { it.contains(text) } }

    /** An asynchttp response stub */
    static Map response(int status, Object json = null, String text = null) {
        [getStatus: { -> status }, status: status, hasError: { -> status >= 400 },
         getErrorMessage: { -> "HTTP $status" }, getJson: { -> json }, json: json,
         getData: { -> text ?: groovy.json.JsonOutput.toJson(json) }]
    }
}
