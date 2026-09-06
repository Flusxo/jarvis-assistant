package com.jarvis.assistant

/**
 * All slash-style / natural-language command detection lives here now,
 * instead of scattered as separate regex functions inside MainActivity.
 * To add a new command: add a case to parse(), add a data class below if it
 * carries data, and handle it in MainActivity's `when (command)` block.
 */
sealed class ParsedCommand {
    data class OpenApp(val appName: String) : ParsedCommand()
    data class Play(val query: String) : ParsedCommand()
    data class Call(val name: String) : ParsedCommand()
    data class WhatsApp(val name: String, val text: String) : ParsedCommand()
    data class Sms(val name: String, val text: String) : ParsedCommand()
    data class ReminderRelative(val task: String, val minutesFromNow: Int) : ParsedCommand()
    data class ReminderAbsolute(val task: String, val hour: Int, val minute: Int) : ParsedCommand()
    data class Timer(val minutesFromNow: Int) : ParsedCommand()
    data class Flashlight(val turnOn: Boolean) : ParsedCommand()
    object WifiPanel : ParsedCommand()
    object BluetoothPanel : ParsedCommand()
    data class Note(val text: String) : ParsedCommand()
    object ListNotes : ParsedCommand()
    object None : ParsedCommand()
}

object CommandParser {

    // Order matters: more specific patterns are checked before generic ones.
    fun parse(rawMessage: String, appMap: Map<String, String>): ParsedCommand {
        val message = rawMessage.trim()
        val lower = message.lowercase()

        detectWhatsapp(lower)?.let { return ParsedCommand.WhatsApp(it.first, it.second) }
        detectSms(lower)?.let { return ParsedCommand.Sms(it.first, it.second) }
        detectOpenApp(lower, appMap)?.let { return ParsedCommand.OpenApp(it) }
        detectPlay(lower)?.let { return ParsedCommand.Play(it) }
        detectCall(lower)?.let { return ParsedCommand.Call(it) }
        detectFlashlight(lower)?.let { return ParsedCommand.Flashlight(it) }
        detectWifiPanel(lower)?.let { return ParsedCommand.WifiPanel }
        detectBluetoothPanel(lower)?.let { return ParsedCommand.BluetoothPanel }
        detectReminderAbsolute(lower)?.let { return ParsedCommand.ReminderAbsolute(it.first, it.second.first, it.second.second) }
        detectReminderRelative(lower)?.let { return ParsedCommand.ReminderRelative(it.first, it.second) }
        detectTimer(lower)?.let { return ParsedCommand.Timer(it) }
        detectListNotes(lower)?.let { return ParsedCommand.ListNotes }
        detectNote(message, lower)?.let { return ParsedCommand.Note(it) }

        return ParsedCommand.None
    }

    private fun detectOpenApp(lower: String, appMap: Map<String, String>): String? {
        val triggers = listOf("open ", "launch ", "start ")
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                var appName = lower.removePrefix(trigger).trim()
                appName = appName.removeSuffix(" now").removeSuffix(" please").trim()
                if (appMap.containsKey(appName)) return appName
            }
        }
        return null
    }

    private fun detectPlay(lower: String): String? {
        if (lower.startsWith("play ")) return lower.removePrefix("play ").trim()
        return null
    }

    private fun detectCall(lower: String): String? {
        val triggers = listOf("call ", "phone ", "dial ")
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) return lower.removePrefix(trigger).trim()
        }
        return null
    }

    private fun detectWhatsapp(lower: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("^whatsapp (.+?) saying (.+)$"),
            Regex("^message (.+?) on whatsapp saying (.+)$"),
            Regex("^text (.+?) on whatsapp saying (.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(lower) ?: continue
            return Pair(match.groupValues[1].trim(), match.groupValues[2].trim())
        }
        return null
    }

    private fun detectSms(lower: String): Pair<String, String>? {
        // Deliberately checked after WhatsApp patterns so "text X on whatsapp saying Y"
        // never falls through to plain SMS.
        val pattern = Regex("^text (.+?) saying (.+)$")
        val match = pattern.find(lower) ?: return null
        return Pair(match.groupValues[1].trim(), match.groupValues[2].trim())
    }

    private fun detectReminderRelative(lower: String): Pair<String, Int>? {
        val pattern = Regex("^remind me to (.+?) in (\\d+) (minute|minutes|hour|hours)$")
        val match = pattern.find(lower) ?: return null
        val task = match.groupValues[1].trim()
        val amount = match.groupValues[2].toIntOrNull() ?: return null
        val unit = match.groupValues[3]
        val minutes = if (unit.startsWith("hour")) amount * 60 else amount
        return Pair(task, minutes)
    }

    // "remind me to call mom at 5:30 pm" / "remind me to call mom at 17:30"
    private fun detectReminderAbsolute(lower: String): Pair<String, Pair<Int, Int>>? {
        val pattern = Regex("^remind me to (.+?) at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$")
        val match = pattern.find(lower) ?: return null
        val task = match.groupValues[1].trim()
        var hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].toIntOrNull() ?: 0
        val meridiem = match.groupValues[4]
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return null
        return Pair(task, Pair(hour, minute))
    }

    private fun detectTimer(lower: String): Int? {
        val pattern = Regex("^set a timer for (\\d+) (minute|minutes|second|seconds)$")
        val match = pattern.find(lower) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        return if (unit.startsWith("second")) 0 else amount
    }

    private fun detectFlashlight(lower: String): Boolean? {
        if (Regex("^turn on (the )?flash(light)?$").matches(lower)) return true
        if (Regex("^turn off (the )?flash(light)?$").matches(lower)) return false
        return null
    }

    private fun detectWifiPanel(lower: String): Boolean? {
        return if (Regex("^(turn on|turn off|toggle|open) (the )?wi-?fi$").matches(lower)) true else null
    }

    private fun detectBluetoothPanel(lower: String): Boolean? {
        return if (Regex("^(turn on|turn off|toggle|open) (the )?bluetooth$").matches(lower)) true else null
    }

    private fun detectListNotes(lower: String): Boolean? {
        val phrases = listOf("what are my notes", "read my notes", "show my notes", "list my notes")
        return if (phrases.any { lower.startsWith(it) }) true else null
    }

    private fun detectNote(original: String, lower: String): String? {
        val triggers = listOf("note that ", "take a note ", "remember that ", "make a note that ")
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                return original.substring(trigger.length).trim()
            }
        }
        return null
    }
}
