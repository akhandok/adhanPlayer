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
    section ("Settings") {
        input "speakers", "capability.speechSynthesis", title: "Speaker(s) to play Adhan", required: true, multiple: true, submitOnChange: true

        if (speakers) {
            paragraph "<small><i><b>Note:</b> If playing custom MP3 files on the speaker(s) is not working, please consider enabling the TTS-only alert in the <b>Advanced Settings</b>.</i></small>"
        }

        input "shouldSendPushNotification", "bool", title: "Send push notifications?", submitOnChange: true

        if (shouldSendPushNotification) {
            input "notifier", "capability.notification", title: "Notification Device(s)", required: true, multiple: true, submitOnChange: true
        }
    }

    if (!ttsOnly) {
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
    if (response.hasError()) {
        log.error "Error refreshing timings: ${response.getErrorMessage()}"
        if (++state.failures >= 3) {
            log("Failed ${state.failures} times; resetting failure count and sending warning via TTS to: ${speakers}")
            state.failures = 0
            speak("Message from Hubitat! Multiple failed attempts to refresh Adhan timings!")
        }

        return
    }

    // TODO: error handling
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
        log("Sending push notification \"${message}\" to ${notifier}")
        notifier.deviceNotification(message)
    }

    if (ttsOnly) {
        speak("It is time for prayer.")
    } else {
        playTrack(track)
    }
}

def playTrack(track) {
    // TODO: error handling
    speakers.each { speaker ->
        speaker.initialize()
        speaker.playTrack(track)
    }
}

def speak(message) {
    // TODO: error handling
    speakers.each { speaker ->
        speaker.initialize()
        speaker.speak(message)
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
    def generator = { function, track -> [ function: function, track: track ] }
    return [
        Fajr: generator(playFajrAdhan, fajrAdhanURL),
        Dhuhr: generator(playDhuhrAdhan, dhuhrAdhanURL),
        Asr: generator(playAsrAdhan, asrAdhanURL),
        Maghrib: generator(playMaghribAdhan, maghribAdhanURL),
        Isha: generator(playIshaAdhan, ishaAdhanURL)
    ]
}

def getDefaultRefreshTime() {
    // offset by 30 minutes from midnight to
    // allow backend ample time to resolve to the new date
    // jitter to distribute load
    def jitter = new Random().nextInt(10)
    return new SimpleDateFormat().format(toDate("00:${30 + jitter}"))
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

