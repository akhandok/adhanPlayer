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

String getMethod() { methodOverride ?: "Islamic Society of North America" }
String getEndpoint() { endpointOverride ?: "https://api.aladhan.com/v1/timings" }
String getRefreshTime() { refreshTimeOverride ?:
    // offset by 30 minutes from midnight to
    // allow backend ample time to resolve to the new date
    // jitter to distribute load
    new SimpleDateFormat().format(toDate("00:${30 + new Random().nextInt(10) /* jitter */}"))
}

preferences {
    page(name: "settingsPage")
    page(name: "adhanSettingsPage")
    page

    /*if (!ttsOnly) {
        section (hideable: true, hidden: true, "Adhan Audio Selections") {
            input "fajrAdhanURL", "string", title: "Fajr Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/fajrAdhan.mp3"
            input "dhuhrAdhanURL", "string", title: "Dhuhr Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
            input "asrAdhanURL", "string", title: "Asr Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
            input "maghribAdhanURL", "string", title: "Maghrib Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
            input "ishaAdhanURL", "string", title: "Isha Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
        }
    }

    section (hideable: true, hidden: !ttsOnly, "Advanced Settings") {
        input "method", "enum", title: "Prayer times calculation method", defaultValue: "Islamic Society of North America", options: getMethodsMap().keySet() as List
        input "endpoint", "string", title: "Al Adhan endpoint URL", required: true, defaultValue: "https://api.aladhan.com/v1/timings"
        input "refreshTime", "time", title: "Time of day to refresh Adhan times", required: true, defaultValue: getDefaultRefreshTime()
        input "ttsOnly", "bool", title: "Only alert via TTS? (disables Adhan audio, useful if custom MP3 audio is not supported)", submitOnChange: true
        input "debugLoggingEnabled", "bool", title: "Enable Debug Logging"
    }*/
}

def settingsPage() {
    getAdhanMap()
    dynamicPage(name: "settingsPage", title: "Settings", uninstall: true) {
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
            section("${adhan} settings", hideable: true, hidden: true) {
                if (!ttsOnly) {
                    input "${adhan}AdhanURL", "string", title: "Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/${adhan == "Fajr" ? "fajrAdhan" : "adhan"}.mp3"
                }

                input "${adhan}Offset", "number", title: "Time adjustment", range: "*..*"
            }
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    state.failures = 0

    refreshTimings()

    schedule(refreshTime, refreshTimings)
}

def refreshTimings() {
    def params = [
        uri: endpoint,
        query: [
            latitude: location.latitude,
            longitude: location.longitude,
            method: getMethodsMap()[method]
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
    try {
        if (response.hasError()) {
            throw new Exception(response.getErrorMessage())
        }

        def responseData = response.getJson()
        log("Received response data: ${responseData}")

        def timings = responseData.data.timings
        getAdhanMap().each { name, adhan ->
            def date = toDate(timings[name])
            log("Converted ${name} prayer time to date: ${date}")
            if (new Date().before(date)) {
                log("Scheduling ${name} prayer adhan...")
                runOnce(date, adhan.function, [data: [name: name, track: adhan.track]])
            } else {
                log("Time for ${name} prayer passed, not scheduling adhan for today")
            }
        }
    } catch (e) {
        recordFailure("Error refreshing timings: ${e}")
    }
}

/**
 * these functions need to be duplicated because
 * runOnce() seems to only schedule one function
 * at any given time (i.e. the same "play" function
 * cannot be scheduled for both Fajr and Dhuhr)
 */
def playFajrAdhan(data)    { playAdhan(data.name, data.track) }
def playDhuhrAdhan(data)   { playAdhan(data.name, data.track) }
def playAsrAdhan(data)     { playAdhan(data.name, data.track) }
def playMaghribAdhan(data) { playAdhan(data.name, data.track) }
def playIshaAdhan(data)    { playAdhan(data.name, data.track) }

def playAdhan(name, track) {
    def message = "Time for ${name} prayer."
    log(message)

    if (shouldSendPushNotification) {
        try {
            log("Sending push notification \"${message}\" to ${notifier}")
            notifier.deviceNotification(message)
        } catch (e) {
            recordFailure("Error sending push notification \"${message}\" to ${notifier}")
        }
    }

    speakers.each { speaker ->
        try {
            if (speaker.hasCommand("initialize")) {
                // call initialize() for Google speakers
                // since they can get disconnected
                speaker.initialize()
            }

            if (!ttsOnly && speaker.hasCommand("playTrack")) {
                speaker.playTrack(track)
            } else {
                speaker.speak("It is time for prayer.")
            }
        } catch (e) {
            recordFailure("Error playing track \"${track}\" or speaking message \"${message}\" on ${speaker}: ${e}")
        }
    }
}

def recordFailure(failureMessage) {
    log.error "${app.getLabel()} failure: ${failureMessage}"
    if (++state.failures >= 3) {
        log.error "Failed ${state.failures} times; resetting failure count"
        state.failures = 0
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

def getAdhanMap() {
    // names should mirror those from the backend
    // https://aladhan.com/prayer-times-api#GetTimings
    ["Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"].collectEntries { [(it): [
        function: "play${it}Adhan",
        track: state["${it}AdhanURLOverride"] ?: "https://azfarandnusrat.com/files/${it == "Fajr" ? "fajrAdhan" : "adhan"}.mp3",
        offset: state["${it}Offset"] ?: 0
    ]]}
}

def getMethodsMap() {
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

