package me.chosante.ui.state

/** First free numbered name, using the same trimmed, case-insensitive names as save validation. */
internal fun freeBuildName(
    base: String,
    taken: Set<String>,
): String {
    val trimmed = base.trim()
    if (trimmed.lowercase() !in taken) return trimmed
    var n = 2
    while ("$trimmed ($n)".lowercase() in taken) n++
    return "$trimmed ($n)"
}
