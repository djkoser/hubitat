import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Unroll
import support.ImportPolicy

/** Proves the build check can fail: each broken driver must be rejected by the sandbox. */
class SandboxSelfTestSpec extends Specification {
    static final String META = 'metadata { definition(name: "T", namespace: "t", author: "t") { capability "Switch" } }\ndef on() {}\ndef off() {}\n'

    @Unroll
    def "sandbox rejects #what"() {
        when:
        new HubitatDeviceSandbox(META + code).run(api: Stub(DeviceExecutor), validationFlags: CompileSpec.DRIVER_FLAGS)

        then:
        thrown(Throwable)

        where:
        what                    | code
        "a syntax error"        | "def broken( {"
        "Thread.sleep"          | "def f() { Thread.sleep(10) }"
        "System.exit"           | "def f() { System.exit(0) }"
    }

    @Unroll
    def "import policy rejects #code"() {
        expect:
        ImportPolicy.violations(code) == [cls]

        where:
        code                                      | cls
        "import java.io.File"                     | "java.io.File"
        "import java.net.Socket"                  | "java.net.Socket"
        "def f() { new java.io.File('x') }"       | "java.io.File"
        "import java.nio.file.Files"              | "java.nio.file.Files"
    }

    def "import policy accepts allowed classes and ignores comments"() {
        expect:
        ImportPolicy.violations("""import groovy.json.JsonOutput
import java.time.LocalDate
import com.hubitat.zigbee.DataType
// new java.io.File("only a comment")
def f() { java.net.URLEncoder.encode('a', 'UTF-8') }""") == []
    }

    def "sandbox accepts a valid driver"() {
        expect:
        new HubitatDeviceSandbox(META).run(api: Stub(DeviceExecutor), validationFlags: CompileSpec.DRIVER_FLAGS)
    }
}
