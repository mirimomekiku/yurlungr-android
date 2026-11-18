package dev.yurlungr.app.voice

/** What a spoken phrase means in hands-free mode. */
enum class VoiceCommand { Allow, Deny, Stop, None }

/**
 * Recognizes the few things said to the app itself (allow, deny, stop) rather than to the agent.
 * Only a whole short utterance counts, so "stop the dev server" still goes to the agent as a
 * message. Pure, so it's unit-tested.
 */
object VoiceCommands {
    private val STOP = setOf(
        "stop", "stop listening", "stop hands free", "stop handsfree", "hands free off", "handsfree off",
        "turn off hands free", "end hands free", "exit", "quit", "that's all", "thats all", "that is all",
        "goodbye", "bye", "never mind", "nevermind", "i'm done", "im done", "we're done", "were done",
    )
    private val ALLOW = setOf(
        "allow", "allow it", "allow once", "allow this", "allow this once", "allow that", "yes", "yeah", "yep", "yup",
        "yes allow", "yes please", "ok", "okay", "sure", "approve", "approved", "approve it", "go ahead", "do it",
        "accept", "confirm", "run it", "go", "fine", "alright", "all right", "permit", "proceed",
    )
    private val DENY = setOf(
        "deny", "deny it", "deny once", "deny this", "deny that", "no", "nope", "nah", "no thanks", "no way",
        "don't", "dont", "do not", "don't do it", "dont do it", "do not do it", "reject", "decline", "denied",
        "cancel", "refuse", "skip", "skip it", "don't allow", "dont allow", "do not allow", "not allowed",
    )
    /** Politeness and hesitation around a command don't change it. */
    private val FILLERS = setOf("please", "um", "uh", "er", "hmm", "hey", "claude", "now", "thanks", "thank", "you")
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}' ]+")

    /**
     * The command in [alternatives] (a recognizer's guesses, best first), or [VoiceCommand.None].
     * [answering]: a permission prompt is waiting, so allow/deny words count; otherwise "cancel"
     * and similar end hands-free instead and everything else is a message.
     */
    fun parse(alternatives: List<String>, answering: Boolean): VoiceCommand {
        for (text in alternatives) {
            val c = parse(text, answering)
            if (c != VoiceCommand.None) return c
        }
        return VoiceCommand.None
    }

    fun parse(text: String, answering: Boolean): VoiceCommand {
        val phrase = normalize(text)
        if (phrase.isEmpty()) return VoiceCommand.None
        if (phrase in STOP) return VoiceCommand.Stop
        if (answering) {
            // Deny first: "don't allow" contains an allow word but means no.
            if (phrase in DENY) return VoiceCommand.Deny
            if (phrase in ALLOW) return VoiceCommand.Allow
        } else if (phrase == "cancel" || phrase == "stop it" || phrase == "off") {
            return VoiceCommand.Stop
        }
        return VoiceCommand.None
    }

    /** Lowercase words, punctuation and fillers dropped, "hands-free" → "hands free". */
    internal fun normalize(text: String): String {
        val words = PUNCTUATION.replace(text.lowercase().replace('’', '\'').replace('-', ' '), " ")
            .split(' ').filter { it.isNotBlank() }
        // Fillers only around the command, so "thank you" alone stays empty rather than matching.
        val trimmed = words.dropWhile { it in FILLERS }.dropLastWhile { it in FILLERS }
        return trimmed.joinToString(" ")
    }
}
