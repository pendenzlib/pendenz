package io.github.d4niella.pendenz.testing

private val NAMES =
    listOf(
        "Ada",
        "Bob",
        "Cleo",
        "Dan",
        "Eve",
        "Fay",
        "Gus",
        "Hedy",
        "Ida",
        "Jon",
        "Kim",
        "Lea",
        "Max",
        "Nia",
        "Oto",
        "Pia",
        "Quin",
        "Rao",
        "Sam",
        "Tia",
        "Uma",
        "Vic",
        "Wen",
        "Xia",
        "Yan",
        "Zoe",
    )

/** A name for the [index]th of many payloads in a test: Ada, Bob and on, then Ada 2, Bob 2 past Zoe. */
fun guest(index: Int): String {
    val round = index / NAMES.size
    return NAMES[index % NAMES.size] + if (round > 0) " ${round + 1}" else ""
}
