package com.brr.app.punchline

import com.brr.app.domain.QuickAddParser
import kotlin.random.Random

/** Génère des messages qui citent la tâche ou réagissent à son contenu (données : design/punchlines.json). */
object ContextualPunchlines {

    fun keywordLines(taskTitle: String): List<String> {
        val n = QuickAddParser.normalize(taskTitle)
        return ContextualData.rules.filter { r -> r.keywords.any { n.contains(it) } }.flatMap { it.lines }
    }

    fun generate(taskTitle: String, minutesLate: Long, snoozeCount: Int, random: Random): String {
        val pool = mutableListOf<String>()
        val keyword = keywordLines(taskTitle).filter { !it.contains("{min}") || minutesLate > 0 }
        // Les phrases propres au sujet de la tâche sont les plus drôles : on les favorise.
        pool += keyword; pool += keyword
        pool += ContextualData.generic
        if (minutesLate > 0) pool += ContextualData.late
        if (snoozeCount >= 2) pool += ContextualData.snooze
        return fill(pool[random.nextInt(pool.size)], taskTitle, minutesLate, snoozeCount)
    }

    fun fill(template: String, taskTitle: String, minutesLate: Long, snoozeCount: Int): String =
        template.replace("{task}", taskTitle.trim())
            .replace("{min}", minutesLate.toString())
            .replace("{snooze}", snoozeCount.toString())
}
