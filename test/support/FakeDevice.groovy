package support

/**
 * Minimal stand-in for a Hubitat DeviceWrapper in app tests.
 * - currentValue()/hasAttribute() read `states`
 * - every command call (on, off, setLEDMode, setSpotlight, ...) is recorded in `calls`;
 *   on/off/open/close also update the matching state, like a well-behaved driver
 * - `commands` limits which commands hasCommand() reports (null = all)
 */
class FakeDevice {
    Long id
    String displayName
    Map states = [:]
    Set<String> commands = null
    Date lastActivity = new Date()
    List<List> calls = []

    def currentValue(String attr) { states[attr] }
    def currentValue(String attr, boolean skipCache) { states[attr] }
    boolean hasAttribute(String attr) { states.containsKey(attr) }
    boolean hasCommand(String cmd) { commands == null || commands.contains(cmd) }
    String getLabel() { displayName }
    String getName() { displayName }
    Date getLastActivity() { lastActivity }

    def methodMissing(String name, def args) {
        List a = args as List
        calls << ([name] + a)
        switch (name) {
            case "on": case "off": states.switch = name; break
            case "open": states.valve = "open"; break
            case "close": states.valve = "closed"; break
        }
        return null
    }

    /** Commands called so far, by name */
    List<String> commandNames() { calls.collect { it[0] as String } }
    String toString() { "FakeDevice(${displayName})" }
}
