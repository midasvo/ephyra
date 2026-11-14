package dev.jdtech.jellyfin.profilelock

/**
 * CE (#330): an unlock pattern, the dots connected on a 3×3 grid in the order they were drawn. Dots
 * are numbered row by row, 0 top left to 8 bottom right. A pattern connects at least [MIN_DOTS]
 * dots and none twice; it is stored as their numbers joined by dashes (`0-1-2-5-8`), hashed like a
 * PIN.
 */
object ProfilePattern {
    const val SIZE = 3
    const val DOTS = SIZE * SIZE
    const val MIN_DOTS = 4

    fun isValid(dots: List<Int>): Boolean =
        dots.size in MIN_DOTS..DOTS &&
            dots.all { it in 0 until DOTS } &&
            dots.toSet().size == dots.size

    fun serialize(dots: List<Int>): String = dots.joinToString("-")

    /**
     * [dots] with [dot] connected next. As on Android's lock screen, a line straight across an
     * unconnected dot (0 to 2 passes 1, 0 to 8 passes 4) connects that dot first. A dot already in
     * the pattern is not connected again: the line just passes over it.
     */
    fun connect(dots: List<Int>, dot: Int): List<Int> {
        if (dot !in 0 until DOTS || dot in dots) return dots
        val last = dots.lastOrNull() ?: return listOf(dot)
        val between = between(last, dot)
        return if (between != null && between !in dots) dots + between + dot else dots + dot
    }

    /** The dot halfway between [a] and [b] when the line from one to the other crosses one. */
    private fun between(a: Int, b: Int): Int? {
        val rowSum = a / SIZE + b / SIZE
        val columnSum = a % SIZE + b % SIZE
        if (rowSum % 2 != 0 || columnSum % 2 != 0) return null
        return (rowSum / 2) * SIZE + columnSum / 2
    }
}
