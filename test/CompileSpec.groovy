import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Unroll
import support.ImportPolicy

/**
 * Build check: every app and driver compiles inside hubitat_ci's emulated Hubitat sandbox
 * (disallowed classes, imports and methods are rejected) and its structure validates.
 * New .groovy files at the repo root are picked up automatically.
 *
 * hubitat_ci 0.17 (2021) predates several current Hubitat features, so these checks are
 * relaxed, each for a documented reason; everything else stays strict:
 *   DontValidateDefinition         - rejects singleThreaded and other current definition options
 *   DontValidatePreferences        - doesn't know input 'width', capability.airQuality, etc.
 *   DontRequireParseMethodInDevice - child drivers (FanSync Fan/Light, AirGradient) never get raw
 *                                    messages, and the hub doesn't require parse() for them
 *   DontRequireCapabilityImplementationMethods - its capability list is out of date
 *   DontValidateCapabilities / DontValidateAttributes - same; newer capabilities are unknown to it
 *   Drivers are compiled in the restricted sandbox but not run (DontRunScript): running executes
 *   metadata {}, which hubitat_ci validates with rules no flag disables and which reject current
 *   syntax (singleThreaded, described command arguments, capability "AirQuality").
 * hubitat_ci lets file and socket classes through, so ImportPolicy also checks every script's
 * imports and fully-qualified java.io/nio/net references against the hub's allowlist.
 * Platform calls newer than the harness (hub variables, runInMillis) are shimmed for apps.
 * @CompileStatic is stripped before compiling: the hub accepts it, but hubitat_ci's
 * script-rewriting customizers crash (NPE in class generation) on statically compiled methods.
 */
class CompileSpec extends Specification {
    static final List APP_FLAGS = [Flags.DontValidateDefinition, Flags.DontValidatePreferences,
                                   Flags.DontValidateSubscriptions]
    static final List DRIVER_FLAGS = [Flags.DontValidateDefinition, Flags.DontValidatePreferences,
                                      Flags.DontRequireParseMethodInDevice, Flags.DontRequireCapabilityImplementationMethods,
                                      Flags.DontValidateCapabilities, Flags.DontValidateAttributes,
                                      Flags.DontValidateMetadata, Flags.DontRunScript]

    // Calls the hub has but hubitat_ci 0.17 doesn't know; compile checks only need them to exist
    static final Closure SHIMS = { s ->
        s.metaClass.getGlobalVar = { String n -> null }
        s.metaClass.setGlobalVar = { String n, v -> false }
        s.metaClass.addInUseGlobalVar = { v -> true }
        s.metaClass.removeAllInUseGlobalVar = { -> true }
        s.metaClass.runInMillis = { long ms, String m, Map o = null -> }
    }

    static String harnessText(File f) { f.text.replaceAll(/@(groovy\.transform\.)?CompileStatic\b/, '') }

    static List<File> scripts() {
        new File('.').listFiles().findAll { it.name.endsWith('.groovy') }.sort { it.name }
    }
    static List<File> apps()    { scripts().findAll { it.text =~ /(?m)^definition\s*\(/ } }
    static List<File> drivers() { scripts().findAll { it.text =~ /(?m)^metadata\s*\{/ } }

    def "every script is either an app or a driver"() {
        expect:
        scripts().every { f -> apps().contains(f) || drivers().contains(f) }
    }

    @Unroll
    def "#file.name only uses classes the hub allows"() {
        expect:
        ImportPolicy.violations(file.text) == []

        where:
        file << scripts()
    }

    @Unroll
    def "app #file.name compiles and validates"() {
        expect:
        new HubitatAppSandbox(file).run(api: Stub(AppExecutor), validationFlags: APP_FLAGS, customizeScriptBeforeRun: SHIMS)

        where:
        file << apps()
    }

    @Unroll
    def "driver #file.name compiles and validates"() {
        expect:
        new HubitatDeviceSandbox(harnessText(file)).run(api: Stub(DeviceExecutor), validationFlags: DRIVER_FLAGS)

        where:
        file << drivers()
    }
}
