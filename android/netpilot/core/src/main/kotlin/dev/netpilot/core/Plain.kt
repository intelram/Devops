package dev.netpilot.core

/** Connection quality in words people understand, from the measured response time. */
enum class Quality { EXCELLENT, GOOD, FAIR, POOR, NOT_WORKING }

fun qualityOf(usable: Boolean, responseMs: Long?): Quality = when {
    !usable -> Quality.NOT_WORKING
    responseMs == null -> Quality.GOOD
    responseMs < 150 -> Quality.EXCELLENT
    responseMs < 400 -> Quality.GOOD
    responseMs < 900 -> Quality.FAIR
    else -> Quality.POOR
}

/** One-tap fixes the UI can offer next to a problem. */
enum class FixAction { NONE, INTERNET_PANEL, WIFI_SETTINGS, PRIVATE_DNS_SETTINGS, BATTERY_SETTINGS, DATA_SAVER_SETTINGS, DATE_SETTINGS }

data class Advice(val title: String, val whatToDo: String, val action: FixAction)

/**
 * Plain-language version of a [Finding]: no jargon, one clear next step. The technical
 * message stays available in the Advanced view.
 */
object Plain {
    fun advice(f: Finding, transportName: String?): Advice {
        val net = transportName ?: "Your connection"
        return when (f.code) {
            "AIRPLANE_MODE" -> Advice("Airplane mode is on", "Turn it off to use the internet.", FixAction.INTERNET_PANEL)
            "DATA_SAVER" -> Advice("Data Saver is limiting apps", "Allow NetPilot unrestricted data so protection keeps working in the background.", FixAction.DATA_SAVER_SETTINGS)
            "BG_RESTRICTED" -> Advice("Battery settings may stop NetPilot", "Set NetPilot's battery use to Unrestricted.", FixAction.BATTERY_SETTINGS)
            "VPN_ACTIVE" -> Advice("Another VPN is on", "Android allows one VPN at a time. Turn the other VPN off to use NetPilot protection.", FixAction.NONE)
            "NO_WIFI" -> Advice("Wi-Fi is not connected", "Connect to Wi-Fi to have a backup connection.", FixAction.WIFI_SETTINGS)
            "NO_CELLULAR" -> Advice("Mobile data is off", "Turn on mobile data to have a backup if Wi-Fi fails.", FixAction.INTERNET_PANEL)
            "CAPTIVE_PORTAL" -> Advice("$net needs you to sign in", "Open the sign-in page (often a hotel or café Wi-Fi login).", FixAction.WIFI_SETTINGS)
            "PRIVATE_DNS" -> Advice("Private DNS setting is blocking the internet", "Set Private DNS to Automatic, or fix the server name.", FixAction.PRIVATE_DNS_SETTINGS)
            "PRIVATE_DNS_BYPASS" -> Advice("Blocking can't work with your Private DNS setting", "Private DNS is set to a specific provider, so lookups skip NetPilot. Set Private DNS to Automatic.", FixAction.PRIVATE_DNS_SETTINGS)
            "NOT_VALIDATED" -> Advice("$net is still checking the internet", "Wait a few seconds. If it stays like this, the network has no internet.", FixAction.NONE)
            "DNS_FAIL" -> Advice("$net can't find websites", "The network's address lookup isn't working. Restart the router or switch networks.", FixAction.INTERNET_PANEL)
            "TCP_FAIL" -> Advice("$net is connected but has no internet", "Restart the router, or switch to the other connection.", FixAction.INTERNET_PANEL)
            "TLS_FAIL" -> Advice("$net is blocking secure connections", "Check that the phone's date and time are correct. Public networks sometimes do this.", FixAction.DATE_SETTINGS)
            "HTTP_UNEXPECTED" -> Advice("$net is changing your traffic", "A filter or login page is in the way. Try another network.", FixAction.INTERNET_PANEL)
            "HIGH_LATENCY" -> Advice("$net is slow right now", "Move closer to the router or check your signal.", FixAction.NONE)
            "WEAK_SIGNAL" -> Advice("$net signal is weak", "Expect drops. Move to a spot with better signal.", FixAction.NONE)
            else -> Advice(f.message, f.remediation, FixAction.NONE)
        }
    }
}
