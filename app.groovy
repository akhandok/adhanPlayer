/**
 * Source: https://github.com/akhandok/adhanPlayer
 */
import java.text.SimpleDateFormat
import hubitat.helper.RMUtils

definition(
    name: "Adhan Player",
    author: "Azfar Khandoker",
    description: "Plays the Adhan on the selected speaker(s) at the appropriate prayer times.",
    iconUrl: "",
    iconX2Url: "",
    namespace: "com.azfarandnusrat.adhanPlayer",
    importUrl: "https://raw.githubusercontent.com/akhandok/adhanPlayer/master/app.groovy",
    documentationLink: "https://github.com/akhandok/adhanPlayer/blob/master/README.md",
    singleInstance: true,
    videoLink: "https://youtu.be/pk_CJzPEzos"
)

preferences {
    page(name: "settingsPage")
    page(name: "adhanSettingsPage")
    page(name: "advancedSettingsPage")
}

def settingsPage() {
    dynamicPage(name: "settingsPage", title: "Settings", install: true, uninstall: true) {
        section {
            paragraph "Location used to calculate Adhan times (from <a href=\"/location/edit\">Hub Settings</a>): (<b>${location.latitude}, ${location.longitude}</b>)"

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
                    if (adhan == 'Asr') {
                        input "useHanafiMadhab", "bool", title: "Use Hanafi Madhab? (default is Shafi)"
                    }
                    
                    if (!ttsOnly) {
                        input getAdhanTrackVariableName(adhan), "string", title: "Custom Adhan MP3 URL"
                    } else {
                        input getAdhanTTSMessageVariableName(adhan), "string", title: "Message to speak at Adhan time", required: true, defaultValue: getAdhanTTSMessage(adhan)
                    }

                    input getAdhanOffsetVariableName(adhan), "number", title: "Time adjustment", range: "*..*"

                    input getAdhanRMRulesVariableName(adhan), "enum", title: "Select which rule(s) (Rule Machine 4.1 and below) to run at Adhan time", options: (RMUtils.getRuleList() ?: []), multiple: true
                    
                    input getAdhanRM5RulesVariableName(adhan), "enum", title: "Select which rule(s) (Rule Machine 5.0 and above) to run at Adhan time", options: (RMUtils.getRuleList('5.0') ?: []), multiple: true
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

            input "appLabelUpdateDisabled", "bool", title: "Disable updating App Label with Adhan times?"
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
    def scheduleTime = refreshTime ?: toDate("02:30")
    log("Scheduling refreshTimings for ${scheduleTime}")
    schedule(scheduleTime, refreshTimings)
}

def refreshTimings() {
    try {
        def parameters = getMethodsMap()[method ?: getDefaultMethod()].getParameters()
        getAdhanNames().each {
            parameters.adjustments[it.toLowerCase()] += getAdhanOffset(it)
        }
        if (useHanafiMadhab) {
            parameters.madhab = Madhab_HANAFI()
        }
        def times = PrayerTimes_ctor(
            Coordinates_ctor(location.latitude, location.longitude),
            DateComponents_from(new Date()),
            parameters)
        log("Calculated times: ${times}")
        
        def newLabelPrefix = "${app.name} <span style='color:green'>("
        getAdhanNames().findAll { !getAdhanDisabled(it) }.each {
            def date = times[it.toLowerCase()]
            newLabelPrefix += " ${it}-${new SimpleDateFormat("HH:mm").format(date)}"
            if (new Date().before(date)) {
                log("Scheduling ${it} prayer adhan...")
                // see http://docs.smartthings.com/en/latest/smartapp-developers-guide/scheduling.html#run-once-in-the-future-runonce
                runOnce(date, playAdhan, [data: [name: it], overwrite: false])
            } else {
                log("Time for ${it} prayer passed, not scheduling adhan for today")
            }
        };

        // keep the if branch here in case the updating of the label were enabled before,
        // but later it is disabled, so as to remove the previous label that had the timings
        if (appLabelUpdateDisabled) {
            app.updateLabel(app.name)
        } else {
            app.updateLabel(newLabelPrefix + " )</span>")
        }
    } catch (Exception e) {
        log.error "Error in Adhan calculation: ${e}"
        app.updateLabel("${app.name} <span style='color:red'>(Error detected. Check logs.)</span>")
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
    
    rules = getAdhanRM5Rules(adhan)
    if (rules) {
        log("Running RM 5.0 rules for ${adhan}: ${rules}")
        RMUtils.sendAction(rules, "runRuleAct", app.label, '5.0')
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

def getAdhanRM5RulesVariableName(adhan) {
    "${adhan}RM5Rules"
}

def getAdhanRM5Rules(adhan) {
    this[getAdhanRM5RulesVariableName(adhan)] ?: []
}

def getAdhanNames() {
    ["Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"]
}

def getDefaultMethod() { "Islamic Society of North America" }
def getMethodsMap() {
    [
        "Muslim World League": CalculationMethod_MUSLIM_WORLD_LEAGUE(),
        "Egyptian General Authority of Survey": CalculationMethod_EGYPTIAN(),
        "University of Islamic Sciences, Karachi": CalculationMethod_KARACHI(),
        "Umm al-Qura University, Makkah": CalculationMethod_UMM_AL_QURA(),
        "The Gulf Region": CalculationMethod_DUBAI(),
        "Moonsighting Committee": CalculationMethod_MOON_SIGHTING_COMMITTEE(),
        "Islamic Society of North America": CalculationMethod_NORTH_AMERICA(),
        "Kuwait": CalculationMethod_KUWAIT(),
        "Qatar": CalculationMethod_QATAR(),
        "Singapore": CalculationMethod_SINGAPORE(),
    ]
}

// ==========================================================================================================================================
// Copy-pasted file from https://github.com/akhandok/adhanPlayer/blob/5c4fb35afc60f9095109659485883e21fa55aa34/app.groovy
// ==========================================================================================================================================

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

// ==========================================================================================================================================
// Java Enums
// ==========================================================================================================================================

def Enum_ctor(String enumName) {
    return [
        name: enumName
    ];
}

// ==========================================================================================================================================
// Coordinates
// ==========================================================================================================================================

def Coordinates_ctor(double latitude, double longitude) {
    return [
        latitude: latitude,
        longitude: longitude
    ];
}

// ==========================================================================================================================================
// DateComponents
// ==========================================================================================================================================

def DateComponents_ctor(int year, int month, int day) {
    return [
        year: year,
        month: month,
        day: day
    ];
}

def DateComponents_from(Date date) {
    Calendar calendar = GregorianCalendar.getInstance();
    calendar.setTime(date);
    return DateComponents_ctor(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
}

def DateComponents_fromUTC(Date date) {
    Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(date);
    return DateComponents_ctor(calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
}

// ==========================================================================================================================================
// PrayerAdjustments
// ==========================================================================================================================================

def PrayerAdjustments_ctor() {
    return [
        fajr: 0,
        sunrise: 0,
        dhuhr: 0,
        asr: 0,
        maghrib: 0,
        isha: 0
    ];
}

def PrayerAdjustments_ctor(int fajr, int sunrise, int dhuhr, int asr, int maghrib, int isha) {
    def instance = PrayerAdjustments_ctor()
    instance.fajr = fajr;
    instance.sunrise = sunrise;
    instance.dhuhr = dhuhr;
    instance.asr = asr;
    instance.maghrib = maghrib;
    instance.isha = isha;
    return instance;
}

// ==========================================================================================================================================
// NightPortions
// ==========================================================================================================================================

def NightPortions_ctor(double fajr, double isha) {
    return [
        fajr: fajr,
        isha: isha
    ];
}

// ==========================================================================================================================================
// HighLatitudeRule
// ==========================================================================================================================================

def HighLatitudeRule_MIDDLE_OF_THE_NIGHT() {
    return Enum_ctor('HighLatitudeRule.MIDDLE_OF_THE_NIGHT');
}

def HighLatitudeRule_SEVENTH_OF_THE_NIGHT() {
    return Enum_ctor('HighLatitudeRule.SEVENTH_OF_THE_NIGHT');
}

def HighLatitudeRule_TWILIGHT_ANGLE() {
    return Enum_ctor('HighLatitudeRule.TWILIGHT_ANGLE');
}

// ==========================================================================================================================================
// ShadowLength 
// ==========================================================================================================================================

def ShadowLength_enum(String name, double shadowLength) {
    def instance = Enum_ctor(name);
    instance.shadowLength = shadowLength;
    instance.getShadowLength = { return instance.shadowLength; }
    return instance;
}

def ShadowLength_SINGLE() {
    return ShadowLength_enum('ShadowLength.SINGLE', 1.0);
}

def ShadowLength_DOUBLE() {
    return ShadowLength_enum('ShadowLength.DOUBLE', 2.0);
}

// ==========================================================================================================================================
// Madhab  
// ==========================================================================================================================================

def Madhab_enum(String name, Map shadowLength) {
    def instance = Enum_ctor(name);
    instance.getShadowLength = { return shadowLength };
    return instance;
}

def Madhab_SHAFI() {
    return Madhab_enum('Madhab.SHAFI', ShadowLength_SINGLE())
}

def Madhab_HANAFI() {
    return Madhab_enum('Madhab.HANAFI', ShadowLength_DOUBLE())
}

// ==========================================================================================================================================
// CalculationParameters
// ==========================================================================================================================================

def CalculationParameters_ctor() {
    def instance = [
        method: CalculationMethod_OTHER(),
        fajrAngle: 0,
        ishaAngle: 0,
        ishaInterval: 0,
        madhab: Madhab_SHAFI(),
        highLatitudeRule: HighLatitudeRule_MIDDLE_OF_THE_NIGHT(),
        adjustments: PrayerAdjustments_ctor(),
        methodAdjustments: PrayerAdjustments_ctor()
    ]
    instance.withMethodAdjustments = { Map adjustments ->
        instance.methodAdjustments = adjustments;
        return instance;
    }
    instance.nightPortions = {
        switch (instance.highLatitudeRule.name) {
            case HighLatitudeRule_MIDDLE_OF_THE_NIGHT().name:
                return NightPortions_ctor(1.0 / 2.0, 1.0 / 2.0);
            case HighLatitudeRule_SEVENTH_OF_THE_NIGHT().name:
                return NightPortions_ctor(1.0 / 7.0, 1.0 / 7.0);
            case HighLatitudeRule_TWILIGHT_ANGLE().name:
                return NightPortions_ctor(instance.fajrAngle / 60.0, instance.ishaAngle / 60.0);
            default:
                throw new IllegalArgumentException("Invalid high latitude rule");
        }
    }
    return instance;
}

def CalculationParameters_ctor(double fajrAngle, double ishaAngle) {
    def instance = CalculationParameters_ctor()
    instance.fajrAngle = fajrAngle;
    instance.ishaAngle = ishaAngle;
    return instance;
}

def CalculationParameters_ctor(double fajrAngle, int ishaInterval) {
    def instance = CalculationParameters_ctor(fajrAngle, 0.0);
    instance.ishaInterval = ishaInterval;
    return instance;
}

def CalculationParameters_ctor(double fajrAngle, double ishaAngle, Map method) {
    def instance = CalculationParameters_ctor(fajrAngle, ishaAngle);
    instance.method = method;
    return instance;
}

def CalculationParameters_ctor(double fajrAngle, int ishaInterval, Map method) {
    def instance = CalculationParameters_ctor(fajrAngle, ishaInterval);
    instance.method = method;
    return instance;
}

// ==========================================================================================================================================
// CalculationMethod 
// ==========================================================================================================================================

def CalculationMethod_enum(String name, double fajrAngle, double ishaAngle) {
    def instance = Enum_ctor(name);
    instance.getParameters = {
        return CalculationParameters_ctor(fajrAngle, ishaAngle, instance);
    }
    return instance;
}

def CalculationMethod_enum(String name, double fajrAngle, int ishaInterval) {
    def instance = Enum_ctor(name);
    instance.getParameters = {
        return CalculationParameters_ctor(fajrAngle, ishaInterval, instance);
    }
    return instance;
}

def CalculationMethod_enum(String name, double fajrAngle, double ishaAngle, Map adjustments) {
    def instance = Enum_ctor(name);
    instance.getParameters = {
        return CalculationParameters_ctor(fajrAngle, ishaAngle, instance).withMethodAdjustments(adjustments);
    }
    return instance;
}

def CalculationMethod_MUSLIM_WORLD_LEAGUE() {
    def adjustments = PrayerAdjustments_ctor(0, 0, 1, 0, 0, 0);
    return CalculationMethod_enum('CalculationMethod.MUSLIM_WORLD_LEAGUE', 18.0, 17.0, adjustments);
}

def CalculationMethod_EGYPTIAN() {
    def adjustments = PrayerAdjustments_ctor(0, 0, 1, 0, 0, 0);
    return CalculationMethod_enum('CalculationMethod.EGYPTIAN', 19.5, 17.5, adjustments);
}

def CalculationMethod_KARACHI() {
    def adjustments = PrayerAdjustments_ctor(0, 0, 1, 0, 0, 0);
    return CalculationMethod_enum('CalculationMethod.KARACHI', 18.0, 18.0, adjustments);
}

def CalculationMethod_UMM_AL_QURA() {
    return CalculationMethod_enum('CalculationMethod.UMM_AL_QURA', 18.5, 90);
}

def CalculationMethod_DUBAI() {
    def adjustments = PrayerAdjustments_ctor(0, -3, 3, 3, 3, 0);
    return CalculationMethod_enum('CalculationMethod.DUBAI', 18.2, 18.2, adjustments);
}

def CalculationMethod_MOON_SIGHTING_COMMITTEE() {
    def adjustments = PrayerAdjustments_ctor(0, 0, 5, 0, 3, 0);
    return CalculationMethod_enum('CalculationMethod.MOON_SIGHTING_COMMITTEE', 18.0, 18.0, adjustments);
}

def CalculationMethod_NORTH_AMERICA() {
    def adjustments = PrayerAdjustments_ctor(0, 0, 1, 0, 0, 0);
    return CalculationMethod_enum('CalculationMethod.NORTH_AMERICA', 15.0, 15.0, adjustments);
}

def CalculationMethod_KUWAIT() {
    return CalculationMethod_enum('CalculationMethod.KUWAIT', 18.0, 17.5);
}

def CalculationMethod_QATAR() {
    return CalculationMethod_enum('CalculationMethod.QATAR', 18.0, 90);
}

def CalculationMethod_SINGAPORE() {
    def adjustments = PrayerAdjustments_ctor(0, 0, 1, 0, 0, 0);
    return CalculationMethod_enum('CalculationMethod.SINGAPORE', 20.0, 18.0, adjustments);
}

def CalculationMethod_OTHER() {
    return CalculationMethod_enum('CalculationMethod.OTHER', 0.0, 0.0);
}

// ==========================================================================================================================================
// TimeComponents 
// ==========================================================================================================================================

def TimeComponents_ctor(int hours, int minutes, int seconds) {
    def instance = [
        hours: hours,
        minutes: minutes,
        seconds: seconds
    ];
    instance.dateComponents = { Map date ->
        def calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(Calendar.YEAR, date.year);
        calendar.set(Calendar.MONTH, date.month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, date.day);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, minutes);
        calendar.set(Calendar.SECOND, seconds);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        return calendar.getTime();
    };
    return instance;
}

def TimeComponents_fromDouble(double value) {
    if (Double.isInfinite(value) || Double.isNaN(value)) {
      return null;
    }

    def hours = Math.floor(value);
    def minutes = Math.floor((value - hours) * 60.0);
    def seconds = Math.floor((value - (hours + minutes / 60.0)) * 60 * 60);
    return TimeComponents_ctor((int) hours, (int) minutes, (int) seconds);
}

// ==========================================================================================================================================
// CalendarUtil  
// ==========================================================================================================================================

def CalendarUtil_isLeapYear(int year) {
    return year % 4 == 0 && !(year % 100 == 0 && year % 400 != 0);
}

def CalendarUtil_roundedMinute(Date when) {
    Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(when);
    def minute = calendar.get(Calendar.MINUTE);
    def second = calendar.get(Calendar.SECOND);
    calendar.set(Calendar.MINUTE, (int) (minute + Math.round(second / 60)));
    calendar.set(Calendar.SECOND, 0);
    return calendar.getTime();
}

def CalendarUtil_resolveTime(Map components) {
    return CalendarUtil_resolveTime(components.year, components.month, components.day);
}

def CalendarUtil_add(Date when, int amount, int field) {
    Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(when);
    calendar.add(field, amount);
    return calendar.getTime();
}

def CalendarUtil_resolveTime(int year, int month, int day) {
    Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
    //noinspection MagicConstant
    calendar.set(year, month - 1, day, 0, 0, 0);
    return calendar.getTime();
}

// ==========================================================================================================================================
// DoubleUtil  
// ==========================================================================================================================================

def DoubleUtil_normalizeWithBound(double value, double max) {
    return value - (max * (Math.floor(value / max)));
}

def DoubleUtil_unwindAngle(double value) {
    return DoubleUtil_normalizeWithBound(value, 360);
}

def DoubleUtil_closestAngle(double angle) {
    if (angle >= -180 && angle <= 180) {
      return angle;
    }
    return angle - (360 * Math.round(angle / 360));
}

// ==========================================================================================================================================
// Astronomical  
// ==========================================================================================================================================

def Astronomical_meanSolarLongitude(double T) {
    /* Equation from Astronomical Algorithms page 163 */
    final double term1 = 280.4664567;
    final double term2 = 36000.76983 * T;
    final double term3 = 0.0003032 * Math.pow(T, 2);
    final double L0 = term1 + term2 + term3;
    return DoubleUtil_unwindAngle(L0);
}

def Astronomical_meanLunarLongitude(double T) {
    /* Equation from Astronomical Algorithms page 144 */
    final double term1 = 218.3165;
    final double term2 = 481267.8813 * T;
    final double Lp = term1 + term2;
    return DoubleUtil_unwindAngle(Lp);
}

def Astronomical_apparentSolarLongitude(double T, double L0) {
    /* Equation from Astronomical Algorithms page 164 */
    final double longitude = L0 + Astronomical_solarEquationOfTheCenter(T, Astronomical_meanSolarAnomaly(T));
    final double Ω = 125.04 - (1934.136 * T);
    final double λ = longitude - 0.00569 - (0.00478 * Math.sin(Math.toRadians(Ω)));
    return DoubleUtil_unwindAngle(λ);
}

def Astronomical_ascendingLunarNodeLongitude(double T) {
    /* Equation from Astronomical Algorithms page 144 */
    final double term1 = 125.04452;
    final double term2 = 1934.136261 * T;
    final double term3 = 0.0020708 * Math.pow(T, 2);
    final double term4 = Math.pow(T, 3) / 450000;
    final double Ω = term1 - term2 + term3 + term4;
    return DoubleUtil_unwindAngle(Ω);
}

def Astronomical_meanSolarAnomaly(double T) {
    /* Equation from Astronomical Algorithms page 163 */
    final double term1 = 357.52911;
    final double term2 = 35999.05029 * T;
    final double term3 = 0.0001537 * Math.pow(T, 2);
    final double M = term1 + term2 - term3;
    return DoubleUtil_unwindAngle(M);
}

def Astronomical_solarEquationOfTheCenter(double T, double M) {
    /* Equation from Astronomical Algorithms page 164 */
    final double Mrad = Math.toRadians(M);
    final double term1 = (1.914602 - (0.004817 * T) - (0.000014 * Math.pow(T, 2))) * Math.sin(Mrad);
    final double term2 = (0.019993 - (0.000101 * T)) * Math.sin(2 * Mrad);
    final double term3 = 0.000289 * Math.sin(3 * Mrad);
    return term1 + term2 + term3;
}

def Astronomical_meanObliquityOfTheEcliptic(double T) {
    /* Equation from Astronomical Algorithms page 147 */
    final double term1 = 23.439291;
    final double term2 = 0.013004167 * T;
    final double term3 = 0.0000001639 * Math.pow(T, 2);
    final double term4 = 0.0000005036 * Math.pow(T, 3);
    return term1 - term2 - term3 + term4;
}

def Astronomical_apparentObliquityOfTheEcliptic(double T, double ε0) {
    /* Equation from Astronomical Algorithms page 165 */
    final double O = 125.04 - (1934.136 * T);
    return ε0 + (0.00256 * Math.cos(Math.toRadians(O)));
}

def Astronomical_meanSiderealTime(double T) {
    /* Equation from Astronomical Algorithms page 165 */
    final double JD = (T * 36525) + 2451545.0;
    final double term1 = 280.46061837;
    final double term2 = 360.98564736629 * (JD - 2451545);
    final double term3 = 0.000387933 * Math.pow(T, 2);
    final double term4 = Math.pow(T, 3) / 38710000;
    final double θ = term1 + term2 + term3 - term4;
    return DoubleUtil_unwindAngle(θ);
}

def Astronomical_nutationInLongitude(double T, double L0, double Lp, double Ω) {
    /* Equation from Astronomical Algorithms page 144 */
    final double term1 = (-17.2/3600) * Math.sin(Math.toRadians(Ω));
    final double term2 =  (1.32/3600) * Math.sin(2 * Math.toRadians(L0));
    final double term3 =  (0.23/3600) * Math.sin(2 * Math.toRadians(Lp));
    final double term4 =  (0.21/3600) * Math.sin(2 * Math.toRadians(Ω));
    return term1 - term2 - term3 + term4;
}

def Astronomical_nutationInObliquity(double T, double L0, double Lp, double Ω) {
    /* Equation from Astronomical Algorithms page 144 */
    final double term1 =  (9.2/3600) * Math.cos(Math.toRadians(Ω));
    final double term2 = (0.57/3600) * Math.cos(2 * Math.toRadians(L0));
    final double term3 = (0.10/3600) * Math.cos(2 * Math.toRadians(Lp));
    final double term4 = (0.09/3600) * Math.cos(2 * Math.toRadians(Ω));
    return term1 + term2 + term3 - term4;
}

def Astronomical_altitudeOfCelestialBody(double φ, double δ, double H) {
    /* Equation from Astronomical Algorithms page 93 */
    final double term1 = Math.sin(Math.toRadians(φ)) * Math.sin(Math.toRadians(δ));
    final double term2 = Math.cos(Math.toRadians(φ)) *
        Math.cos(Math.toRadians(δ)) * Math.cos(Math.toRadians(H));
    return Math.toDegrees(Math.asin(term1 + term2));
}

def Astronomical_approximateTransit(double L, double Θ0, double α2) {
    /* Equation from page Astronomical Algorithms 102 */
    final double Lw = L * -1;
    return DoubleUtil_normalizeWithBound((α2 + Lw - Θ0) / 360, 1);
}

def Astronomical_correctedTransit(double m0, double L, double Θ0, double α2, double α1, double α3) {
        /* Equation from page Astronomical Algorithms 102 */
    final double Lw = L * -1;
    final double θ = DoubleUtil_unwindAngle(Θ0 + (360.985647 * m0));
    final double α = DoubleUtil_unwindAngle(Astronomical_interpolateAngles(
            /* value */ α2, /* previousValue */ α1, /* nextValue */ α3, /* factor */ m0));
    final double H = DoubleUtil_closestAngle(θ - Lw - α);
    final double Δm = H / -360;
    return (m0 + Δm) * 24;
}

def Astronomical_correctedHourAngle(double m0, double h0, Map coordinates, boolean afterTransit,
      double Θ0, double α2, double α1, double α3, double δ2, double δ1, double δ3) {
    /* Equation from page Astronomical Algorithms 102 */
    final double Lw = coordinates.longitude * -1;
    final double term1 = Math.sin(Math.toRadians(h0)) -
        (Math.sin(Math.toRadians(coordinates.latitude)) * Math.sin(Math.toRadians(δ2)));
    final double term2 = Math.cos(Math.toRadians(coordinates.latitude)) * Math.cos(Math.toRadians(δ2));
    final double H0 = Math.toDegrees(Math.acos(term1 / term2));
    final double m = afterTransit ? m0 + (H0 / 360) : m0 - (H0 / 360);
    final double θ = DoubleUtil_unwindAngle(Θ0 + (360.985647 * m));
    final double α = DoubleUtil_unwindAngle(Astronomical_interpolateAngles(
        /* value */ α2, /* previousValue */ α1, /* nextValue */ α3, /* factor */ m));
    final double δ = Astronomical_interpolate(/* value */ δ2, /* previousValue */ δ1,
        /* nextValue */ δ3, /* factor */ m);
    final double H = (θ - Lw - α);
    final double h = Astronomical_altitudeOfCelestialBody(/* observerLatitude */ coordinates.latitude,
        /* declination */ δ, /* localHourAngle */ H);
    final double term3 = h - h0;
    final double term4 = 360 * Math.cos(Math.toRadians(δ)) *
        Math.cos(Math.toRadians(coordinates.latitude)) * Math.sin(Math.toRadians(H));
    final double Δm = term3 / term4;
    return (m + Δm) * 24;
}

def Astronomical_interpolate(double y2, double y1, double y3, double n) {
    /* Equation from Astronomical Algorithms page 24 */
    final double a = y2 - y1;
    final double b = y3 - y2;
    final double c = b - a;
    return y2 + ((n/2) * (a + b + (n * c)));
}

def Astronomical_interpolateAngles(double y2, double y1, double y3, double n) {
    /* Equation from Astronomical Algorithms page 24 */
    final double a = DoubleUtil_unwindAngle(y2 - y1);
    final double b = DoubleUtil_unwindAngle(y3 - y2);
    final double c = b - a;
    return y2 + ((n/2) * (a + b + (n * c)));
}

// ==========================================================================================================================================
// CalendricalHelper   
// ==========================================================================================================================================

def CalendricalHelper_julianDay(int year, int month, int day) {
    return CalendricalHelper_julianDay(year, month, day, 0.0);
}

def CalendricalHelper_julianDay(Date date) {
    Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(date);
    return CalendricalHelper_julianDay(calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
        calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0);
}

def CalendricalHelper_julianDay(int year, int month, int day, double hours) {
    /* Equation from Astronomical Algorithms page 60 */

    // NOTE: Integer conversion is done intentionally for the purpose of decimal truncation

    int Y = month > 2 ? year : year - 1;
    int M = month > 2 ? month : month + 12;
    double D = day + (hours / 24);

    int A = Y/100;
    int B = 2 - A + (A/4);

    int i0 = (int) (365.25 * (Y + 4716));
    int i1 = (int) (30.6001 * (M + 1));
    return i0 + i1 + D + B - 1524.5;
}

def CalendricalHelper_julianCentury(double JD) {
    /* Equation from Astronomical Algorithms page 163 */
    return (JD - 2451545.0) / 36525;
}

// ==========================================================================================================================================
// SolarCoordinates    
// ==========================================================================================================================================

def SolarCoordinates_ctor(double julianDay) {
    double T = CalendricalHelper_julianCentury(julianDay);
    double L0 = Astronomical_meanSolarLongitude(/* julianCentury */ T);
    double Lp = Astronomical_meanLunarLongitude(/* julianCentury */ T);
    double Ω = Astronomical_ascendingLunarNodeLongitude(/* julianCentury */ T);
    double λ = Math.toRadians(
        Astronomical_apparentSolarLongitude(/* julianCentury*/ T, /* meanLongitude */ L0));

    double θ0 = Astronomical_meanSiderealTime(/* julianCentury */ T);
    double ΔΨ = Astronomical_nutationInLongitude(/* julianCentury */ T, /* solarLongitude */ L0,
        /* lunarLongitude */ Lp, /* ascendingNode */ Ω);
    double Δε = Astronomical_nutationInObliquity(/* julianCentury */ T, /* solarLongitude */ L0,
        /* lunarLongitude */ Lp, /* ascendingNode */ Ω);

    double ε0 = Astronomical_meanObliquityOfTheEcliptic(/* julianCentury */ T);
    double εapp = Math.toRadians(Astronomical_apparentObliquityOfTheEcliptic(
        /* julianCentury */ T, /* meanObliquityOfTheEcliptic */ ε0));

        /* Equation from Astronomical Algorithms page 165 */
    def declination = Math.toDegrees(Math.asin(Math.sin(εapp) * Math.sin(λ)));

        /* Equation from Astronomical Algorithms page 165 */
    def rightAscension = DoubleUtil_unwindAngle(
        Math.toDegrees(Math.atan2(Math.cos(εapp) * Math.sin(λ), Math.cos(λ))));

        /* Equation from Astronomical Algorithms page 88 */
    def apparentSiderealTime = θ0 + (((ΔΨ * 3600) * Math.cos(Math.toRadians(ε0 + Δε))) / 3600);
    
    return [
        declination: declination,
        rightAscension: rightAscension,
        apparentSiderealTime: apparentSiderealTime
    ];
}

// ==========================================================================================================================================
// SolarTime     
// ==========================================================================================================================================

def SolarTime_ctor(Map today, Map coordinates) {
    final double julianDate = CalendricalHelper_julianDay(today.year, today.month, today.day);

    def prevSolar = SolarCoordinates_ctor(julianDate - 1);
    def solar = SolarCoordinates_ctor(julianDate);
    def nextSolar = SolarCoordinates_ctor(julianDate + 1);

    def approximateTransit = Astronomical_approximateTransit(coordinates.longitude,
        solar.apparentSiderealTime, solar.rightAscension);
    final double solarAltitude = -50.0 / 60.0;

    def observer = coordinates;
    def transit = Astronomical_correctedTransit(approximateTransit, coordinates.longitude,
        solar.apparentSiderealTime, solar.rightAscension, prevSolar.rightAscension,
        nextSolar.rightAscension);
    def sunrise = Astronomical_correctedHourAngle(approximateTransit, solarAltitude,
        coordinates, false, solar.apparentSiderealTime, solar.rightAscension,
        prevSolar.rightAscension, nextSolar.rightAscension, solar.declination,
        prevSolar.declination, nextSolar.declination);
    def sunset = Astronomical_correctedHourAngle(approximateTransit, solarAltitude,
        coordinates, true, solar.apparentSiderealTime, solar.rightAscension,
        prevSolar.rightAscension, nextSolar.rightAscension, solar.declination,
        prevSolar.declination, nextSolar.declination);
    
    def instance = [
        prevSolar: prevSolar,
        solar: solar,
        nextSolar: nextSolar,
        approximateTransit: approximateTransit,
        observer: observer,
        transit: transit,
        sunrise: sunrise,
        sunset: sunset
    ];
    instance.hourAngle = { double angle, boolean afterTransit ->
        return Astronomical_correctedHourAngle(instance.approximateTransit, angle, instance.observer,
            afterTransit, instance.solar.apparentSiderealTime, instance.solar.rightAscension,
            instance.prevSolar.rightAscension, instance.nextSolar.rightAscension, instance.solar.declination,
            instance.prevSolar.declination, instance.nextSolar.declination);
    };
    instance.afternoon = { Map shadowLength ->
        // TODO (from Swift version) source shadow angle calculation
        final double tangent = Math.abs(observer.latitude - solar.declination);
        final double inverse = shadowLength.getShadowLength() + Math.tan(Math.toRadians(tangent));
        final double angle = Math.toDegrees(Math.atan(1.0 / inverse));

        return instance.hourAngle(angle, true);
    };
    return instance;
}

// ==========================================================================================================================================
// PrayerTimes     
// ==========================================================================================================================================

def PrayerTimes_seasonAdjustedMorningTwilight(
      double latitude, int day, int year, Date sunrise) {
    final double a = 75 + ((28.65 / 55.0) * Math.abs(latitude));
    final double b = 75 + ((19.44 / 55.0) * Math.abs(latitude));
    final double c = 75 + ((32.74 / 55.0) * Math.abs(latitude));
    final double d = 75 + ((48.10 / 55.0) * Math.abs(latitude));

    final double adjustment;
    final int dyy = PrayerTimes_daysSinceSolstice(day, year, latitude);
    if ( dyy < 91) {
      adjustment = a + ( b - a ) / 91.0 * dyy;
    } else if ( dyy < 137) {
      adjustment = b + ( c - b ) / 46.0 * ( dyy - 91 );
    } else if ( dyy < 183 ) {
      adjustment = c + ( d - c ) / 46.0 * ( dyy - 137 );
    } else if ( dyy < 229 ) {
      adjustment = d + ( c - d ) / 46.0 * ( dyy - 183 );
    } else if ( dyy < 275 ) {
      adjustment = c + ( b - c ) / 46.0 * ( dyy - 229 );
    } else {
      adjustment = b + ( a - b ) / 91.0 * ( dyy - 275 );
    }

    return CalendarUtil_add(sunrise, -(int) Math.round(adjustment * 60.0), Calendar.SECOND);
}

def PrayerTimes_seasonAdjustedEveningTwilight(
      double latitude, int day, int year, Date sunset) {
    final double a = 75 + ((25.60 / 55.0) * Math.abs(latitude));
    final double b = 75 + ((2.050 / 55.0) * Math.abs(latitude));
    final double c = 75 - ((9.210 / 55.0) * Math.abs(latitude));
    final double d = 75 + ((6.140 / 55.0) * Math.abs(latitude));

    final double adjustment;
    final int dyy = PrayerTimes_daysSinceSolstice(day, year, latitude);
    if ( dyy < 91) {
      adjustment = a + ( b - a ) / 91.0 * dyy;
    } else if ( dyy < 137) {
      adjustment = b + ( c - b ) / 46.0 * ( dyy - 91 );
    } else if ( dyy < 183 ) {
      adjustment = c + ( d - c ) / 46.0 * ( dyy - 137 );
    } else if ( dyy < 229 ) {
      adjustment = d + ( c - d ) / 46.0 * ( dyy - 183 );
    } else if ( dyy < 275 ) {
      adjustment = c + ( b - c ) / 46.0 * ( dyy - 229 );
    } else {
      adjustment = b + ( a - b ) / 91.0 * ( dyy - 275 );
    }

    return CalendarUtil_add(sunset, (int) Math.round(adjustment * 60.0), Calendar.SECOND);
}

def PrayerTimes_daysSinceSolstice(int dayOfYear, int year, double latitude) {
    int daysSinceSolistice;
    final int northernOffset = 10;
    boolean isLeapYear = CalendarUtil_isLeapYear(year);
    final int southernOffset = isLeapYear ? 173 : 172;
    final int daysInYear = isLeapYear ? 366 : 365;

    if (latitude >= 0) {
      daysSinceSolistice = dayOfYear + northernOffset;
      if (daysSinceSolistice >= daysInYear) {
        daysSinceSolistice = daysSinceSolistice - daysInYear;
      }
    } else {
      daysSinceSolistice = dayOfYear - southernOffset;
      if (daysSinceSolistice < 0) {
        daysSinceSolistice = daysSinceSolistice + daysInYear;
      }
    }
    return daysSinceSolistice;
}

def PrayerTimes_ctor(Map coordinates, Map date, Map parameters) {
    def instance = [
        coordinates: coordinates,
        dateComponents: date,
        calculationParameters: parameters
    ];

    Date tempFajr = null;
    Date tempSunrise = null;
    Date tempDhuhr = null;
    Date tempAsr = null;
    Date tempMaghrib = null;
    Date tempIsha = null;

    final Date prayerDate = CalendarUtil_resolveTime(date);
    Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(prayerDate);
    final int dayOfYear = calendar.get(Calendar.DAY_OF_YEAR);

    final Date tomorrowDate = CalendarUtil_add(prayerDate, 1, Calendar.DATE);
    final Map tomorrow = DateComponents_fromUTC(tomorrowDate);

    def solarTime = SolarTime_ctor(date, coordinates);

    def timeComponents = TimeComponents_fromDouble(solarTime.transit);
    Date transit = timeComponents == null ? null : timeComponents.dateComponents(date);
    
    timeComponents = TimeComponents_fromDouble(solarTime.sunrise);
    Date sunriseComponents = timeComponents == null ? null : timeComponents.dateComponents(date);

    timeComponents = TimeComponents_fromDouble(solarTime.sunset);
    Date sunsetComponents = timeComponents == null ? null : timeComponents.dateComponents(date);

    final Map tomorrowSolarTime = SolarTime_ctor(tomorrow, coordinates);
    final Map tomorrowSunriseComponents = TimeComponents_fromDouble(tomorrowSolarTime.sunrise);

    boolean error = transit == null || sunriseComponents == null ||
            sunsetComponents == null || tomorrowSunriseComponents == null;
    if (!error) {
      tempDhuhr = transit;
      tempSunrise = sunriseComponents;
      tempMaghrib = sunsetComponents;

      timeComponents = TimeComponents_fromDouble(
          solarTime.afternoon(parameters.madhab.getShadowLength()));
      if (timeComponents != null) {
        tempAsr = timeComponents.dateComponents(date);
      }

      // get night length
      final Date tomorrowSunrise = tomorrowSunriseComponents.dateComponents(tomorrow);
      long night = tomorrowSunrise.getTime() - sunsetComponents.getTime();

      timeComponents = TimeComponents_fromDouble(solarTime.hourAngle(-parameters.fajrAngle, false));
      if (timeComponents != null) {
        tempFajr = timeComponents.dateComponents(date);
      }

      if (parameters.method.name == CalculationMethod_MOON_SIGHTING_COMMITTEE().name &&
          coordinates.latitude >= 55) {
        tempFajr = CalendarUtil_add(
            sunriseComponents, -1 * (int) (night / 7000), Calendar.SECOND);
      }

      final Map nightPortions = parameters.nightPortions();

      final Date safeFajr;
      if (parameters.method.name == CalculationMethod_MOON_SIGHTING_COMMITTEE().name) {
        safeFajr = PrayerTimes_seasonAdjustedMorningTwilight(coordinates.latitude, dayOfYear, date.year, sunriseComponents);
      } else {
        double portion = nightPortions.fajr;
        long nightFraction = (long) (portion * night / 1000);
        safeFajr = CalendarUtil_add(
            sunriseComponents, -1 * (int) nightFraction, Calendar.SECOND);
      }
        
      if (tempFajr == null || tempFajr.before(safeFajr)) {
        tempFajr = safeFajr;
      }

      // Isha calculation with check against safe value
      if (parameters.ishaInterval > 0) {
        tempIsha = CalendarUtil_add(tempMaghrib, parameters.ishaInterval * 60, Calendar.SECOND);
      } else {
        timeComponents = TimeComponents_fromDouble(
            solarTime.hourAngle(-parameters.ishaAngle, true));
        if (timeComponents != null) {
          tempIsha = timeComponents.dateComponents(date);
        }

        if (parameters.method.name == CalculationMethod_MOON_SIGHTING_COMMITTEE().name &&
            coordinates.latitude >= 55) {
          long nightFraction = night / 7000;
          tempIsha = CalendarUtil_add(sunsetComponents, (int) nightFraction, Calendar.SECOND);
        }

        final Date safeIsha;
        if (parameters.method.name == CalculationMethod_MOON_SIGHTING_COMMITTEE().name) {
            safeIsha = PrayerTimes_seasonAdjustedEveningTwilight(
                coordinates.latitude, dayOfYear, date.year, sunsetComponents);
        } else {
          double portion = nightPortions.isha;
          long nightFraction = (long) (portion * night / 1000);
          safeIsha = CalendarUtil_add(sunsetComponents, (int) nightFraction, Calendar.SECOND);
        }

        if (tempIsha == null || (tempIsha.after(safeIsha))) {
          tempIsha = safeIsha;
        }
      }
    }

    if (error || tempAsr == null) {
      // if we don't have all prayer times then initialization failed
      instance.fajr = null;
      instance.sunrise = null;
      instance.dhuhr = null;
      instance.asr = null;
      instance.maghrib = null;
      instance.isha = null;
    } else {
      // Assign final times to public struct members with all offsets
      instance.fajr = CalendarUtil_roundedMinute(
          CalendarUtil_add(
              CalendarUtil_add(tempFajr, parameters.adjustments.fajr, Calendar.MINUTE),
              parameters.methodAdjustments.fajr,
              Calendar.MINUTE));
      instance.sunrise = CalendarUtil_roundedMinute(
          CalendarUtil_add(
            CalendarUtil_add(tempSunrise, parameters.adjustments.sunrise, Calendar.MINUTE),
            parameters.methodAdjustments.sunrise,
            Calendar.MINUTE));
      instance.dhuhr = CalendarUtil_roundedMinute(
          CalendarUtil_add(
            CalendarUtil_add(tempDhuhr, parameters.adjustments.dhuhr, Calendar.MINUTE),
            parameters.methodAdjustments.dhuhr,
            Calendar.MINUTE));
      instance.asr = CalendarUtil_roundedMinute(
          CalendarUtil_add(
            CalendarUtil_add(tempAsr, parameters.adjustments.asr, Calendar.MINUTE),
            parameters.methodAdjustments.asr,
            Calendar.MINUTE));
      instance.maghrib = CalendarUtil_roundedMinute(
          CalendarUtil_add(
              CalendarUtil_add(tempMaghrib, parameters.adjustments.maghrib, Calendar.MINUTE),
              parameters.methodAdjustments.maghrib,
              Calendar.MINUTE));
      instance.isha = CalendarUtil_roundedMinute(
          CalendarUtil_add(
            CalendarUtil_add(tempIsha, parameters.adjustments.isha, Calendar.MINUTE),
            parameters.methodAdjustments.isha,
            Calendar.MINUTE));
    }
    
    // We do not need these for the Adhan Player app on Hubitat
    // instance.currentPrayer = ...
    // instance.nextPrayer = ...
    // instance.timeForPrayer = ...
    
    return instance;
}
