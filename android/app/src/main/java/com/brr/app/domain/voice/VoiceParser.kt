package com.brr.app.domain.voice

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Compréhension d'une dictée libre en français → tâches structurées. Déterministe, hors ligne.
 * Port fidèle de web/js/core/nlu.js ; les deux implémentations passent design/fixtures/nlu.json.
 */
object VoiceParser {

    /** Minuscules sans accents, même longueur que le texte NFC (positions alignées). */
    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).map { c ->
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
    }.joinToString("").replace('’', '\'').replace('`', '\'').lowercase()

    /** Plages des moments de la journée : début, fin, heure retenue pour une échéance. */
    val PARTS = mapOf(
        "matin" to Triple(480, 720, 480), "midi" to Triple(720, 840, 720), "apres-midi" to Triple(810, 1080, 810),
        "soir" to Triple(1080, 1350, 1080), "nuit" to Triple(1260, 1410, 1320),
    )
    private val PART_ALIASES = mapOf("apres midi" to "apres-midi", "aprem" to "apres-midi", "soiree" to "soir")
    private val NUMBERS = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "dix" to 10)
    private fun num(s: String): Int = NUMBERS[s] ?: s.toIntOrNull() ?: 0
    private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val MONTHS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")

    private val STOP = setOf("avec", "pour", "dans", "mais", "tout", "toute", "toutes", "tous", "cette", "semaine", "demain",
        "faire", "faut", "faudrait", "absolument", "vraiment", "aussi", "encore", "bien", "peux", "dois", "veux", "aimerais",
        "voudrais", "avant", "apres", "jour", "jours", "chaque", "termine", "terminer", "parce", "rendre", "soir", "matin",
        "midi", "nuit", "aujourd", "aujourd'hui", "leur", "leurs", "notre", "votre", "quelque", "chose", "heure", "heures",
        "minutes", "fois", "plus", "moins", "sont", "etre", "soit", "avoir", "peu")

    private const val ADVERBS = "(?:(?:absolument|vraiment|bien|aussi|encore|surtout|imperativement|d'abord|ensuite|enfin|egalement)\\s+)*"
    private val TRIGGERS = listOf(
        "j'aimerais", "je voudrais", "je veux", "je dois", "il faut que je", "il faudrait que je", "faut que je",
        "j'ai besoin de", "je vais", "je compte", "je pense", "n'oublie pas de", "ne pas oublier de", "penser a",
        "rappelle-moi de", "rappelle moi de", "j'ai a", "je prevois de", "je devrais", "il me faut", "je peux",
        "je dois encore", "ajoute", "ajouter",
    )
    private val TRIGGER_RE = Regex("\\b(?:" + TRIGGERS.joinToString("|") {
        // Concaténation littérale : Regex.replace interpréterait les « \\ » de ADVERBS.
        (if (it.endsWith(" que je")) it.removeSuffix(" que je") + " " + ADVERBS + "que je" else it).replace(" ", "\\s+")
    } + ")\\s+" + ADVERBS)

    private val SUBJ = mapOf("termine" to "terminer", "finisse" to "finir", "fasse" to "faire", "rende" to "rendre", "envoie" to "envoyer",
        "appelle" to "appeler", "aille" to "aller", "prenne" to "prendre", "ecrive" to "écrire", "lise" to "lire", "revise" to "réviser",
        "prepare" to "préparer", "range" to "ranger", "avance" to "avancer", "commence" to "commencer", "reponde" to "répondre",
        "paie" to "payer", "sorte" to "sortir")
    private val STRIP_RE = Regex("\\b(absolument|imperativement|vraiment|urgent|urgente|sans faute|obligatoirement|a tout prix|au plus vite|en priorite|un peu|\\d+ fois|deux fois|trois fois|chaque jour|tous les jours|quotidiennement|chaque soir|tous les soirs|chaque matin|tous les matins)\\b")
    private val INFINITIVE = Regex("^(?:s'|se\\s+)?[a-z-]+(?:er|ir|re|oir)$")
    private val PRONOUN_START = Regex("^(?:(?:en|les|le|la|y|lui|leur|m'en|m'y)\\s+|l')[a-z]")
    private val DETERMINER = Regex("^(?:le|la|les|l'|un|une|des|du|de la|de l'|mon|ma|mes|ton|ta|tes|son|sa|ses|notre|nos|votre|vos|leur|leurs|cet|cette|ces)\\b")
    private val NOT_VERBS = setOf("hier", "soir", "pire", "cher", "dernier", "premier", "entier", "leur", "mer", "fer", "air", "noir", "espoir", "devoir")

    private val URGENT_RE = Regex("\\b(absolument|imperativement|urgent|urgente|urgence|sans faute|obligatoirement|a tout prix|au plus vite|en priorite|prioritaire|crucial|vital)\\b")
    private val LOW_RE = Regex("\\b(si j'ai le temps|eventuellement|pas urgent|pas presse|quand j'aurai le temps|a l'occasion)\\b")
    private val DEADLINE_RE = Regex("\\b(avant|pour|d'ici|au plus tard|a rendre|rendre|rendu|rendue|termine|terminee|termines|fini|finie|boucle|deadline|echeance|limite|doit etre|doivent etre|jusqu'a)\\b[^,.;]*$")
    private val PLAN_RE = Regex("\\b(planifi|organis|planning|emploi du temps|ma semaine|ma journee|cale[rs]?\\b|caser)")
    private val DAILY_RE = Regex("\\b(?:un peu\\s+)?(chaque jour|tous les jours|quotidiennement|chaque soir|tous les soirs|chaque matin|tous les matins)\\b")
    private val COUNT_RE = Regex("\\b(\\d+|deux|trois|quatre|cinq|six)\\s+fois\\b")
    private val LITTLE_RE = Regex("\\bun peu\\b")
    private val DURATION_RE = Regex("\\b(?:(?:pendant|durant|en|prendre|prendra|prend|environ|compter|faut)\\s+)?(?:environ\\s+)?(\\d+|une|un|deux|trois|quatre)\\s*(?:(h|heures?)\\s*(\\d{2})?|(min|minutes?))\\b|\\b(une demi-heure|une demi heure|un quart d'heure)\\b")
    private val DUR_MARK = Regex("^(pendant|durant|en|prendre|prendra|prend|environ|compter|faut)\\b")

    private val TIME_PATTERNS = listOf(
        "rel" to Regex("\\b(aujourd'hui|ce soir|ce matin|cet apres-midi|cet apres midi|cet aprem|cette nuit|ce midi|apres-demain|apres demain|demain)\\b"),
        "weekday" to Regex("\\b(${DAYS.joinToString("|")})(\\s+prochain)?\\b"),
        "date" to Regex("\\ble\\s+(\\d{1,2})(?:er)?(?:\\s+(${MONTHS.joinToString("|")}))?\\b"),
        "in" to Regex("\\bdans\\s+(\\d+|un|une|deux|trois|quatre|cinq)\\s+(jours?|semaines?|heures?|minutes?|min)\\b"),
        "part" to Regex("\\b(?:le\\s+|en\\s+|dans la\\s+|la\\s+)?(apres-midi|apres midi|aprem|matin|midi|soiree|soir|nuit)\\b"),
        "time" to Regex("\\b(?:(a|vers|des|avant|pour|jusqu'a)\\s+)?(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})?(?![\\d])"),
        "scope" to Regex("\\b(cette semaine|ce week-end|ce weekend|la semaine prochaine|semaine prochaine)\\b"),
    )
    private val MERGE_GAP = Regex("^[\\s,]*(?:a|vers|des|en fin de|en debut de|dans la)?\\s*$")

    private class Tok(val type: String, val start: Int, val end: Int, val m: MatchResult)
    private class TimeExpr(
        val kind: String, var date: LocalDate? = null, var part: String? = null, var minute: Int? = null,
        val from: LocalDate? = null, val until: LocalDate? = null, var start: Int = 0, var end: Int = 0,
    )

    private fun g(m: MatchResult, i: Int): String? = m.groups[i]?.value

    private fun scanTimes(n: String, now: Moment, durSpans: List<IntRange>): List<TimeExpr> {
        val raw = mutableListOf<Tok>()
        for ((type, re) in TIME_PATTERNS) for (m in re.findAll(n)) {
            val start = m.range.first; val end = m.range.last + 1
            if (durSpans.any { start < it.last + 1 && end > it.first }) continue
            if (type == "time") {
                val h = g(m, 2)!!.toInt()
                // « 1h30 » sans « à / vers » est presque toujours une durée.
                if (h > 23 || (g(m, 1) == null && h < 7)) continue
            }
            raw += Tok(type, start, end, m)
        }
        raw.sortWith(compareBy<Tok> { it.start }.thenByDescending { it.end - it.start })
        val toks = mutableListOf<Tok>()
        for (t in raw) if (toks.none { t.start < it.end && t.end > it.start }) toks += t

        // Fusion : jour + moment + heure adjacents forment une seule expression.
        val out = mutableListOf<TimeExpr>()
        for (t in toks) {
            val v = resolve(t, now)
            val prev = out.lastOrNull()
            if (prev != null && MERGE_GAP.matches(n.substring(prev.end, t.start)) && canMerge(prev, v)) {
                if (v.date != null && prev.date == null) prev.date = v.date
                if (v.part != null && prev.part == null) prev.part = v.part
                if (v.minute != null && prev.minute == null) prev.minute = v.minute
                prev.end = t.end
                continue
            }
            v.start = t.start; v.end = t.end
            out += v
        }
        return out
    }

    private fun canMerge(a: TimeExpr, b: TimeExpr) = a.kind != "scope" && b.kind != "scope" &&
        !(a.date != null && b.date != null) && !(a.part != null && b.part != null) && !(a.minute != null && b.minute != null)

    private fun resolve(t: Tok, now: Moment): TimeExpr {
        val m = t.m
        return when (t.type) {
            "rel" -> {
                val (d, part) = when (g(m, 1)!!) {
                    "aujourd'hui" -> 0 to null
                    "ce soir" -> 0 to "soir"; "ce matin" -> 0 to "matin"; "cette nuit" -> 0 to "nuit"; "ce midi" -> 0 to "midi"
                    "cet apres-midi", "cet apres midi", "cet aprem" -> 0 to "apres-midi"
                    "apres-demain", "apres demain" -> 2 to null
                    "demain" -> 1 to null
                    else -> 0 to null
                }
                TimeExpr("when", now.date.plusDays(d.toLong()), part)
            }
            "weekday" -> {
                val target = DAYS.indexOf(g(m, 1)) + 1
                var d = (target - now.date.dayOfWeek.value + 7) % 7
                if (g(m, 2) != null && d == 0) d = 7
                TimeExpr("when", now.date.plusDays(d.toLong()))
            }
            "date" -> {
                val day = g(m, 1)!!.toInt()
                if (day !in 1..31) return TimeExpr("none")
                var month = g(m, 2)?.let { MONTHS.indexOf(it) + 1 } ?: now.date.monthValue
                var year = now.date.year
                fun iso() = "%04d-%02d-%02d".format(year, month, day)
                if (iso() < now.date.toString()) { if (g(m, 2) != null) year++ else if (++month > 12) { month = 1; year++ } }
                val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return TimeExpr("none")
                TimeExpr("when", date)
            }
            "in" -> {
                val q = num(g(m, 1)!!); val u = g(m, 2)!!
                when {
                    u.startsWith("jour") -> TimeExpr("when", now.date.plusDays(q.toLong()))
                    u.startsWith("semaine") -> TimeExpr("when", now.date.plusDays(7L * q))
                    else -> {
                        val total = now.minute + if (u.startsWith("h")) q * 60 else q
                        TimeExpr("when", now.date.plusDays((total / 1440).toLong()), minute = total % 1440)
                    }
                }
            }
            "part" -> TimeExpr("when", part = PART_ALIASES[g(m, 1)] ?: g(m, 1))
            "time" -> TimeExpr("when", minute = minOf(23, g(m, 2)!!.toInt()) * 60 + (g(m, 3)?.let { minOf(59, it.toInt()) } ?: 0))
            "scope" -> {
                val w = g(m, 1)!!
                val dow = now.date.dayOfWeek.value
                when {
                    w.contains("prochaine") -> { val mon = now.date.plusDays((8 - dow).toLong()); TimeExpr("scope", from = mon, until = mon.plusDays(6)) }
                    w.contains("week") -> TimeExpr("scope", from = now.date.plusDays(maxOf(0, 6 - dow).toLong()), until = now.date.plusDays((7 - dow).toLong()))
                    else -> TimeExpr("scope", from = now.date, until = now.date.plusDays((7 - dow).toLong()))
                }
            }
            else -> TimeExpr("none")
        }
    }

    private fun scanDurations(n: String): Pair<List<IntRange>, Int?> {
        val spans = mutableListOf<IntRange>(); var minutes: Int? = null
        for (m in DURATION_RE.findAll(n)) {
            val marked = DUR_MARK.containsMatchIn(m.value)
            when {
                g(m, 5) != null -> minutes = if (g(m, 5)!!.contains("quart")) 15 else 30
                g(m, 4) != null -> minutes = num(g(m, 1)!!)
                g(m, 2) != null -> {
                    if (!marked && num(g(m, 1)!!) > 4) continue // « 21h30 » est une heure, « 1h30 » une durée
                    minutes = num(g(m, 1)!!) * 60 + (g(m, 3)?.toInt() ?: 0)
                }
                else -> continue
            }
            spans += m.range
        }
        return spans to minutes
    }

    // ---------- Tâches ----------
    private fun stem(w: String) = w.replace(Regex("(?:s|x)$"), "")
    private fun tokens(n: String) = n.split(Regex("[^a-z0-9'-]+")).map { it.replace(Regex("^(?:l|d|j|m|t|s|qu|n)'"), "") }.filter { it.isNotEmpty() }

    fun keywords(norm: String): List<String> =
        tokens(norm).drop(1).filter { Regex("^\\d+$").matches(it) || (it.length >= 4 && it !in STOP) }.map(::stem)

    private fun kwMatch(a: String, b: String) = a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    private fun matchTask(tasks: List<VoiceTask>, text: String, minRatio: Double): VoiceTask? {
        val words = tokens(text).map(::stem)
        var best: VoiceTask? = null; var bestScore = 0.0
        for (t in tasks) {
            val kws = keywords(t.norm)
            if (kws.isEmpty()) continue
            val hits = kws.count { k -> words.any { kwMatch(it, k) } }
            val ratio = hits.toDouble() / kws.size
            if (hits > 0 && ratio >= minRatio && hits + ratio > bestScore) { best = t; bestScore = hits + ratio }
        }
        return best
    }

    private fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun cleanTitle(orig: String, removeSpans: List<IntRange>): String {
        val chars = orig.toCharArray()
        for (r in removeSpans) for (i in maxOf(0, r.first)..r.last) if (i < chars.size) chars[i] = '\u0000'
        var s = String(chars).replace(Regex("\u0000+"), " ")
        s = s.replace(Regex("\\s+(?:avant d['e]|apr[eè]s avoir|une fois que)\\b.*$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\s,;:]+|[\\s,;:.!?]+$"), "")
            .replace(Regex("\\s+(?:et|puis|ou|de|d'|à|a|pour|avant|vers|dès|le|la|les|en)$", RegexOption.IGNORE_CASE), "").trim()
        return capitalize(s)
    }

    /** Découpe en conservant les séparateurs (comportement de String.split JS avec un groupe capturant). */
    private fun splitKeep(s: String, re: Regex): List<String> {
        val out = mutableListOf<String>(); var last = 0
        for (m in re.findAll(s)) { out += s.substring(last, m.range.first); out += m.value; last = m.range.last + 1 }
        out += s.substring(last)
        return out
    }

    private class Target(val task: VoiceTask, val start: Int, val end: Int, val inherited: Boolean = false)
    private class Item(val start: Int, val end: Int, val verb: String?, val inherit: String?)
    private class PendingDep(val task: VoiceTask, val phrase: String, val after: Boolean)

    fun parse(text: String, now: Moment, learned: Map<String, List<Int>> = emptyMap()): Dictation {
        val orig = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()
        val n = norm(orig)
        val tasks = mutableListOf<VoiceTask>()
        val questions = mutableListOf<String>()
        var globalScope: Horizon? = null
        var seq = 0
        val pendingDeps = mutableListOf<PendingDep>()

        fun newTask(title: String, tn: String) = VoiceTask("t${++seq}", title, tn).also { tasks += it }

        for (sm in Regex("[^.!?;\\n]+[.!?;\\n]*").findAll(orig)) {
            if (sm.value.isBlank()) continue
            val so = orig.substring(sm.range.first, sm.range.last + 1)
            val sn = n.substring(sm.range.first, sm.range.last + 1)
            val (durSpans, durMinutes) = scanDurations(sn)
            val times = scanTimes(sn, now, durSpans)
            times.firstOrNull { it.kind == "scope" }?.let { if (globalScope == null && tasks.isEmpty()) globalScope = Horizon(it.from!!, it.until!!) }

            // 1. Énumérations introduites par une intention (« j'aimerais… », « je dois… »).
            val items = mutableListOf<Item>()
            val starts = TRIGGER_RE.findAll(sn).map { it.range.last + 1 }.toMutableList()
            // Sans formule d'intention : liste directe (« Réviser les maths… », « Planifie ma semaine : … »).
            if (starts.isEmpty()) {
                val cands = listOf(0, sn.indexOf(':') + 1, sn.indexOf(',') + 1).filterIndexed { k, i -> k == 0 || i > 0 }
                cands.map { i -> i + (sn.substring(i).length - sn.substring(i).trimStart().length) }
                    .firstOrNull { i -> val w = sn.substring(i).split(Regex("\\s+"))[0]; i < sn.length && INFINITIVE.matches(w) && w !in NOT_VERBS }
                    ?.let { starts += it }
            }
            for (tailStart in starts) {
                val cut = Regex("\\s*(?:\\bparce que\\b|\\bcar\\b|\\bpuisque\\b|\\bmais\\b|,\\s*ce qui\\b|\\bvu que\\b|\\bdonc\\b)").find(sn.substring(tailStart))?.range?.first
                val tailEnd = if (cut != null) tailStart + cut else sn.length
                val tail = sn.substring(tailStart, tailEnd)
                if (PRONOUN_START.containsMatchIn(tail)) continue // « en faire », « les faire » : renvoie à une tâche déjà citée
                var pos = 0; var prevVerb: String? = null
                val before = sn.substring(0, tailStart)
                val subjCtx = Regex("\\bque\\s+(?:je|j')\\s*$").containsMatchIn(before.trimEnd() + " ") || Regex("que\\s+je\\s+$").containsMatchIn(before)
                for (part in splitKeep(tail, Regex("\\s*,\\s*(?:et\\s+|puis\\s+)?|\\s+(?:et|puis|ainsi que)\\s+"))) {
                    val segStart = tailStart + pos; pos += part.length
                    if (Regex("^\\s*(,|et|puis|ainsi que)").containsMatchIn(part) && Regex("^[\\s,]*(?:et|puis|ainsi que)?[\\s,]*$").matches(part)) continue
                    val afterQue = Regex("^\\s*que\\s+(?:je\\s+|j')").containsMatchIn(part)
                    val trimmed = part.trimStart().replace(Regex("^(?:de|d')\\s*(?=[a-z])"), "")
                        .replace(Regex("^que\\s+(?:je\\s+|j')"), "").replace(Regex("^(?:l'|(?:le|la|les|lui|leur)\\s+)(?=[a-z]+e\\b)"), "")
                    if (trimmed.isEmpty()) continue
                    val lead = part.length - trimmed.length
                    val first = trimmed.split(Regex("\\s+"))[0]
                    // Subjonctif régulier du 1er groupe (« lave », « emmène ») → infinitif.
                    val subj = SUBJ[first] ?: if ((subjCtx || afterQue) && Regex("^[a-z]{3,}e$").matches(first))
                        norm(so.substring(segStart + lead, segStart + lead + first.length)) + "r" else null
                    val isVerb = (INFINITIVE.matches(first) && first !in NOT_VERBS) || subj != null
                    if (!isVerb && !(prevVerb != null && DETERMINER.containsMatchIn(trimmed))) { if (items.isNotEmpty()) break; continue }
                    items += Item(segStart + lead, segStart + part.length, if (isVerb) subj else null, if (isVerb) null else prevVerb)
                    if (isVerb) prevVerb = subj ?: so.substring(segStart + lead, segStart + lead + first.length)
                }
            }

            // 2. Chaque élément devient une tâche, ou se rattache à une tâche existante qu'il cite.
            val targets = mutableListOf<Target>()
            for (it in items) {
                val remove = times.map { t -> (t.start - it.start) until (t.end - it.start) } + durSpans.map { d -> (d.first - it.start)..(d.last - it.start) } +
                    STRIP_RE.findAll(sn.substring(it.start, it.end)).map { m -> m.range }.toList()
                var title = cleanTitle(so.substring(it.start, it.end), remove)
                if (title.isEmpty()) continue
                if (it.verb != null) title = capitalize(Regex("^\\S+").replace(title) { w -> if (it.verb == norm(w.value)) w.value else it.verb })
                if (it.inherit != null) title = capitalize("${it.inherit.lowercase()} ${title.replaceFirstChar { c -> c.lowercase() }}")
                val tn = norm(title)
                val existing = matchTask(tasks, tn, 0.6)
                val target = existing ?: newTask(title, tn)
                // « que je termine mon exposé » : il ne reste qu'une partie du travail.
                if (existing != null && Regex("^(terminer|finir|finaliser|boucler|achever)\\b").containsMatchIn(tn)) existing.finishing = true
                targets += Target(target, it.start, it.end, it.inherit != null)
            }

            // 3. Sans élément explicite : la phrase précise une tâche déjà citée.
            if (targets.isEmpty()) matchTask(tasks, sn, 0.01)?.let { targets += Target(it, 0, sn.length) }

            // 4. Contraintes de la phrase : une indication vise l'élément qui la contient, sinon toute la phrase.
            fun scoped(index: Int?): List<VoiceTask> {
                if (index == null) return emptyList()
                val inside = targets.filter { index >= it.start && index < it.end }
                return (if (targets.size > 1 && inside.isNotEmpty()) inside else targets).map { it.task }
            }
            val daily = DAILY_RE.find(sn)
            for (tm in times) {
                if (tm.kind == "scope") { for (tg in targets) tg.task.horizon = Horizon(tm.from!!, tm.until!!); continue }
                if (tm.kind != "when") continue
                val target = if (targets.size == 1) targets[0].task else targets.firstOrNull { tm.start >= it.start && tm.end <= it.end }?.task
                val beforeTxt = sn.substring(maxOf(0, tm.start - 40), tm.start)
                val isDeadline = DEADLINE_RE.containsMatchIn(beforeTxt)
                // « A, B et C avant vendredi » : l'échéance finale vaut pour toute l'énumération.
                val last = targets.lastOrNull()
                val all = if (isDeadline && targets.size > 1 && target == last?.task && targets.drop(1).all { it.inherited }) targets.map { it.task } else null
                val label = so.substring(tm.start, tm.end).trim()
                if (target == null) {
                    if (isDeadline) questions += "À quelle tâche correspond l'échéance « $label » ?"
                    continue
                }
                val date = tm.date ?: if (tm.minute != null && tm.minute!! < now.minute && tm.part == null) now.date.plusDays(1) else now.date
                if (isDeadline) {
                    val assumed = tm.minute == null
                    // « avant vendredi » (jour seul) : au plus tard la veille au soir.
                    val strictDay = tm.part == null && tm.minute == null && Regex("\\bavant\\b").containsMatchIn(beforeTxt)
                    val dDate = if (strictDay) date.minusDays(1) else date
                    val minute = tm.minute ?: tm.part?.let { PARTS.getValue(it).third } ?: 1439
                    for (t in all ?: listOf(target)) {
                        t.deadline = Deadline(dDate, minute, label, assumed)
                        if (assumed) t.notes += "Hypothèse : « $label » compris comme ${PlanDates.fmtDay(dDate, now.date).lowercase()} à ${PlanDates.fmtTime(minute)}."
                    }
                } else if (daily == null) {
                    val (a, b) = tm.part?.let { PARTS.getValue(it).let { p -> p.first to p.second } } ?: (480 to 1350)
                    target.window = if (tm.minute != null) Window(date, tm.minute!!, tm.minute!!, true, label) else Window(date, a, b, false, label)
                }
            }
            for (t in scoped(URGENT_RE.find(sn)?.range?.first)) t.urgent = true
            for (t in scoped(LOW_RE.find(sn)?.range?.first)) t.low = true
            if (daily != null) for (t in scoped(daily.range.first)) {
                val w = daily.groupValues[1]
                t.recurrence = Recurrence(Recurrence.Type.DAILY, part = if (w.contains("soir")) "soir" else if (w.contains("matin")) "matin" else null)
            }
            val count = COUNT_RE.find(sn)
            if (daily == null && count != null) for (t in scoped(count.range.first)) t.recurrence = Recurrence(Recurrence.Type.COUNT, n = num(count.groupValues[1]))
            for (t in scoped(LITTLE_RE.find(sn)?.range?.first)) t.little = true
            if (durMinutes != null) for (t in scoped(durSpans.last().first)) t.explicitMinutes = durMinutes

            // 5. Dépendances : « après avoir X », « avant de Y » — résolues en fin d'analyse.
            val main = targets.firstOrNull()?.task
            Regex("\\b(?:apres avoir|une fois que j'aurai|une fois que j'ai)\\s+([^,.;]+)").find(sn)?.let { if (main != null) pendingDeps += PendingDep(main, it.groupValues[1], true) }
            Regex("\\bavant de\\s+([^,.;]+)").find(sn)?.let { if (main != null) pendingDeps += PendingDep(main, it.groupValues[1], false) }
        }
        for (d in pendingDeps) {
            val other = matchTask(tasks.filter { it !== d.task }, norm(d.phrase), 0.3) ?: continue
            val (first, then) = if (d.after) other to d.task else d.task to other
            if (first.id !in then.dependsOn) then.dependsOn += first.id
        }

        // 6. Finalisation : priorité, niveau d'alerte, durée, horizon.
        val defaultHorizon = globalScope ?: Horizon(now.date, now.date.plusDays(6))
        for (t in tasks) {
            if (t.horizon == null) t.horizon = defaultHorizon
            val est = DurationEstimator.estimate(t.norm, learned)
            t.estimate = est
            t.minutes = t.explicitMinutes ?: if (t.finishing) maxOf(15, Math.round(est.minutes * 0.6 / 5).toInt() * 5) else est.minutes
            if (t.finishing && t.explicitMinutes == null) t.notes += "Il ne reste qu'à terminer : estimation réduite."
            t.explicitMinutes?.let { t.notes += "Durée indiquée : $it min." }
            t.sessionMinutes = if (t.recurrence?.type == Recurrence.Type.DAILY)
                t.explicitMinutes ?: maxOf(25, Math.round((if (t.little) est.min * 0.75 else est.minutes.toDouble()) / 5).toInt() * 5) else null
            val hoursLeft = t.deadline?.let { ChronoUnit.DAYS.between(now.date, it.date) * 24 + (it.minute - now.minute) / 60.0 } ?: Double.POSITIVE_INFINITY
            t.priority = when { t.urgent -> Priority.URGENT; hoursLeft <= 48 -> Priority.HIGH; t.low -> Priority.LOW; else -> Priority.NORMAL }
            t.level = when { t.urgent && hoursLeft <= 24 -> ReminderLevel.BRR; t.urgent -> ReminderLevel.HARCELEMENT; t.low -> ReminderLevel.NORMAL; else -> ReminderLevel.INSISTANT }
            if (t.level == ReminderLevel.BRR) t.notes += "Échéance explicitement urgente : Bully Mode: Nuclear proposé."
            val dl = t.deadline; val w = t.window
            if (dl != null && w != null && (w.date > dl.date || (w.date == dl.date && w.start >= dl.minute))) {
                t.notes += "Le créneau souhaité tombe après l'échéance : il sera ignoré."
                t.window = null
            }
            if (t.deadline == null && t.window == null && t.recurrence == null) t.notes += "Aucune échéance donnée : placée selon tes disponibilités."
        }
        return Dictation(tasks, questions, PLAN_RE.containsMatchIn(n) || tasks.size > 1)
    }
}
