/**
 * This app heavily depends upon (and assumes usage of): https://aladhan.com/prayer-times-api#GetTimings
 */

import java.text.SimpleDateFormat

definition(
    name: "Adhan Player",
    author: "Azfar Khandoker",
    description: "Plays the Adhan on the selected speaker(s) at the appropriate prayer times.",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    namespace: "com.azfarandnusrat.adhanPlayer"
)

preferences {
    section {
        input "speakers", "capability.speechSynthesis", title: "Speaker(s) to play Adhan", required: true, multiple: true
    }

    section (hideable: true, hidden: true, "Adhan Audio Selections") {
        input "fajrAdhanURL", "string", title: "Fajr Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/fajrAdhan.mp3"
        input "dhuhrAdhanURL", "string", title: "Dhuhr Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
        input "asrAdhanURL", "string", title: "Asr Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
        input "maghribAdhanURL", "string", title: "Maghrib Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
        input "ishaAdhanURL", "string", title: "Isha Adhan audio file URL", required: true, defaultValue: "https://azfarandnusrat.com/files/adhan.mp3"
    }

    section (hideable: true, hidden: true, "Advanced Settings") {
        input "endpoint", "string", title: "Al Adhan endpoint URL", required: true, defaultValue: "https://api.aladhan.com/v1/timings"
        input "refreshTime", "time", title: "Time of day to refresh Adhan times", required: true, defaultValue: new SimpleDateFormat().format(toDate("00:00"))
        input "debugLoggingEnabled", "bool", title: "Enable Debug Logging", defaultValue: false
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
    try {
        def params = [
            uri: endpoint,
            query: [
                latitude: location.latitude,
                longitude: location.longitude,
                method: 2 // ISNA
            ],
            contentType: "application/json",
            headers: [
                "User-Agent": "Hubitat/${location.hub.firmwareVersionString} (${app.getLabel()} app)"
            ]
        ]
        log("Refreshing timings with httpGet parameters: ${params}")

        httpGet(params) { response ->
            log("Received response data: ${response?.data}")
            def timings = response?.data?.data?.timings
            def playFunctions = [
                Fajr: playFajrAdhan,
                Dhuhr: playDhuhrAdhan,
                Asr: playAsrAdhan,
                Maghrib: playMaghribAdhan,
                Isha: playIshaAdhan
            ]

            playFunctions.each { name, func ->
                def date = toDate(timings[name])
                log("Converted ${name} prayer time to date: ${date}")
                if (new Date().before(date)) {
                    log("Scheduling ${name} prayer adhan...")
                    runOnce(date, func)
                } else {
                    log("Time for ${name} prayer passed, not scheduling adhan for today")
                }
            }
        }
    } catch (e) {
        log.error "Error refreshing timings: ${e}"
        if (++state.failures >= 3) {
            log("Failed ${state.failures} times; resetting failure count and sending warning via TTS to: ${speakers}")
            state.failures = 0
            speak("Message from Hubitat! Multiple failed attempts to refresh Adhan timings!")
        }
    }
}

/**
 * these functions need to be duplicated because
 * runOnce() seems to only schedule one function
 * at any given time (i.e. the same "play" function
 * cannot be scheduled for both Fajr and Dhuhr)
 */
def playFajrAdhan() {
    log("Playing Fajr adhan.")
    playTrack(fajrAdhanURL)
}

def playDhuhrAdhan() {
    log("Playing Dhuhr adhan.")
    playTrack(dhuhrAdhanURL)
}

def playAsrAdhan() {
    log("Playing Asr adhan.")
    playTrack(asrAdhanURL)
}

def playMaghribAdhan() {
    log("Playing Maghrib adhan.")
    playTrack(maghribAdhanURL)
}

def playIshaAdhan() {
    log("Playing Isha adhan.")
    playTrack(ishaAdhanURL)
}

def playTrack(track) {
    speakers.each { speaker -> speaker.playTrack(track) }
}

def speak(message) {
    speakers.each { speaker -> speaker.speak(message) }
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
