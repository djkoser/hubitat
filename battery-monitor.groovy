definition(
	name: "Enhanced Battery Monitor",
	namespace: "djkoser",
	author: "David Koser",
	description: "Real-time battery monitor that sends notifications when devices cross battery thresholds (very high to high, high to medium, medium to low, low to critical). Notifications are only sent once per device for each threshold crossing.",
	installOnOpen: true,
	category: "Convenience",
	iconUrl: "",
	iconX2Url: ""
)

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: "Battery Monitor", uninstall: true, install: true) {
		section("Device Selection") {
			input "batteryDevices", "capability.battery", title: "Select Battery Devices to Monitor", submitOnChange: true, multiple: true, required: true
			if(batteryDevices) {
				paragraph "Currently monitoring ${batteryDevices.size()} device(s) with battery capability"
			}
		}
		
		section("Battery Level Thresholds") {
			input "highThreshold", "number", title: "High Battery Threshold (%)", defaultValue: 75, range: "51..100", required: true, width: 3, submitOnChange: true
			input "mediumThreshold", "number", title: "Medium Battery Threshold (%)", defaultValue: 50, range: "26..74", required: true, width: 3, submitOnChange: true
			input "lowThreshold", "number", title: "Low Battery Threshold (%)", defaultValue: 25, range: "6..49", required: true, width: 3, submitOnChange: true
			input "criticalThreshold", "number", title: "Critical Battery Threshold (%)", defaultValue: 5, range: "1..24", required: true, width: 3, submitOnChange: true
			try {
				def config = getThresholdConfiguration()
				paragraph "<b>Battery Classification Ranges:</b><br>" +
					"🔋 Very High: ${config.veryHighRange}<br>" +
					"🟢 High: ${config.highRange}<br>" +
					"🟡 Medium: ${config.mediumRange}<br>" +
					"🟠 Low: ${config.lowRange}<br>" +
					"🔴 Critical: ${config.criticalRange}"
			} catch (Exception e) {
				paragraph "<b>Battery Classification Ranges:</b><br>" +
					"🔋 Very High: >75%<br>" +
					"🟢 High: 51-75%<br>" +
					"🟡 Medium: 26-50%<br>" +
					"🟠 Low: 6-25%<br>" +
					"🔴 Critical: ≤5%"
			}
		}
		
		section("Notification Settings") {
			input "notificationDevices", "capability.notification", title: "Select notification devices", submitOnChange: true, multiple: true, required: true
			if(notificationDevices) {
				paragraph "Notifications will be sent to ${notificationDevices.size()} device(s): ${notificationDevices.collect{it.displayName}.join(', ')}"
			}
			input "enableDebug", "bool", title: "Enable debug logging?", defaultValue: false
		}
		
		section("Current Status") {
			if(batteryDevices) {
				input "checkNow", "button", title: "Check Battery Levels Now"
				if(state.lastCheck) {
					paragraph "Last check: ${new Date(state.lastCheck).format('MM/dd/yyyy h:mm a')}"
					if(state.lastReport) {
						paragraph "<b>Last Report:</b><br>${state.lastReport}"
					}
				}
				try {
					paragraph formatBatteryStatusForUI()
				} catch (Exception e) {
					paragraph "<b>Current Battery Status:</b><br>Error loading status. Please configure thresholds and try again."
					if(enableDebug) log.debug "Error in formatBatteryStatusForUI: ${e.message}"
				}
			}
		}
	}
}

/**
 * HUBITAT LIFECYCLE HOOK: updated()
 * 
 * Purpose: Called automatically by Hubitat when the app configuration is saved/updated
 * Lifecycle: Triggered every time user clicks "Done" or saves app settings
 * 
 * Responsibilities:
 * - Clean up existing device subscriptions to prevent duplicates
 * - Reinitialize the app with new settings
 * - Ensure app starts fresh with current configuration
 * 
 * Note: This is one of the most important hooks as it handles configuration changes
 */
def updated() {
	unsubscribe() // Remove all existing device event subscriptions
	if(enableDebug) log.debug "Battery Monitor updated - reinitializing"
	initialize()  // Restart the app with new settings
}

/**
 * HUBITAT LIFECYCLE HOOK: installed()
 * 
 * Purpose: Called automatically by Hubitat when the app is first installed
 * Lifecycle: Triggered only once when user first installs the app
 * 
 * Responsibilities:
 * - Perform initial setup and configuration
 * - Initialize app state variables
 * - Set up initial device tracking
 * - Start the app for the first time
 * 
 * Note: This runs before updated() on first install
 */
def installed() {
	if(enableDebug) log.debug "Battery Monitor installed"
	// Initialize device states for tracking threshold crossings
	initializeDeviceStates()
	initialize()
}

/**
 * HUBITAT LIFECYCLE HOOK: uninstalled()
 * 
 * Purpose: Called automatically by Hubitat when the app is being removed
 * Lifecycle: Triggered when user deletes/uninstalls the app
 * 
 * Responsibilities:
 * - Remove all device subscriptions
 * - Perform any necessary cleanup before app removal
 * - Log uninstall for debugging purposes
 * 
 * Note: This is the last chance to clean up before app is permanently removed
 */
def uninstalled() {
	unsubscribe() // Remove all device event subscriptions
	if(enableDebug) log.debug "Battery Monitor uninstalled"
}

/**
 * CUSTOM INITIALIZATION METHOD: initialize()
 * 
 * Purpose: Common initialization function called by both installed() and updated() hooks
 * Lifecycle: Called during app installation and every time settings are updated
 * 
 * Responsibilities:
 * - Set up device event subscriptions for real-time battery monitoring
 * - Initialize or refresh app state and device tracking
 * - Validate configuration and handle errors gracefully
 * 
 * Design Pattern: This centralizes initialization logic to avoid duplication
 * between installed() and updated() lifecycle hooks
 * 
 * Note: Always called after unsubscribe() to ensure clean state
 */
void initialize() {
	// Subscribe to battery events from selected devices
	if(batteryDevices) {
		subscribe(batteryDevices, "battery", batteryHandler)
		if(enableDebug) log.debug "Subscribed to battery events for ${batteryDevices.size()} devices"
	}
	
	// Initialize or update device states
	initializeDeviceStates()
}

void initializeDeviceStates() {
	if(!state.deviceStates) state.deviceStates = [:]
	if(!state.lastNotifications) state.lastNotifications = [:]
	
	batteryDevices?.each { device ->
		try {
			def deviceId = device.id.toString()
			def currentBattery = device.currentBattery ?: 0
			def currentLevel = getBatteryLevel(currentBattery as Integer)
			
			if(!state.deviceStates[deviceId]) {
				state.deviceStates[deviceId] = [
					lastLevel: currentLevel,
					lastBattery: currentBattery,
					lastUpdate: now()
				]
				if(enableDebug) log.debug "Initialized state for ${device.displayName}: ${currentBattery}% (${currentLevel})"
			}
		} catch (Exception e) {
			log.warn "Error initializing device state for ${device?.displayName}: ${e.message}"
		}
	}
}

/**
 * HUBITAT EVENT HANDLER: batteryHandler(evt)
 * 
 * Purpose: Automatically called by Hubitat when subscribed battery devices report battery changes
 * Lifecycle: Triggered by device events based on subscribe() calls in initialize()
 * 
 * Parameters:
 * - evt: Event object containing device, value, name, and other event data
 * 
 * Responsibilities:
 * - Process incoming battery level changes from monitored devices
 * - Track device state transitions (very high/high/medium/low/critical levels)
 * - Detect threshold crossings and trigger notifications
 * - Update persistent state tracking for each device
 * 
 * Note: This is the core real-time monitoring function that responds to device updates
 */
void batteryHandler(evt) {
	def deviceId = evt.device.id.toString()
	def newBattery = evt.value as Integer
	def newLevel = getBatteryLevel(newBattery)
	
	// Get previous state BEFORE updating it
	def previousState = state.deviceStates[deviceId]
	def previousLevel = previousState?.lastLevel ?: "unknown"
	def previousBattery = previousState?.lastBattery ?: 0
	
	// Check for threshold crossing BEFORE updating state
	if(previousLevel != "unknown" && previousLevel != newLevel) {
		checkThresholdCrossing(evt.device, previousLevel, newLevel, newBattery, previousBattery)
	}
	
	// Update device state AFTER checking for crossings
	state.deviceStates[deviceId] = [
		lastLevel: newLevel,
		lastBattery: newBattery,
		lastUpdate: now()
	]
}

/**
 * HUBITAT BUTTON HANDLER: appButtonHandler(String buttonName)
 * 
 * Purpose: Called automatically by Hubitat when user clicks buttons in the app interface
 * Lifecycle: Triggered by user interaction with input "button" elements
 * 
 * Parameters:
 * - buttonName: String name of the button that was clicked (from input definition)
 * 
 * Responsibilities:
 * - Process user-initiated actions from the app interface
 * - Handle manual battery checks, resets, and other user commands
 * - Provide immediate feedback for user interactions
 * 
 * Note: Button names must match the input names defined in preferences pages
 */
void appButtonHandler(String buttonName) {
		switch(buttonName) {
		case "checkNow":
			performBatteryCheck(false)
			break
		default:
			if(enableDebug) log.debug "Unknown button: ${buttonName}"
	}
}

// Core battery monitoring functions

/**
 * Centralized threshold configuration and range calculation
 * Returns a map with parsed threshold values and formatted ranges
 */
Map getThresholdConfiguration() {
	// Parse threshold values with defaults
	def highVal = (highThreshold ?: 75) as Integer
	def mediumVal = (mediumThreshold ?: 50) as Integer  
	def lowVal = (lowThreshold ?: 25) as Integer
	def criticalVal = (criticalThreshold ?: 5) as Integer
	
	return [
		highVal: highVal,
		mediumVal: mediumVal,
		lowVal: lowVal,
		criticalVal: criticalVal,
		veryHighRange: ">${highVal}%",
		highRange: "${mediumVal+1}-${highVal}%",
		mediumRange: "${lowVal+1}-${mediumVal}%",
		lowRange: "${criticalVal+1}-${lowVal}%",
		criticalRange: "≤${criticalVal}%"
	]
}

String getBatteryLevel(Integer batteryPercent) {
	def config = getThresholdConfiguration()
	
	// Centralized 5-tier classification logic:
	// CRITICAL: <= criticalVal (≤5%)
	// LOW: > criticalVal AND <= lowVal (6-25%)  
	// MEDIUM: > lowVal AND <= mediumVal (26-50%)
	// HIGH: > mediumVal AND <= highVal (51-75%)
	// VERY HIGH: > highVal (76%+)
	String result
	if(batteryPercent <= config.criticalVal) {
		result = "critical"
	} else if(batteryPercent <= config.lowVal) {
		result = "low"
	} else if(batteryPercent <= config.mediumVal) {
		result = "medium"
	} else if(batteryPercent <= config.highVal) {
		result = "high"
	} else {
		result = "very_high"
	}
	
	return result
}

/**
 * Converts string battery level to numerical value for comparison
 */
Integer getBatteryLevelValue(String level) {
	switch(level) {
		case "critical": return 1
		case "low": return 2
		case "medium": return 3
		case "high": return 4
		case "very_high": return 5
		default: return 0 // unknown
	}
}

/**
 * Returns crossing direction: 1 for upward, -1 for downward, 0 for no change
 */
Integer getCrossingDirection(String previousLevel, String newLevel) {
	def previousValue = getBatteryLevelValue(previousLevel)
	def newValue = getBatteryLevelValue(newLevel)
	
	if(newValue > previousValue) return 1      // upward
	if(newValue < previousValue) return -1     // downward
	return 0                                   // no change
}

/**
 * Resets notification flags for levels below current level when battery improves
 */
void resetNotificationFlagsBelowCurrent(String deviceId, String deviceName, String newLevel) {
	def newLevelValue = getBatteryLevelValue(newLevel)
	def levelsToReset = []
	
	// Reset notifications for all levels below the current level
	// Include "high" and "very_high" levels to allow re-notification after battery replacement
	["critical": 1, "low": 2, "medium": 3, "high": 4, "very_high": 5].each { level, value ->
		def key = "${deviceId}_${level}"
		if(newLevelValue > value && state.lastNotifications.containsKey(key)) {
			state.lastNotifications.remove(key)
			levelsToReset << level
		}
	}
	
	if(levelsToReset && enableDebug) {
		log.debug "Reset notification flags for ${deviceName}: ${levelsToReset.join(', ')}"
	}
}

/**
 * Resets ALL notification flags for a device (used for battery replacement)
 */
void resetAllNotificationFlags(String deviceId, String deviceName) {
	def levelsToReset = []
	
	// Ensure deviceId is a string
	def deviceIdStr = deviceId.toString()
	
	// Use a more direct approach - iterate through all keys and remove matching ones
	def keysToRemove = []
	state.lastNotifications.each { key, value ->
		if(key.startsWith(deviceIdStr + "_")) {
			keysToRemove << key
		}
	}
	
	keysToRemove.each { key ->
		state.lastNotifications.remove(key)
		def level = key.split('_')[1]
		levelsToReset << level
	}
	
	if(enableDebug) {
		log.debug "Reset ALL notification flags for ${deviceName} (battery replacement): ${levelsToReset.join(', ') ?: 'none cleared'}"
	}
}

/**
 * Classifies all battery devices into their respective levels
 * Returns a map with arrays for each level and threshold values
 */
Map classifyBatteryDevices() {
	def veryHighDevices = []
	def highDevices = []
	def mediumDevices = []
	def lowDevices = []
	def criticalDevices = []
	def unknownDevices = []
	
	// Get centralized threshold configuration
	def config = getThresholdConfiguration()
	
	// Classify all devices
	batteryDevices?.each { device ->
		try {
			def batteryLevel = device.currentBattery
			if(batteryLevel != null) {
				def level = getBatteryLevel(batteryLevel as Integer)
				def deviceInfo = "${device.displayName}: ${batteryLevel}%"
				
				switch(level) {
					case "very_high":
						veryHighDevices << deviceInfo
						break
					case "high":
						highDevices << deviceInfo
						break
					case "medium":
						mediumDevices << deviceInfo
						break
					case "low":
						lowDevices << deviceInfo
						break
					case "critical":
						criticalDevices << deviceInfo
						break
				}
			} else {
				unknownDevices << "${device.displayName}: No battery data"
			}
		} catch (Exception e) {
			unknownDevices << "${device.displayName}: Error reading battery"
			if(enableDebug) log.debug "Error reading battery for ${device?.displayName}: ${e.message}"
		}
	}
	
	return [
		veryHighDevices: veryHighDevices,
		highDevices: highDevices,
		mediumDevices: mediumDevices,
		lowDevices: lowDevices,
		criticalDevices: criticalDevices,
		unknownDevices: unknownDevices,
		// Include threshold configuration for display functions
		config: config
	]
}

/**
 * Formats a single battery level status line
 */
String formatBatteryLevelLine(String icon, String label, String range, Integer count) {
	return "${icon} ${label} (${range}): ${count}"
}

/**
 * Builds detailed battery report with device lists
 */
List buildDetailedBatteryReport(Map deviceClassification) {
	def reportLines = []
	def veryHighDevices = deviceClassification.veryHighDevices
	def highDevices = deviceClassification.highDevices
	def mediumDevices = deviceClassification.mediumDevices
	def lowDevices = deviceClassification.lowDevices
	def criticalDevices = deviceClassification.criticalDevices
	def unknownDevices = deviceClassification.unknownDevices
	def config = deviceClassification.config
	
	if(criticalDevices) {
		reportLines << formatBatteryLevelLine("🔴", "CRITICAL", config.criticalRange, criticalDevices.size())
		criticalDevices.each { reportLines << "  • ${it}" }
	}
	
	if(lowDevices) {
		reportLines << formatBatteryLevelLine("🟠", "LOW", config.lowRange, lowDevices.size())
		lowDevices.each { reportLines << "  • ${it}" }
	}
	
	if(mediumDevices) {
		reportLines << formatBatteryLevelLine("🟡", "MEDIUM", config.mediumRange, mediumDevices.size())
		mediumDevices.each { reportLines << "  • ${it}" }
	}
	
	if(highDevices) {
		reportLines << formatBatteryLevelLine("🟢", "HIGH", config.highRange, highDevices.size())
		highDevices.each { reportLines << "  • ${it}" }
	}
	
	if(veryHighDevices) {
		reportLines << formatBatteryLevelLine("🔋", "VERY HIGH", config.veryHighRange, veryHighDevices.size())
		veryHighDevices.each { reportLines << "  • ${it}" }
	}
	
	if(unknownDevices) {
		reportLines << formatBatteryLevelLine("❓", "UNKNOWN", "", unknownDevices.size())
		unknownDevices.each { reportLines << "  • ${it}" }
	}
	
	return reportLines
}

/**
 * Builds compact single-line battery report
 */
String buildCompactBatteryReport(Map deviceClassification) {
	def config = deviceClassification.config
	
	def veryHighCount = deviceClassification.veryHighDevices.size()
	def highCount = deviceClassification.highDevices.size()
	def mediumCount = deviceClassification.mediumDevices.size()
	def lowCount = deviceClassification.lowDevices.size()
	def criticalCount = deviceClassification.criticalDevices.size()
	def unknownCount = deviceClassification.unknownDevices.size()
	
	def statusParts = []
	statusParts << formatBatteryLevelLine("🔋", "Very High", config.veryHighRange, veryHighCount)
	statusParts << formatBatteryLevelLine("🟢", "High", config.highRange, highCount)
	statusParts << formatBatteryLevelLine("🟡", "Medium", config.mediumRange, mediumCount)
	statusParts << formatBatteryLevelLine("🟠", "Low", config.lowRange, lowCount)
	statusParts << formatBatteryLevelLine("🔴", "Critical", config.criticalRange, criticalCount)
	
	if(unknownCount > 0) {
		statusParts << formatBatteryLevelLine("❓", "Unknown", "", unknownCount)
	}
	
	return statusParts.join(" | ")
}

/**
 * Builds notification header message for threshold crossings and battery replacements
 */
String buildNotificationHeader(String deviceName, String level, Integer batteryPercent, String crossingType, Boolean isReplacement) {
	if(isReplacement) {
		return "🔋 Battery Replaced: ${deviceName} battery has been replaced (${batteryPercent}%) - ${crossingType}"
	} else {
		return "🔋 Battery Alert: ${deviceName} has crossed to ${level} battery level (${batteryPercent}%) - ${crossingType}"
	}
}

/**
 * Handles upward battery level crossings, including high-level notifications
 */
void handleUpwardCrossing(String deviceId, String deviceName, String previousLevel, String newLevel, Integer batteryPercent) {
	// Send notification if crossing into high or very_high level
	if(newLevel == "high" || newLevel == "very_high") {
		def crossingType = "${previousLevel} to ${newLevel}"
		def notificationKey = "${deviceId}_${newLevel}"
		
		// Only notify if we haven't already notified for this level
		if(!state.lastNotifications[notificationKey]) {
			def levelDisplay = newLevel == "very_high" ? "very high" : newLevel
			def headerMessage = "🔋 Battery Restored: ${deviceName} has reached ${levelDisplay} battery level (${batteryPercent}%) - ${crossingType}"
			def message = buildFullBatteryReport(headerMessage)
			
			if(notificationDevices) {
				notificationDevices.each { device ->
					device.deviceNotification(message)
				}
				log.info "Battery ${levelDisplay}-level notification sent to ${notificationDevices.size()} device(s) for ${deviceName}"
			} else {
				log.warn "No notification devices configured"
			}
			
			// Set notification flag to prevent duplicates
			state.lastNotifications[notificationKey] = now()
		}
	}
}

/**
 * Handles downward battery level crossings and sends notifications if needed
 */
void handleDownwardCrossing(String deviceId, String deviceName, String previousLevel, String newLevel, Integer batteryPercent, Boolean isReplacement = false) {
	def crossingType = "${previousLevel} to ${newLevel}"
	def notificationKey = "${deviceId}_${newLevel}"
	
	// For battery replacements, always send notification without checking previous notifications
	// For regular crossings, only notify if we haven't already notified for this level
	if(isReplacement || !state.lastNotifications[notificationKey]) {
		def deviceClassification = classifyBatteryDevices()
		
		// Always send full detailed report with header
		def headerMessage = buildNotificationHeader(deviceName, newLevel, batteryPercent, crossingType, isReplacement)
		def message = buildFullBatteryReport(headerMessage)
		
		if(notificationDevices) {
			notificationDevices.each { device ->
				device.deviceNotification(message)
			}
			def notificationType = isReplacement ? "replacement" : "crossing"
			log.info "Battery detailed report notification sent to ${notificationDevices.size()} device(s) for ${deviceName} ${notificationType}"
		} else {
			log.warn "No notification devices configured"
		}
		
		// Only set notification flag for regular crossings, NOT for battery replacements
		// This allows normal crossing notifications to work after battery replacements
		if(!isReplacement) {
			state.lastNotifications[notificationKey] = now()
		}
	} else {
		if(enableDebug) log.debug "Skipped duplicate notification for ${deviceName} ${newLevel} level"
	}
}

/**
 * Sends battery replacement notification and clears all flags
 */
void sendBatteryReplacementNotification(String deviceId, String deviceName, String previousLevel, String newLevel, Integer batteryPercent) {
	resetAllNotificationFlags(deviceId, deviceName)
	
	def crossingType = "${previousLevel} to ${newLevel}"
	def headerMessage = buildNotificationHeader(deviceName, newLevel, batteryPercent, crossingType, true)
	def message = buildFullBatteryReport(headerMessage)
	
	if(notificationDevices) {
		notificationDevices.each { device ->
			device.deviceNotification(message)
		}
		log.info "Battery replacement notification sent to ${notificationDevices.size()} device(s) for ${deviceName}"
	} else {
		log.warn "No notification devices configured"
	}
}

void checkThresholdCrossing(device, String previousLevel, String newLevel, Integer batteryPercent, Integer previousBattery) {
	def deviceId = device.id.toString()
	def deviceName = device.displayName
	def crossingDirection = getCrossingDirection(previousLevel, newLevel)
	
	// Check for battery replacement (>20% increase)
	def batteryIncrease = batteryPercent - previousBattery
	
	if(batteryIncrease > 20 && previousLevel != "unknown") {
		sendBatteryReplacementNotification(deviceId, deviceName, previousLevel, newLevel, batteryPercent)
		return // Skip normal crossing logic after replacement
	}
	
	// Handle upward crossings (battery recovering) - now includes high level notifications
	if(crossingDirection == 1) {
		resetNotificationFlagsBelowCurrent(deviceId, deviceName, newLevel)
		// Allow high-level notifications after any upward crossing
		handleUpwardCrossing(deviceId, deviceName, previousLevel, newLevel, batteryPercent)
	}
	
	// Handle downward crossings (battery declining)
	if(crossingDirection == -1) {
		// Clear the notification flag for the level we're crossing into
		def notificationKey = "${deviceId}_${newLevel}"
		state.lastNotifications.remove(notificationKey)
		
		handleDownwardCrossing(deviceId, deviceName, previousLevel, newLevel, batteryPercent)
	}
}

/**
 * Builds full battery report with header and detailed breakdown
 */
String buildFullBatteryReport(String headerMessage) {
	// Get device classification
	def deviceClassification = classifyBatteryDevices()
	
	// Build summary message with provided header
	def reportLines = []
	reportLines << headerMessage
	reportLines << ""
	reportLines << buildCompactBatteryReport(deviceClassification)
	reportLines << ""
	reportLines << "Detailed Status:"
	
	// Add device status lines
	reportLines.addAll(buildDetailedBatteryReport(deviceClassification))
	
	return reportLines.join("\n")
}

void performBatteryCheck(Boolean isManualCheck) {
	if(!batteryDevices) {
		log.warn "No battery devices configured for monitoring"
		return
	}
	
	state.lastCheck = now()
	
	// Get device classification
	def deviceClassification = classifyBatteryDevices()
	def veryHighDevices = deviceClassification.veryHighDevices
	def highDevices = deviceClassification.highDevices
	def mediumDevices = deviceClassification.mediumDevices
	def lowDevices = deviceClassification.lowDevices
	def criticalDevices = deviceClassification.criticalDevices
	def unknownDevices = deviceClassification.unknownDevices
	
	// Generate report for display purposes only
	def reportLines = buildDetailedBatteryReport(deviceClassification)
	
	// Create summary message
	String summaryMessage
	if(criticalDevices || lowDevices) {
		summaryMessage = "🔋 Battery Report:"
		if(criticalDevices) summaryMessage += " ${criticalDevices.size()} critical"
		if(lowDevices) summaryMessage += "${criticalDevices ? ',' : ''} ${lowDevices.size()} low"
		if(mediumDevices) summaryMessage += ", ${mediumDevices.size()} medium"
		if(highDevices) summaryMessage += ", ${highDevices.size()} high"
		if(veryHighDevices) summaryMessage += ", ${veryHighDevices.size()} very high"
	} else {
		summaryMessage = "🔋 Battery Report: All devices have adequate battery levels"
		def levelCounts = []
		if(mediumDevices) levelCounts << "${mediumDevices.size()} medium"
		if(highDevices) levelCounts << "${highDevices.size()} high"
		if(veryHighDevices) levelCounts << "${veryHighDevices.size()} very high"
		if(levelCounts) summaryMessage += " (${levelCounts.join(', ')})"
	}
	
	def fullReport = summaryMessage
	if(reportLines) {
		fullReport += "\n\n" + reportLines.join("\n")
	}
	
	state.lastReport = fullReport
	
	// Manual checks never send notifications - only real-time threshold crossings do
	if(enableDebug) {
		log.debug "Battery check completed: Very High=${veryHighDevices.size()}, High=${highDevices.size()}, Medium=${mediumDevices.size()}, Low=${lowDevices.size()}, Critical=${criticalDevices.size()}"
	}
}

/**
 * Formats battery status for UI display
 */
String formatBatteryStatusForUI() {
	if(!batteryDevices) return "No devices selected for monitoring"
	
	def deviceClassification = classifyBatteryDevices()
	def statusLine = buildCompactBatteryReport(deviceClassification)
	
	return "<b>Current Battery Status:</b><br>${statusLine}"
}