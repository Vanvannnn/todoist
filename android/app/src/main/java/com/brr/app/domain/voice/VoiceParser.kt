package com.brr.app.domain.voice

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Compréhension d'une dictée libre en français → tâches structurées. Déterministe, hors ligne.
 * Port fidèle de web/js/core/nlu.js ; les deux implémentations passent design/fixtures/nlu.json et nlu-conversation.json.
 * C'est aussi le mode de secours quand le moteur IA est désactivé ou injoignable.
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
    private val NUMBERS = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "dix" to 10, "quinze" to 15, "trente" to 30)
    private fun num(s: String): Int = NUMBERS[s] ?: s.toIntOrNull() ?: 0
    private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val MONTHS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")

    private val STOP = setOf("avec", "pour", "dans", "mais", "tout", "toute", "toutes", "tous", "cette", "semaine", "demain",
        "faire", "faut", "faudrait", "absolument", "vraiment", "aussi", "encore", "bien", "peux", "dois", "veux", "aimerais",
        "voudrais", "avant", "apres", "jour", "jours", "chaque", "termine", "terminer", "parce", "rendre", "soir", "matin",
        "midi", "nuit", "aujourd", "aujourd'hui", "leur", "leurs", "notre", "votre", "quelque", "chose", "heure", "heures",
        "minutes", "fois", "plus", "moins", "sont", "etre", "soit", "avoir", "peu")

    private const val ADVERBS = "(?:(?:absolument|vraiment|bien|aussi|encore|surtout|imperativement|d'abord|ensuite|enfin|egalement|quand meme|quand-meme)\\s+)*"
    // « je fais les courses », « je révise », « je rédigerai l'introduction » : présent et futur valent une intention.
    private val PRESENT_RE = Regex("\\bje\\s+(?!(?:pense|crois|trouve|sais|veux|dois|peux|vais|compte|souhaite|prevois|devrais|aime|deteste|suppose|imagine|ai|suis|me|te|ne|l|les|la|le|lui|leur|en|y|voudrais|aimerais|dis|parle|reste|serai|aurai|serais|aurais)\\b)(?=[a-z]{3,}\\b)")
    private val PRESENT_IRREGULAR = mapOf("fais" to "faire", "lis" to "lire", "ecris" to "écrire", "finis" to "finir", "rends" to "rendre", "vois" to "voir",
        "prends" to "prendre", "mets" to "mettre", "sors" to "sortir", "cours" to "courir", "dors" to "dormir", "ferai" to "faire", "irai" to "aller",
        "lirai" to "lire", "ecrirai" to "écrire", "verrai" to "voir")
    /** Forme conjuguée → infinitif (orig garde les accents : « révise » → « réviser »). */
    private fun presentVerb(n: String, orig: String): String? = PRESENT_IRREGULAR[n] ?: when {
        Regex("^[a-z]{2,}(?:erai|irai)$").matches(n) -> orig.dropLast(2)
        Regex("^[a-z]{2,}rai$").matches(n) -> orig.dropLast(2) + "e"
        Regex("^[a-z]{2,}e$").matches(n) -> orig + "r"
        else -> null
    }
    // Participes après « après avoir » : « fini les recherches » → « Finir les recherches ».
    private val PARTICIPLE = mapOf("fini" to "finir", "fait" to "faire", "lu" to "lire", "ecrit" to "écrire", "pris" to "prendre", "rendu" to "rendre", "vu" to "voir", "mis" to "mettre")
    private val TRIGGERS = listOf(
        "j'aimerais", "je voudrais", "je veux", "je dois", "il faut que je", "il faudrait que je", "faut que je",
        "j'ai besoin de", "je vais", "je compte", "je pense", "n'oublie pas de", "ne pas oublier de", "penser a",
        "rappelle-moi de", "rappelle moi de", "j'ai a", "je prevois de", "je devrais", "il me faut", "je peux",
        "je dois encore", "ajoute", "ajouter", "n'oublie pas d'", "ne pas oublier d'", "j'ai envie de", "je souhaite",
    )
    private val TRIGGER_RE = Regex("\\b(?:" + TRIGGERS.joinToString("|") {
        // Concaténation littérale : Regex.replace interpréterait les « \\ » de ADVERBS.
        (if (it.endsWith(" que je")) it.removeSuffix(" que je") + " " + ADVERBS + "que je" else it).replace(" ", "\\s+")
    } + ")(?:\\s*,?\\s+|(?<='))" + ADVERBS)

    private val SUBJ = mapOf("termine" to "terminer", "finisse" to "finir", "fasse" to "faire", "rende" to "rendre", "envoie" to "envoyer",
        "appelle" to "appeler", "aille" to "aller", "prenne" to "prendre", "ecrive" to "écrire", "lise" to "lire", "revise" to "réviser",
        "prepare" to "préparer", "range" to "ranger", "avance" to "avancer", "commence" to "commencer", "reponde" to "répondre",
        "paie" to "payer", "sorte" to "sortir")
    private val STRIP_RE = Regex("\\b(absolument|imperativement|vraiment|urgent|urgente|sans faute|obligatoirement|a tout prix|au plus vite|en priorite|un peu|\\d+ fois|deux fois|trois fois|chaque jour|tous les jours|quotidiennement|chaque soir|tous les soirs|chaque matin|tous les matins|aussi|egalement)\\b")
    // « prévois les révisions », « ajoute le rendez-vous » : l'impératif peut introduire un simple groupe nominal.
    private val NOUN_TRIGGER_RE = Regex("\\b(?:prevois|planifie|programme|cale|ajoute|note)\\s+(?:moi\\s+)?(?!(?:ma|la|cette|notre|mon)\\s+(?:semaine|journee|matinee|soiree|planning|emploi)\\b)")
    // « (que je) prévoie un peu de temps pour relire… » : la tâche est ce qui suit.
    private val TIME_FOR_RE = Regex("^(?:prevoie|prevoir|prendre|garder|trouver|bloquer)\\s+(?:(?:un peu de|un peu|du|le|un)\\s+)?temps\\s+(?:pour|de)\\s+")
    // « mettre le rendez-vous chez le dentiste » : le verbe ne fait que demander l'ajout.
    private val META_VERB_RE = Regex("^(?:mettre|ajouter|noter|inscrire|programmer|planifier|prevoir)\\s+(?:(?:le|la|les|un|une|des|du|mon|ma|mes)\\s+|l')(?=\\S)")
    // Conversation : corrections, déplacements, annulations, indisponibilités.
    private val CORR_SPLIT_RE = Regex(",\\s*(?=(?:enfin non|ah non|non,? plutot|ou plutot)\\b)")
    private val CORR_RE = Regex("^[\\s,]*(?:(?:ah|oh|euh|bon|et|mais)[\\s,]+)*(?:enfin non|non|en fait|finalement|plutot|ou plutot|je me suis trompe|attends|correction)\\b")
    private val MOVE_RE = Regex("\\b(?:deplace|decale|reporte|mets|met|passe|bascule)\\b")
    // « j'ai oublié quelque chose », « n'oublie pas de » ne sont pas des annulations.
    private val CANCEL_RE = Regex("(?<!n'|j'ai |j'avais )\\b(?:annule|supprime|enleve|retire|laisse tomber|oublie)\\b(?!\\s+(?:quelque|de\\b|d'|qu))")
    private val SAUF_RE = Regex("\\bsauf\\s+(?:le\\s+)?(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)s?\\b")
    private val START_ONLY_RE = Regex("^(?:commencer|attaquer|demarrer|debuter|m'y mettre|m'y atteler)$")
    private val HAND_IN_RE = Regex("^(?:rendre|remettre|deposer|soumettre|livrer)\\b")
    private val FILLER_RE = Regex("\\b(?:euh+|heu+|hum+|bah)\\b[,.]?\\s*", RegexOption.IGNORE_CASE)
    private val BUSY_RE = Regex("\\b(?:j'ai|je suis (?:en|au|a la|a l'|chez)|je travaille|je bosse)\\s+(?:(?:un|une|le|la|mon|ma|des|du)\\s+)?(?:cours|td|tp|travail|boulot|rendez-vous|rdv|reunion|examen|partiel|stage|sport|entrainement|match|dentiste|medecin|kine)\\b")
    private val UNAVAIL_RE = Regex("\\b(?:pas disponible|pas dispo|indisponible|pas libre|je suis pris|je suis prise|ne me (?:mets|met|prevois|planifie) rien)\\b")
    // Jour cité en tête de phrase (« Et dimanche, je veux… ») : il vaut pour toute la phrase et les suivantes.
    private val HEAD_GAP_RE = Regex("^[\\s,]*(?:(?:et|alors|ah|oh|puis|ensuite|bon|sinon|aussi|oui|donc|pour|quant a)[\\s,]+)*$")
    // « ce que j'ai fait mercredi » : un jour passé cité en référence, pas une date de planification.
    private val PAST_REF_RE = Regex("\\b(?:j'ai|on a|j'avais|tu as)\\s+\\S+\\s*$")
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
    // « une heure et demie », « deux heures et quart », « une heure trente ».
    private val DURATION_HALF_RE = Regex("\\b(?:(?:pendant|durant|environ|en)\\s+)?(\\d+|une|un|deux|trois|quatre)\\s+heures?\\s+(?:et\\s+(demie|quart)|(trente|quinze|quarante-cinq|\\d{2}))\\b")
    private val DUR_MARK = Regex("^(pendant|durant|en|prendre|prendra|prend|environ|compter|faut)\\b")

    private val TIME_PATTERNS = listOf(
        "rel" to Regex("\\b(aujourd'hui|ce soir|ce matin|cet apres-midi|cet apres midi|cet aprem|cette nuit|ce midi|apres-demain|apres demain|demain)\\b"),
        "weekday" to Regex("\\b(${DAYS.joinToString("|")})(\\s+prochain)?\\b"),
        "date" to Regex("\\ble\\s+(\\d{1,2})(?:er)?(?:\\s+(${MONTHS.joinToString("|")}))?\\b"),
        "in" to Regex("\\bdans\\s+(\\d+|un|une|deux|trois|quatre|cinq|huit|dix|quinze)\\s+(jours?|semaines?|heures?|minutes?|min)\\b"),
        "part" to Regex("\\b(?:le\\s+|en\\s+|dans la\\s+|la\\s+)?(apres-midi|apres midi|aprem|matin|midi|soiree|soir|nuit)\\b"),
        "time" to Regex("\\b(?:(a|vers|des|avant|pour|jusqu'a)\\s+)?(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})?(?![\\d])"),
        "scope" to Regex("\\b(cette semaine|ce week-end|ce weekend|la semaine prochaine|semaine prochaine)\\b"),
    )
    private val MERGE_GAP = Regex("^[\\s,]*(?:a|vers|des|en fin de|en debut de|dans la)?\\s*$")

    private class Tok(val type: String, val start: Int, val end: Int, val m: MatchResult)
    private class TimeExpr(
        val kind: String, var date: LocalDate? = null, var part: String? = null, var minute: Int? = null,
        val from: LocalDate? = null, val until: LocalDate? = null, var start: Int = 0, var end: Int = 0, var sameDay: Boolean = false,
    )
    /** Jour de la « section » en cours : « Mardi, … » vaut pour les phrases suivantes. */
    private class DayCtx(val date: LocalDate, val part: String?, val label: String, val sameDay: Boolean)
    private class Dur(val start: Int, val end: Int, val minutes: Int)

    private fun g(m: MatchResult, i: Int): String? = m.groups[i]?.value

    private fun scanTimes(n: String, now: Moment, durSpans: List<IntRange>): List<TimeExpr> {
        val raw = mutableListOf<Tok>()
        for ((type, re) in TIME_PATTERNS) for (m in re.findAll(n)) {
            val start = m.range.first; val end = m.range.last + 1
            if (durSpans.any { start < it.last + 1 && end > it.first }) continue
            if ((type == "weekday" || type == "date" || type == "rel") && PAST_REF_RE.containsMatchIn(n.substring(maxOf(0, start - 30), start))) continue
            if (type == "weekday" && Regex("\\bsauf\\s+(?:le\\s+)?$").containsMatchIn(n.substring(maxOf(0, start - 10), start))) continue // « sauf mercredi »
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
                if (v.date != null && prev.date == null) { prev.date = v.date; prev.sameDay = v.sameDay }
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
                TimeExpr("when", now.date.plusDays(d.toLong()), sameDay = d == 0)
            }
            "date" -> {
                val day = g(m, 1)!!.toInt()
                if (day !in 1..31) return TimeExpr("none")
                var month = g(m, 2)?.let { MONTHS.indexOf(it) + 1 } ?: now.date.monthValue
                var year = now.date.year
                fun iso() = "%04d-%02d-%02d".format(year, month, day)
                // Un mois cité explicitement et déjà passé : on le signale au lieu de déplacer la date en silence.
                if (g(m, 2) != null && iso() < now.date.toString()) return TimeExpr("past")
                if (iso() < now.date.toString() && ++month > 12) { month = 1; year++ }
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

    private fun scanDurations(n: String): List<Dur> {
        val list = mutableListOf<Dur>()
        for (m in DURATION_HALF_RE.findAll(n)) {
            if (Regex("\\b(?:a|vers|des|avant|apres|jusqu'a)\\s+$").containsMatchIn(n.substring(maxOf(0, m.range.first - 12), m.range.first))) continue // « à 10 heures et demie »
            val extra = g(m, 2)?.let { if (it == "demie") 30 else 15 } ?: when (val x = g(m, 3)!!) { "trente" -> 30; "quinze" -> 15; "quarante-cinq" -> 45; else -> x.toInt() }
            list += Dur(m.range.first, m.range.last + 1, num(g(m, 1)!!) * 60 + extra)
        }
        for (m in DURATION_RE.findAll(n)) {
            val start = m.range.first; val end = m.range.last + 1
            if (list.any { start < it.end && end > it.start }) continue
            val marked = DUR_MARK.containsMatchIn(m.value)
            val minutes = when {
                g(m, 5) != null -> if (g(m, 5)!!.contains("quart")) 15 else 30
                g(m, 4) != null -> num(g(m, 1)!!)
                g(m, 2) != null -> {
                    if (!marked && num(g(m, 1)!!) > 4) continue // « 21h30 » est une heure, « 1h30 » une durée
                    num(g(m, 1)!!) * 60 + (g(m, 3)?.toInt() ?: 0)
                }
                else -> continue
            }
            list += Dur(start, end, minutes)
        }
        list.sortBy { it.start }
        return list
    }

    // ---------- Tâches ----------
    private fun stem(w: String) = w.replace(Regex("(?:s|x)$"), "")
    private fun tokens(n: String) = n.split(Regex("[^a-z0-9'-]+")).map { it.replace(Regex("^(?:l|d|j|m|t|s|qu|n)'"), "") }.filter { it.isNotEmpty() }

    fun keywords(norm: String): List<String> =
        tokens(norm).drop(1).filter { Regex("^\\d+$").matches(it) || (it.length >= 4 && it !in STOP) }.map(::stem)

    private fun kwMatch(a: String, b: String) = a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    private fun scoreOf(t: VoiceTask, words: List<String>, minRatio: Double): Double? {
        val kws = keywords(t.norm)
        if (kws.isEmpty()) return null
        val hits = kws.count { k -> words.any { kwMatch(it, k) } }
        val ratio = hits.toDouble() / kws.size
        return if (hits > 0 && ratio >= minRatio) hits + ratio else null
    }

    private fun matchTask(tasks: List<VoiceTask>, text: String, minRatio: Double): VoiceTask? {
        val words = tokens(text).map(::stem)
        var best: VoiceTask? = null; var bestScore = 0.0
        for (t in tasks) { val sc = scoreOf(t, words, minRatio) ?: continue; if (sc > bestScore) { best = t; bestScore = sc } }
        return best
    }

    /** Toutes les tâches à égalité avec la meilleure correspondance (« le dossier » vise chaque séance du dossier). */
    private fun matchTasks(tasks: List<VoiceTask>, text: String, minRatio: Double): List<VoiceTask> {
        val words = tokens(text).map(::stem)
        val scored = tasks.mapNotNull { t -> scoreOf(t, words, minRatio)?.let { t to it } }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }

    /** Plage horaire d'une indisponibilité dans une proposition (« après 18 heures », « de 14h à 16h », « le matin »). */
    private fun busyRange(clause: String, part: String?): Pair<Int, Int>? {
        fun i(s: String?) = s?.toIntOrNull() ?: 0
        Regex("\\b(?:de|entre)\\s+(\\d{1,2})\\s*(?:h|heures?)\\s*(\\d{2})?\\s+(?:a|et)\\s+(\\d{1,2})\\s*(?:h|heures?)\\s*(\\d{2})?").find(clause)
            ?.let { return i(g(it, 1)) * 60 + i(g(it, 2)) to i(g(it, 3)) * 60 + i(g(it, 4)) }
        Regex("\\b(?:apres|a partir de|des)\\s+(\\d{1,2})\\s*(?:h|heures?)\\s*(\\d{2})?").find(clause)?.let { return i(g(it, 1)) * 60 + i(g(it, 2)) to 1440 }
        Regex("\\b(?:avant|jusqu'a)\\s+(\\d{1,2})\\s*(?:h|heures?)\\s*(\\d{2})?").find(clause)?.let { return 0 to i(g(it, 1)) * 60 + i(g(it, 2)) }
        if (Regex("\\btoute la journee\\b").containsMatchIn(clause)) return 0 to 1440
        return part?.let { PARTS.getValue(it).let { p -> p.first to p.second } }
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

    private fun sub(s: String, start: Int, len: Int) = s.substring(start, minOf(s.length, start + len))

    private class Target(val task: VoiceTask, val start: Int, val end: Int, val inherited: Boolean = false, val fresh: Boolean = false, val whole: Boolean = false)
    private class Item(val start: Int, val end: Int, val verb: String?, val inherit: String?, val noun: Boolean)
    private class PendingDep(val task: VoiceTask, val phrase: String, val after: Boolean)

    private fun window(date: LocalDate, tm: TimeExpr?, part: String?, label: String): Window {
        val (a, b) = part?.let { PARTS.getValue(it).let { p -> p.first to p.second } } ?: (480 to 1350)
        val minute = tm?.minute
        return if (minute != null) Window(date, minute, minute, true, label) else Window(date, a, b, false, label)
    }

    fun parse(text: String, now: Moment, learned: Map<String, List<Int>> = emptyMap()): Dictation {
        val orig = Normalizer.normalize(text, Normalizer.Form.NFC).replace(FILLER_RE, "").replace(Regex("\\s+"), " ").trim()
        val n = norm(orig)
        val tasks = mutableListOf<VoiceTask>()
        val questions = mutableListOf<String>()
        val constraints = mutableListOf<Constraint>()
        var globalScope: Horizon? = null
        var seq = 0
        val pendingDeps = mutableListOf<PendingDep>()
        var dayCtx: DayCtx? = null
        var lastTargets: List<VoiceTask> = emptyList()

        fun newTask(title: String, tn: String) = VoiceTask("t${++seq}", title, tn).also { tasks += it }
        fun setWindow(task: VoiceTask, w: Window, sameDay: Boolean) {
            task.window = w
            if (sameDay && task.notes.none { it.startsWith("Hypothèse : « " + w.label) })
                task.notes += "Hypothèse : « ${w.label} » compris comme aujourd'hui. Dis « ${w.label} prochain » pour la semaine suivante."
        }

        val sentences = mutableListOf<IntRange>()
        for (sm in Regex("[^.!?;\\n]+[.!?;\\n]*").findAll(orig)) {
            if (sm.value.isBlank()) continue
            // « deux heures, enfin non, une heure trente » : la correction forme sa propre phrase.
            var a = sm.range.first
            for (c in CORR_SPLIT_RE.findAll(n.substring(sm.range.first, sm.range.last + 1))) {
                sentences += a until sm.range.first + c.range.first; a = sm.range.first + c.range.first + 1
            }
            sentences += a..sm.range.last
        }

        for (sr in sentences) {
            val so = orig.substring(sr.first, sr.last + 1)
            val sn = n.substring(sr.first, sr.last + 1)
            val durList = scanDurations(sn)
            val durSpans = durList.map { it.start until it.end }
            val durMinutes = durList.lastOrNull()?.minutes
            val times = scanTimes(sn, now, durSpans)
            val scope = times.firstOrNull { it.kind == "scope" }
            if (scope != null && globalScope == null && tasks.isEmpty()) globalScope = Horizon(scope.from!!, scope.until!!)
            if (scope != null) dayCtx = null
            for (t in times.filter { it.kind == "past" }) questions += "« ${so.substring(t.start, t.end).trim()} » est déjà passé : quelle date voulais-tu dire ?"
            val dated = times.filter { it.kind == "when" && it.date != null }
            val head = dated.firstOrNull { HEAD_GAP_RE.matches(sn.substring(0, it.start)) && !DEADLINE_RE.containsMatchIn(sn.substring(0, it.start)) }
            val used = mutableSetOf<TimeExpr>() // expressions temporelles déjà consommées (indisponibilité)

            // 0. Indisponibilités : « mardi j'ai cours le matin », « mercredi, ne me mets rien après 18 heures ».
            val busy = BUSY_RE.find(sn) ?: UNAVAIL_RE.find(sn)
            if (busy != null) {
                val bi = busy.range.first
                val cStart = sn.lastIndexOf(',', bi) + 1
                val cEndRel = Regex(",|\\bdonc\\b|\\bmais\\b|\\balors\\b").find(sn.substring(bi))?.range?.first
                val cEnd = if (cEndRel != null) bi + cEndRel else sn.length
                val inClause = times.filter { it.kind == "when" && it.start >= cStart && it.end <= cEnd }
                used += inClause
                val date = inClause.firstOrNull { it.date != null }?.date ?: head?.date ?: dayCtx?.date
                val part = inClause.firstOrNull { it.part != null }?.part ?: if (head != null && inClause.isEmpty()) head.part else null
                val range = busyRange(sn.substring(cStart, cEnd), part) ?: if (UNAVAIL_RE.containsMatchIn(busy.value)) 0 to 1440 else null
                if (date != null && range != null) constraints += Constraint(date, range.first, minOf(range.second, 1440),
                    capitalize(so.substring(bi, cEnd).replace(Regex("[\\s,.;!?]+$"), "").trim()))
                else if (date == null) questions += "Quel jour correspond « ${so.substring(bi, cEnd).trim()} » ?"
            }

            // 1. Énumérations introduites par une intention (« j'aimerais… », « je dois… »).
            val items = mutableListOf<Item>()
            val starts = TRIGGER_RE.findAll(sn).map { it.range.last + 1 }.toMutableList()
            val nounStarts = NOUN_TRIGGER_RE.findAll(sn).map { it.range.last + 1 }.toSet()
            starts += nounStarts.filter { it !in starts }
            val presentStarts = PRESENT_RE.findAll(sn).map { it.range.last + 1 }.filter { it !in starts }.toSet()
            starts += presentStarts
            starts.sort()
            // Sans formule d'intention : liste directe (« Réviser les maths… », « Planifie ma semaine : … »).
            if (starts.isEmpty()) {
                val cands = listOf(0, sn.indexOf(':') + 1, sn.indexOf(',') + 1).filterIndexed { k, i -> k == 0 || i > 0 }
                cands.map { i -> i + (sn.substring(i).length - sn.substring(i).trimStart().length) }
                    .firstOrNull { i -> val w = sn.substring(i).split(Regex("\\s+"))[0]; i < sn.length && INFINITIVE.matches(w) && w !in NOT_VERBS }
                    ?.let { starts += it }
            }
            for (s0 in starts) {
                var tailStart = s0
                val nounOk = s0 in nounStarts
                TIME_FOR_RE.find(sn.substring(tailStart))?.let { tailStart += it.value.length }
                val cut = Regex("\\s*(?:\\bparce que\\b|\\bcar\\b|\\bpuisque\\b|\\bmais\\b|,\\s*ce qui\\b|\\bvu que\\b|\\bdonc\\b)").find(sn.substring(tailStart))?.range?.first
                val tailEnd = if (cut != null) tailStart + cut else sn.length
                val tail = sn.substring(tailStart, tailEnd)
                if (!nounOk && PRONOUN_START.containsMatchIn(tail)) continue // « en faire », « les faire » : renvoie à une tâche déjà citée
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
                    val first = trimmed.split(Regex("\\s+"))[0].replace(Regex("[.!?;:,]+$"), "")
                    val pres = if (tailStart in presentStarts && segStart + lead == tailStart) presentVerb(first, sub(so, segStart + lead, first.length)) else null
                    // Subjonctif régulier du 1er groupe (« lave », « emmène ») → infinitif.
                    val subj = SUBJ[first] ?: pres ?: if ((subjCtx || afterQue) && Regex("^[a-z]{3,}e$").matches(first))
                        norm(sub(so, segStart + lead, first.length)) + "r" else null
                    val isVerb = (INFINITIVE.matches(first) && first !in NOT_VERBS) || subj != null
                    val noun = !isVerb && prevVerb == null && nounOk && DETERMINER.containsMatchIn(trimmed)
                    if (!isVerb && !noun && !(prevVerb != null && DETERMINER.containsMatchIn(trimmed))) { if (items.isNotEmpty()) break; continue }
                    items += Item(segStart + lead, segStart + part.length, if (isVerb) subj else null, if (isVerb) null else prevVerb, noun)
                    if (isVerb) prevVerb = subj ?: sub(so, segStart + lead, first.length)
                }
            }

            // 2. Chaque élément devient une tâche, ou se rattache à une tâche existante qu'il cite.
            // Jour qui vaut pour les tâches de la phrase : celui cité en tête, sinon celui de la section en cours.
            val headCtx = head?.let { DayCtx(it.date!!, it.part, so.substring(it.start, it.end).trim(), it.sameDay) }
            val sentenceDay: DayCtx? = headCtx ?: if (dated.any { it !in used }) null else dayCtx
            val sentenceDayIsHead = headCtx != null
            val targets = mutableListOf<Target>()
            for (it in items) {
                val remove = times.map { t -> (t.start - it.start) until (t.end - it.start) } + durSpans.map { d -> (d.first - it.start)..(d.last - it.start) } +
                    STRIP_RE.findAll(sn.substring(it.start, it.end)).map { m -> m.range }.toList()
                var title = cleanTitle(so.substring(it.start, it.end), remove)
                if (title.isEmpty()) continue
                if (it.verb != null) title = capitalize(Regex("^\\S+").replace(title) { w -> if (it.verb == norm(w.value)) w.value else it.verb })
                if (it.inherit != null) title = capitalize("${it.inherit.lowercase()} ${title.replaceFirstChar { c -> c.lowercase() }}")
                val meta = (if (it.noun) DETERMINER else META_VERB_RE).find(norm(title))
                if (meta != null && title.length > meta.value.length + 2) title = capitalize(title.substring(meta.value.length).trimStart())
                val tn = norm(title)
                // « mais je veux commencer mardi » : pas une nouvelle tâche, le jour de début de celle qui vient d'être citée.
                if (START_ONLY_RE.matches(tn)) {
                    val ref = targets.firstOrNull()?.task ?: lastTargets.firstOrNull()
                    if (ref != null) { targets += Target(ref, it.start, it.end); continue }
                }
                var existing = matchTask(tasks, tn, 0.6)
                // « avancer le dossier » mardi puis « terminer le dossier » jeudi : deux séances distinctes, la seconde après la première.
                val splitDay = existing != null && sentenceDay != null && existing.window != null && existing.window!!.date != sentenceDay.date
                if (splitDay) existing = null
                // Tâche créée d'après « après avoir fini les recherches » puis dite explicitement : la formulation explicite l'emporte.
                if (existing != null && existing.fromDep) { existing.title = title; existing.norm = tn; existing.fromDep = false }
                val target = existing ?: newTask(title, tn)
                if (splitDay) matchTask(tasks.filter { x -> x !== target }, tn, 0.6)?.let { p -> target.dependsOn += p.id }
                // « que je termine mon exposé » : il ne reste qu'une partie du travail.
                if ((existing != null || splitDay) && Regex("^(terminer|finir|finaliser|boucler|achever)\\b").containsMatchIn(tn)) target.finishing = true
                targets += Target(target, it.start, it.end, it.inherit != null, fresh = existing == null)
            }

            // Conversation : correction (« enfin non, plutôt… »), déplacement (« mets ça dimanche »), annulation (« laisse tomber les courses »).
            val correction = CORR_RE.containsMatchIn(sn)
            val cancel = if (busy == null) CANCEL_RE.find(sn) else null
            if (targets.isEmpty() && cancel != null) {
                val victims = matchTasks(tasks, sn.substring(cancel.range.first), 0.3)
                for (v in victims.ifEmpty { lastTargets.takeLast(1) }) {
                    tasks.remove(v)
                    for (o in tasks) o.dependsOn.remove(v.id)
                }
                lastTargets = emptyList()
                continue
            }
            val move = busy == null && (correction || MOVE_RE.containsMatchIn(sn)) && targets.isEmpty()
            if (move) {
                val whens = times.filter { it.kind == "when" && it !in used }
                val dest = whens.lastOrNull()
                if (dest != null || durMinutes != null) {
                    // « déplace la tâche de mardi à samedi » : la source est le premier jour cité.
                    val src = if (whens.size > 1 && whens[0].date != null) tasks.filter { it.window?.date == whens[0].date } else emptyList()
                    val named = matchTasks(tasks, sn, 0.3)
                    val victims = src.ifEmpty { named.ifEmpty { lastTargets } }
                    for (v in victims) {
                        if (durMinutes != null) v.explicitMinutes = durMinutes
                        if (dest != null) {
                            val date = dest.date ?: v.window?.date ?: dayCtx?.date ?: now.date
                            v.window = window(date, dest, dest.part, so.substring(dest.start, dest.end).trim())
                        }
                    }
                    if (victims.isNotEmpty()) { lastTargets = victims; continue }
                }
            }

            // 3. Sans élément explicite : la phrase précise une tâche déjà citée.
            if (targets.isEmpty()) {
                // Plusieurs durées (« les recherches une heure, les diapositives deux heures ») : chacune vise la tâche de sa proposition.
                if (durList.size > 1) {
                    var from = 0
                    for (d in durList) { matchTask(tasks, sn.substring(from, d.end), 0.01)?.explicitMinutes = d.minutes; from = d.end }
                    continue
                }
                val ts = matchTasks(tasks, sn, 0.01)
                for (t in ts) targets += Target(t, 0, sn.length, whole = true)
                // « Je pense qu'une heure et demie devrait suffire » : précision sur la tâche qui vient d'être citée.
                // « 2 heures pour la physique » nomme autre chose : on ne l'attribue pas au hasard.
                val namesOther = Regex("\\bpour\\s+(?:le|la|les|l'|mon|ma|mes)\\s*\\S").containsMatchIn(sn)
                if (ts.isEmpty() && (correction || (durMinutes != null && !namesOther)) && lastTargets.isNotEmpty()) for (t in lastTargets) targets += Target(t, 0, sn.length)
            }

            // 4. Contraintes de la phrase : une indication vise l'élément qui la contient, sinon toute la phrase.
            fun scoped(index: Int?): List<VoiceTask> {
                if (index == null) return emptyList()
                val inside = targets.filter { index >= it.start && index < it.end }
                return (if (targets.size > 1 && inside.isNotEmpty()) inside else targets).map { it.task }
            }
            val daily = DAILY_RE.find(sn)
            for (tm in times) {
                if (tm.kind == "scope") { for (tg in targets) tg.task.horizon = Horizon(tm.from!!, tm.until!!); continue }
                if (tm.kind != "when" || tm in used) continue
                val beforeTxt = sn.substring(maxOf(0, tm.start - 40), tm.start)
                // « Dans deux semaines, je dois rendre mon projet » : rendre un travail est toujours une échéance.
                val handIn = targets.size == 1 && HAND_IN_RE.containsMatchIn(targets[0].task.norm)
                val isDeadline = DEADLINE_RE.containsMatchIn(beforeTxt) || handIn
                val label = so.substring(tm.start, tm.end).trim()
                // Jour en tête de phrase : il vaut pour toutes les tâches de la phrase.
                if (tm === head && !isDeadline && targets.size > 1 && daily == null) {
                    for (tg in targets) if (tg.task.window == null) setWindow(tg.task, window(tm.date!!, tm, tm.part, label), tm.sameDay)
                    continue
                }
                val target = if (targets.size == 1) targets[0].task else targets.firstOrNull { tm.start >= it.start && tm.end <= it.end }?.task
                // « A, B et C avant vendredi » : l'échéance finale vaut pour toute l'énumération.
                val last = targets.lastOrNull()
                // Phrase qui précise plusieurs tâches citées (« le dossier » = chaque séance du dossier) : toutes sont visées.
                val all = if (targets.size > 1 && targets.all { it.whole }) targets.map { it.task }
                else if (isDeadline && targets.size > 1 && target == last?.task && targets.drop(1).all { it.inherited }) targets.map { it.task } else null
                if (target == null) {
                    if (isDeadline) questions += "À quelle tâche correspond l'échéance « $label » ?"
                    continue
                }
                // Moment sans jour (« l'après-midi ») : le jour de la phrase ou de la section, sinon aujourd'hui.
                val date = tm.date ?: sentenceDay?.date ?: if (tm.minute != null && tm.minute!! < now.minute && tm.part == null) now.date.plusDays(1) else now.date
                if (tm.sameDay) target.notes += "Hypothèse : « $label » compris comme aujourd'hui. Dis « $label prochain » pour la semaine suivante."
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
                    for (t in all ?: listOf(target)) t.window = window(date, tm, tm.part, label)
                }
            }
            // Phrase sans date : les nouvelles tâches héritent du jour de la section (« Mercredi, … . Je voudrais aussi… »).
            if (sentenceDay != null && !sentenceDayIsHead && daily == null) {
                for (tg in targets) if (tg.fresh && tg.task.window == null && tg.task.recurrence == null)
                    setWindow(tg.task, window(sentenceDay.date, null, sentenceDay.part, sentenceDay.label), sentenceDay.sameDay)
            }
            for (t in scoped(URGENT_RE.find(sn)?.range?.first)) t.urgent = true
            for (t in scoped(LOW_RE.find(sn)?.range?.first)) t.low = true
            val except = SAUF_RE.findAll(sn).map { DAYS.indexOf(it.groupValues[1]) + 1 }.toList()
            if (daily != null) for (t in scoped(daily.range.first)) {
                val w = daily.groupValues[1]
                t.recurrence = Recurrence(Recurrence.Type.DAILY, part = if (w.contains("soir")) "soir" else if (w.contains("matin")) "matin" else null)
                t.except = except
            }
            val count = COUNT_RE.find(sn)
            if (daily == null && count != null) for (t in scoped(count.range.first)) t.recurrence = Recurrence(Recurrence.Type.COUNT, n = num(count.groupValues[1]))
            for (t in scoped(LITTLE_RE.find(sn)?.range?.first)) t.little = true
            if (durMinutes != null) for (t in scoped(durSpans.last().first)) t.explicitMinutes = durMinutes

            // 5. Dépendances : « après avoir X », « avant de Y » — résolues en fin d'analyse.
            val main = targets.firstOrNull()?.task
            val after = Regex("\\b(?:apres avoir|une fois que j'aurai|une fois que j'ai)\\s+([^,.;]+)").find(sn)
            val beforeDep = Regex("\\bavant de\\s+([^,.;]+)").find(sn)
            if (main != null && after != null) pendingDeps += PendingDep(main, after.groupValues[1], true)
            if (main != null && beforeDep != null) pendingDeps += PendingDep(main, beforeDep.groupValues[1], false)
            // La tâche citée seulement dans la dépendance (« avant de commencer les diapositives ») existe aussi.
            for (d in listOf(after, beforeDep)) {
                if (main == null || d == null || matchTask(tasks.filter { it !== main }, d.groupValues[1], 0.3) != null) continue
                val phrase = d.groupValues[1]
                val ws = phrase.trim().split(Regex("\\s+"))
                if (ws.size < 2 || !DETERMINER.containsMatchIn(ws.drop(1).joinToString(" "))) continue // « avant de cuisiner pour samedi » : pas d'objet
                val at = d.range.first + d.value.length - phrase.length
                val origWords = so.substring(at, at + phrase.length).trim().split(Regex("\\s+"))
                val verb = PARTICIPLE[ws[0]] ?: if (INFINITIVE.matches(ws[0]) && ws[0] !in NOT_VERBS) origWords[0]
                    else if (Regex("^[a-z]{3,}e$").matches(ws[0])) origWords[0] + "r" else null
                if (verb == null) continue
                val title = capitalize((listOf(verb) + origWords.drop(1)).joinToString(" "))
                newTask(title, norm(title)).fromDep = true
            }

            // La section change avec un nouveau jour planifié (pas une échéance) ; une phrase de tâches devient le contexte des précisions.
            val newDay = head ?: dated.firstOrNull { !DEADLINE_RE.containsMatchIn(sn.substring(maxOf(0, it.start - 40), it.start)) }
            if (newDay != null) dayCtx = DayCtx(newDay.date!!, newDay.part, so.substring(newDay.start, newDay.end).trim(), newDay.sameDay)
            if (targets.isNotEmpty()) lastTargets = targets.map { it.task }
        }
        for (d in pendingDeps) {
            if (d.task !in tasks) continue
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
            // « chaque jour sauf mercredi » : une séance par jour de la période, sans les jours exclus.
            val rec = t.recurrence
            if (rec?.type == Recurrence.Type.DAILY && t.except.isNotEmpty()) {
                val dates = mutableListOf<LocalDate>()
                var d = maxOf(t.horizon!!.from, now.date)
                while (d <= t.horizon!!.until) { if (d.dayOfWeek.value !in t.except) dates += d; d = d.plusDays(1) }
                t.recurrence = Recurrence(Recurrence.Type.DAYS, part = rec.part, dates = dates)
            }
            t.except = emptyList()
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
        return Dictation(tasks, questions, PLAN_RE.containsMatchIn(n) || tasks.size > 1, constraints)
    }
}
