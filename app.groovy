/**
 * Source: https://github.com/akhandok/adhanPlayer
 *
 * This app heavily depends upon (and assumes usage of): https://aladhan.com/prayer-times-api#GetTimings
 */

import java.text.SimpleDateFormat

definition(
    name: "Adhan Player",
    author: "Azfar Khandoker",
    description: "Plays the Adhan on the selected speaker(s) at the appropriate prayer times.",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    namespace: "com.azfarandnusrat.adhanPlayer",
    importUrl: "https://raw.githubusercontent.com/akhandok/adhanPlayer/master/app.groovy"
)

preferences {
    page(name: "settingsPage")
    page(name: "adhanSettingsPage")
    page(name: "advancedSettingsPage")
}

def settingsPage() {
    dynamicPage(name: "settingsPage", title: "Settings", install: true, uninstall: true) {
        section("Main Settings") {
            input "speakers", "capability.speechSynthesis", title: "Speaker(s) to play Adhan", required: true, multiple: true, submitOnChange: true

            if (speakers) {
                paragraph "<small><i><b>Note:</b> If playing custom MP3 files on the speaker(s) is not working, please consider enabling the TTS-only alert in the <b>Advanced Settings</b>.</i></small>"
            }

            input "shouldSendPushNotification", "bool", title: "Send push notifications?", submitOnChange: true

            if (shouldSendPushNotification) {
                input "notifier", "capability.notification", title: "Notification Device(s)", required: true, multiple: true
            }
        }

        section("Additional Settings") {
            href title: "Adhan Settings", description: "Change settings for each Adhan", page: "adhanSettingsPage"
            href title: "Advanced Settings", description: "Change settings that may help if the app is not behaving as expected", page: "advancedSettingsPage"
            mode title: "Set for specific mode(s)"
        }
    }
}

def adhanSettingsPage() {
    dynamicPage(name: "adhanSettingsPage", title: "Adhan Settings") {
        section {
            paragraph "Choose settings for each Adhan."
            paragraph "You may set an adjustment for each Adhan. The adjustment (+/- in minutes) is the number of minutes to play the Adhan before/after the actual Adhan time.\n"+
                      "<i>For example, setting an adjustment of -2 will play the Adhan 2 minutes before the actual Adhan time.</i>"
        }

        getAdhanMap().keySet().each { adhan ->
            // for some reason we have to use a named parameter here
            // because the default "it" variable seems to disappear
            // in the closure below for the section
            section("${adhan} settings", hideable: true, hidden: true) {
                if (!ttsOnly) {
                    input getAdhanTrackVariableName(adhan), "string", title: "Custom Adhan audio URL"
                }

                input getAdhanOffsetVariableName(adhan), "number", title: "Time adjustment", range: "*..*"
            }
        }
    }
}

def advancedSettingsPage() {
    dynamicPage(name: "advancedSettingsPage", title: "Advanced Settings") {
        section {
            input "method", "enum", title: "Prayer times calculation method", defaultValue: getDefaultMethod(), options: getMethodsMap().keySet() as List
            input "refreshTime", "time", title: "Custom time of day to refresh Adhan times"
            input "ttsOnly", "bool", title: "Only alert via TTS? (disables Adhan audio, useful if custom MP3 audio is not supported/working)"
            input "debugLoggingEnabled", "bool", title: "Enable Debug Logging"
        }
    }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    refreshTimings()

    // offset by 30 minutes from midnight to
    // allow backend ample time to resolve to the new date
    // jitter to distribute load
    def scheduleTime = refreshTime ?: toDate("00:${30 + new Random().nextInt(10) /* jitter */}")
    log("Scheduling refreshTimings for ${scheduleTime}")
    schedule(scheduleTime, refreshTimings)
}

def refreshTimings() {
    def params = [
        uri: "https://api.aladhan.com/v1/timings",
        query: [
            latitude: location.latitude,
            longitude: location.longitude,
            method: getMethodsMap()[method ?: getDefaultMethod()]
        ],
        contentType: "application/json",
        headers: [
            "User-Agent": "Hubitat/${location.hub.firmwareVersionString} (${app.getLabel()} app)"
        ]
    ]

    log("Refreshing timings with parameters: ${params}")

    asynchttpGet(responseHandler, params)
}

def responseHandler(response, _) {
    if (response.hasError()) {
        log.error "Error in response: ${response.getErrorMessage()}"
        return
    }

    def responseData = response.getJson()
    log("Received response data: ${responseData}")

    getAdhanMap().keySet().each {
        def date = toDate(responseData.data.timings[it])
        log("Converted ${it} prayer time to date: ${date}")
        if (new Date().before(date)) {
            log("Scheduling ${it} prayer adhan...")
            runOnce(date, getAdhanFunctionName(it), [data: [name: it]])
        } else {
            log("Time for ${it} prayer passed, not scheduling adhan for today")
        }
    }
}

/**
 * these functions need to be duplicated because
 * runOnce() seems to only schedule one function
 * at any given time (i.e. the same function
 * cannot be scheduled for both Fajr and Dhuhr)
 */
def playFajrAdhan(data)    { playAdhan(data.name) }
def playDhuhrAdhan(data)   { playAdhan(data.name) }
def playAsrAdhan(data)     { playAdhan(data.name) }
def playMaghribAdhan(data) { playAdhan(data.name) }
def playIshaAdhan(data)    { playAdhan(data.name) }

def playAdhan(name) {
    def message = "Time for ${name} prayer."

    log(message)

    if (shouldSendPushNotification) {
        log("Sending push notification \"${message}\" to ${notifier}")
        notifier.deviceNotification(message)
    }

    speakers.each {
        if (it.hasCommand("initialize")) {
            // call initialize() for Google speakers
            // since they can get disconnected
            it.initialize()
        }

        if (!ttsOnly && it.hasCommand("playTrack")) {
            it.playTrack(getAdhanTrack(name))
        } else {
            it.speak("It is time for prayer.")
        }
    }
}

def toDate(time) {
    def hour = Integer.parseInt(time.substring(0, time.indexOf(':')))
    def min = Integer.parseInt(time.substring(time.indexOf(':') + 1))
    def cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, hour)
    cal.set(Calendar.MINUTE, min)
    return cal.getTime()
}

def log(message) {
    if (debugLoggingEnabled) {
        log.debug message
    }
}

def getAdhanOffset(adhan) {
    getAdhanMap()[adhan].offset
}

def getAdhanTrack(adhan) {
    getAdhanMap()[adhan].track
}

def getAdhanFunctionName(adhan) {
    getAdhanMap()[adhan].function
}

def getAdhanTrackVariableName(adhan) {
    "${adhan}Track"
}

def getAdhanOffsetVariableName(adhan) {
    "${adhan}Offset"
}

def getAdhanMap() {
    // names must mirror those from the backend
    // https://aladhan.com/prayer-times-api#GetTimings
    ["Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"].collectEntries { [(it): [
        function: "play${it}Adhan",
        
        // hubitat sets these variables on the AppExecutor object (this)
        // when the user opens the settings page for them
        track: this[getAdhanTrackVariableName(it)] ?: "https://azfarandnusrat.com/files/${it == "Fajr" ? "fajrAdhan" : "adhan"}.mp3",
        offset: this[getAdhanOffsetVariableName(it)] ?: 0
    ]]}
}

def getDefaultMethod() { "Islamic Society of North America" }
def getMethodsMap() {
    // must mirror the "method" options from the backend
    // https://aladhan.com/prayer-times-api#GetTimings
    return [
        "Shia Ithna-Ansari": 0,
        "University of Islamic Sciences, Karachi": 1,
        "Islamic Society of North America": 2,
        "Muslim World League": 3,
        "Umm Al-Qura University, Makkah": 4,
        "Egyptian General Authority of Survey": 5,
        "Institute of Geophysics, University of Tehran": 7,
        "Gulf Region": 8,
        "Kuwait": 9,
        "Qatar": 10,
        "Majlis Ugama Islam Singapura, Singapore": 11,
        "Union Organization islamic de France": 12,
        "Diyanet İşleri Başkanlığı, Turkey": 13,
        "Spiritual Administration of Muslims of Russia": 14
    ]
}

