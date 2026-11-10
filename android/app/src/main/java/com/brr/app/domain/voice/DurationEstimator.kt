package com.brr.app.domain.voice

/** Estimation des durées, miroir de web/js/core/estimator.js. Jamais présentée comme une certitude : toujours une fourchette. */
object DurationEstimator {
    data class Family(val id: String, val label: String, val keywords: List<String>, val min: Int, val max: Int)

    /** Ordre significatif : la première famille reconnue l'emporte. */
    val FAMILIES = listOf(
        Family("mail", "Mail / message", listOf("mail", "courriel", "message", "repondre a", "sms"), 5, 15),
        Family("appel", "Appel", listOf("appeler", "telephoner", "rappeler", "appel"), 5, 20),
        Family("expose", "Exposé / oral", listOf("expose", "presentation", "oral", "diaporama", "slides", "soutenance"), 120, 240),
        Family("dossier", "Dossier / rédaction", listOf("dossier", "rapport", "memoire", "rediger", "redaction", "dissertation", "projet"), 90, 180),
        Family("revision", "Révision", listOf("reviser", "revision", "etudier", "apprendre", "chapitre", "math", "exercice", "devoir", "partiel", "examen", "td"), 45, 90),
        Family("recherche", "Recherche", listOf("recherche", "chercher", "se renseigner", "comparer"), 20, 40),
        Family("courses", "Courses", listOf("course", "supermarche", "epicerie", "acheter"), 45, 90),
        Family("menage_grand", "Grand ménage", listOf("appartement", "maison", "grand menage"), 60, 120),
        Family("menage", "Rangement / ménage", listOf("ranger", "rangement", "menage", "nettoyer", "aspirateur", "chambre", "bureau"), 30, 60),
        Family("petit_menage", "Petite corvée", listOf("vaisselle", "linge", "lessive", "etendre", "plier"), 15, 30),
        Family("poubelle", "Poubelles", listOf("poubelle", "ordure", "dechet"), 5, 10),
        Family("sport", "Sport", listOf("sport", "courir", "footing", "muscu", "salle", "yoga", "velo", "seance"), 45, 90),
        Family("cuisine", "Cuisine", listOf("cuisiner", "cuisine", "repas", "preparer a manger"), 30, 60),
        Family("lecture", "Lecture", listOf("lire", "lecture", "livre"), 30, 60),
        Family("admin", "Administratif", listOf("facture", "payer", "impot", "banque", "administratif", "papier", "formulaire", "caf"), 15, 45),
        Family("rdv", "Rendez-vous", listOf("medecin", "dentiste", "rendez-vous", "rdv", "docteur"), 45, 90),
    )
    private val DEFAULT = Family("autre", "Autre", emptyList(), 30, 60)

    private val VERB_FACTORS = listOf(
        Regex("^(terminer|finir|finaliser|boucler|achever)\\b") to 0.6,
        Regex("^(avancer|continuer|poursuivre|travailler)\\b") to 0.6,
        Regex("^(commencer|demarrer|entamer|attaquer)\\b") to 0.5,
        Regex("^(relire|verifier|corriger)\\b") to 0.35,
    )

    fun family(norm: String): Family = FAMILIES.firstOrNull { f -> f.keywords.any { norm.contains(it) } } ?: DEFAULT
    fun verbFactor(norm: String): Double = VERB_FACTORS.firstOrNull { it.first.containsMatchIn(norm) }?.second ?: 1.0

    /** @param learned durées constatées par famille (minutes, ramenées à une tâche « complète »). */
    fun estimate(norm: String, learned: Map<String, List<Int>> = emptyMap()): Estimate {
        val f = family(norm)
        val factor = verbFactor(norm)
        var min = f.min * factor
        var max = f.max * factor
        var mid = (min + max) / 2
        val samples = learned[f.id].orEmpty().filter { it > 0 }.takeLast(10)
        if (samples.isNotEmpty()) {
            // Mélange progressif : l'estimation par défaut compte pour deux mesures.
            val blended = (mid * 2 + samples.average() * factor * samples.size) / (2 + samples.size)
            val scale = blended / mid
            min *= scale; max *= scale; mid = blended
        }
        return Estimate(PlanDates.round5(min), PlanDates.round5(max), PlanDates.round5(mid), f.id, f.label, samples.isNotEmpty())
    }

    /** Ajoute une durée constatée (garde les 10 dernières par famille). */
    fun learn(learned: Map<String, List<Int>>, norm: String, minutes: Int): Map<String, List<Int>> {
        if (minutes <= 0 || minutes > 12 * 60) return learned
        val id = family(norm).id
        return learned + (id to (learned[id].orEmpty() + Math.round(minutes / verbFactor(norm)).toInt()).takeLast(10))
    }

    /** Sérialisation compacte pour DataStore : « famille:30,45;autre:20 ». */
    fun encode(learned: Map<String, List<Int>>): String = learned.entries.joinToString(";") { (k, v) -> "$k:${v.joinToString(",")}" }
    fun decode(raw: String?): Map<String, List<Int>> = raw.orEmpty().split(';').mapNotNull { e ->
        val (k, v) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        k to v.split(',').mapNotNull { it.toIntOrNull() }
    }.toMap()
}
