/**
 * Source: https://github.com/akhandok/adhanPlayer
 *
 * This app heavily depends upon (and assumes usage of): https://aladhan.com/prayer-times-api#GetTimingsByAddresss
 */
import hubitat.helper.RMUtils

definition(
    name: "Adhan Player",
    author: "Azfar Khandoker",
    description: "Plays the Adhan on the selected speaker(s) at the appropriate prayer times.",
    iconUrl: "",
    iconX2Url: "",
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
        section {
            input "speakers", "capability.speechSynthesis", title: "Speaker(s) to play Adhan", required: true, multiple: true, submitOnChange: true

            def ttsOnlySpeakers = speakers.findAll { !it.hasCommand("playTrack") }
            if (!ttsOnlySpeakers.isEmpty()) {
                paragraph "<small><i><b>Warning!</b> It seems like </i>\"${ttsOnlySpeakers}\"<i> cannot play MP3 files and will instead just speak a message.<br />" +
                          "Please consider enabling the TTS-only alert option in the <b>Advanced Settings</b>.</i></small>"
            }

            input "shouldSendPushNotification", "bool", title: "Send push notifications?", submitOnChange: true

            if (shouldSendPushNotification) {
                input "notifier", "capability.notification", title: "Notification Device(s)", required: true, multiple: true
            }

            href title: "Adhan Settings", description: "Change settings for each Adhan", page: "adhanSettingsPage"
            href title: "Advanced Settings", description: "" /* empty string to hide default "Click to show" description */, page: "advancedSettingsPage"
        }
    }
}

def adhanSettingsPage() {
    dynamicPage(name: "adhanSettingsPage", title: "Adhan Settings") {
        section {
            paragraph "Choose settings for each Adhan."
            paragraph "You may set an adjustment for each Adhan. The adjustment (+/- in minutes) is the number of minutes to play the Adhan before/after the actual Adhan time.\n" +
                      "<i>For example, setting an adjustment of -2 will play the Adhan 2 minutes before the actual Adhan time.</i>"
        }

        getAdhanNames().each { adhan -> // for some reason we have to use a named parameter here
                                        // because the default "it" variable seems to disappear
                                        // in the section closure below
            section("${adhan} settings", hideable: true, hidden: true) {
                input getAdhanDisabledVariableName(adhan), "bool", title: "Disable Adhan?", submitOnChange: true

                if (!getAdhanDisabled(adhan)) {
                    if (!ttsOnly) {
                        input getAdhanTrackVariableName(adhan), "string", title: "Custom Adhan MP3 URL"
                    } else {
                        input getAdhanTTSMessageVariableName(adhan), "string", title: "Message to speak at Adhan time", required: true, defaultValue: getAdhanTTSMessage(adhan)
                    }

                    input getAdhanOffsetVariableName(adhan), "number", title: "Time adjustment", range: "*..*"

                    input getAdhanRMRulesVariableName(adhan), "enum", title: "Select which rule(s) to run at Adhan time", options: (RMUtils.getRuleList() ?: []), multiple: true
                }
            }
        }
    }
}

def advancedSettingsPage() {
    dynamicPage(name: "advancedSettingsPage", title: "Advanced Settings") {
        section {
            input "method", "enum", title: "Prayer times calculation method", defaultValue: getDefaultMethod(), options: getMethodsMap().keySet()
            input "refreshTime", "time", title: "Custom time of day to refresh Adhan times"
            input "ttsOnly", "bool", title: "Only alert via TTS? (disables Adhan audio, useful if custom MP3 audio is not supported/working)", submitOnChange: true

            if (ttsOnly) {
                paragraph "<small><i><b>Note:</b> the spoken messages can be changed in <b>Adhan Settings</b>.</i></small>"
            }

            input "debugLoggingEnabled", "bool", title: "Enable Debug Logging"

            mode title: "Set for specific mode(s)"
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
    refreshTimings()

    // Default is after 2:30am to account for daylight savings time
    // http://www.webexhibits.org/daylightsaving/b.html#:~:text=In%20the%20U.S.%2C%202%3A00,the%20fewest%20trains%20were%20running.
    // offset by 30 minutes from midnight to
    // allow backend ample time to resolve to the new date
    // jitter to distribute load
    def scheduleTime = refreshTime ?: toDate("02:${30 + new Random().nextInt(10) /* jitter */}")
    log("Scheduling refreshTimings for ${scheduleTime}")
    schedule(scheduleTime, refreshTimings)
}

def refreshTimings() {
    def params = [
        uri: "https://api.aladhan.com/v1/timingsByAddress",
        query: [
            address: location.zipCode,
            tune: getOffsetNames().collect(this.&getAdhanOffset).join(","),
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
        log.error "Error in Adhan response: ${response.getErrorMessage()}"
        return
    }

    def responseData = response.getJson()
    log("Received response data: ${responseData}")

    getAdhanNames().findAll { !getAdhanDisabled(it) }.each {
        def date = toDate(responseData.data.timings[it])
        log("Converted ${it} prayer time to date: ${date}")
        if (new Date().before(date)) {
            log("Scheduling ${it} prayer adhan...")
            // see http://docs.smartthings.com/en/latest/smartapp-developers-guide/scheduling.html#run-once-in-the-future-runonce
            runOnce(date, playAdhan, [data: [name: it], overwrite: false])
        } else {
            log("Time for ${it} prayer passed, not scheduling adhan for today")
        }
    }
}

def playAdhan(data) {
    def adhan = data.name
    def message = "Time for ${adhan} prayer."

    log(message)

    def rules = getAdhanRMRules(adhan)
    if (rules) {
        log("Running rules for ${adhan}: ${rules}")
        RMUtils.sendAction(rules, "runRuleAct", app.label)
    }

    if (shouldSendPushNotification) {
        log("Sending push notification \"${message}\" to ${notifier}")
        notifier.deviceNotification(message)
    }

    speakers.each {
        try {
            if (it.hasCommand("initialize")) {
                // call initialize() for Google speakers
                // since they can get disconnected
                it.initialize()
            }

            if (!ttsOnly && it.hasCommand("playTrack")) {
                it.playTrack(getAdhanTrack(adhan))
            } else {
                it.speak(getAdhanTTSMessage(adhan))
            }
        } catch (NullPointerException npe) {
            if (it != null) {
                log.error("Error communicating with speaker.\n" +
                    "It is likely that Hubitat has lost connection with: " + it + ".\n" +
                    "Please try removing the speaker from Hubitat and/or rebooting the speaker and then rediscovering it from Hubitat.\n" +
                    "It may also help to give the speaker a reserved IP address on your network.");
            }

            throw npe;
        }
    }
}

def toDate(time) {
    def hour = Integer.parseInt(time.substring(0, time.indexOf(':')))
    def min = Integer.parseInt(time.substring(time.indexOf(':') + 1))
    def cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, hour)
    cal.set(Calendar.MINUTE, min)
    cal.set(Calendar.SECOND, 0)
    cal.getTime()
}

def log(message) {
    if (debugLoggingEnabled) {
        log.debug message
    }
}

def getAdhanTTSMessageVariableName(adhan) {
    "${adhan}TTSMessage"
}

def getAdhanTTSMessage(adhan) {
    this[getAdhanTTSMessageVariableName(adhan)] ?: "It is time for prayer."
}

def getAdhanOffsetVariableName(adhan) {
    "${adhan}Offset"
}

def getAdhanOffset(adhan) {
    (this[getAdhanOffsetVariableName(adhan)] ?: 0) as int
}

def getAdhanTrackVariableName(adhan) {
    "${adhan}Track"
}

def getAdhanTrack(adhan) {
    this[getAdhanTrackVariableName(adhan)] ?: "https://azfarandnusrat.com/files/${adhan == "Fajr" ? "fajrAdhan" : "adhan"}.mp3"
}

def getAdhanDisabledVariableName(adhan) {
    "${adhan}Disabled"
}

def getAdhanDisabled(adhan) {
    this[getAdhanDisabledVariableName(adhan)]
}

def getAdhanRMRulesVariableName(adhan) {
    "${adhan}RMRules"
}

def getAdhanRMRules(adhan) {
    this[getAdhanRMRulesVariableName(adhan)] ?: []
}

def getAdhanNames() {
    // names must mirror those from the backend
    // https://aladhan.com/prayer-times-api#GetTimingsByAddresss
    ["Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"]
}

def getOffsetNames() {
    // names must mirror those from the backend
    // (with some exceptions; see: https://github.com/islamic-network/aladhan.com/issues/18 )
    // https://aladhan.com/calculation-methods
    ["Imsak", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Sunset", "Isha", "Midnight"]
}

def getDefaultMethod() { "Islamic Society of North America" }
def getMethodsMap() {
    // must mirror the "method" options from the backend
    // https://aladhan.com/prayer-times-api#GetTimingsByAddresss
    [
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

