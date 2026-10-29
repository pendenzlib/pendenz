package io.github.pendenzlib.store

/** As PostgreSQL reads a LIKE pattern: `%` any run, `_` one character, a backslash escapes the next one. */
internal fun likeRegex(pattern: String): Regex {
    val regex = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        when (val ch = pattern[i]) {
            '\\' -> if (i + 1 < pattern.length) regex.append(Regex.escape(pattern[++i].toString()))
            '%' -> regex.append(".*")
            '_' -> regex.append('.')
            else -> regex.append(Regex.escape(ch.toString()))
        }
        i++
    }
    // `%` and `_` match line breaks too, as in PostgreSQL
    return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL)
}
