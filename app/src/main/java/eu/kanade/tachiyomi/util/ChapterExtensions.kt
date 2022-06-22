package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlin.math.floor

/**
 * Calculate the missing chapters for a given list of chapters. Return null if none are missing
 */
fun List<SChapter>.getMissingChapterCount(mangaStatus: Int): String? {
    if (mangaStatus == SManga.COMPLETED) return null

    val chapterNumberArray = this.asSequence().distinctBy {
        if (it.chapter_txt.isNotEmpty()) {
            it.vol + it.chapter_txt
        } else {
            it.name
        }
    }.sortedBy { it.chapter_number }
        .map { floor(it.chapter_number).toInt() }.toList().toIntArray()

    var count = 0

    if (chapterNumberArray.isNotEmpty()) {
        chapterNumberArray.forEachIndexed { index, chpNum ->
            val lastIndex = index - 1
            if (lastIndex >= 0 && (chpNum - 1) > chapterNumberArray[lastIndex]) {
                count += (chpNum - chapterNumberArray[lastIndex]) - 1
            }

        }
    }

    if (count <= 0) return null

    return count.toString()
}
