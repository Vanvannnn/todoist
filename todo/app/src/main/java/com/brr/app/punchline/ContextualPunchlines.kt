package com.brr.app.punchline

import com.brr.app.domain.QuickAddParser
import kotlin.random.Random

/** Génère des messages qui citent la tâche ou réagissent à son contenu. */
object ContextualPunchlines {

    private class Rule(val keywords: List<String>, val lines: List<String>)

    private val rules = listOf(
        Rule(listOf("vaisselle", "lave-vaisselle", "assiette"), listOf(
            "La vaisselle ne va pas se laver avec tes bonnes intentions.",
            "Les assiettes sales viennent de former un syndicat.",
            "Ton évier a demandé un arrêt maladie.",
        )),
        Rule(listOf("poubelle", "ordure", "dechet"), listOf(
            "Cela fait maintenant {min} minutes que tu négocies avec un sac-poubelle.",
            "Les poubelles ne sortent pas toutes seules. On a vérifié.",
            "Le camion-poubelle ne t'attendra pas. Lui, il est ponctuel.",
        )),
        Rule(listOf("linge", "lessive", "machine", "repasser", "repassage"), listOf(
            "Le linge ne va pas se plier par télépathie.",
            "Ta pile de linge a atteint l'altitude de croisière.",
            "La machine a fini son cycle. Toi, tu n'as pas commencé le tien.",
        )),
        Rule(listOf("course", "supermarche", "acheter", "frigo"), listOf(
            "Ton frigo fait de l'écho quand on lui parle. Les courses, vite.",
            "Le supermarché ferme un jour. Ta procrastination, jamais.",
        )),
        Rule(listOf("sport", "courir", "course a pied", "salle", "muscu", "footing", "yoga", "velo"), listOf(
            "Tes baskets se demandent si elles ont encore un but dans la vie.",
            "Tes muscles ont envoyé un avis de recherche.",
            "Le canapé ne compte pas comme une séance de sport.",
        )),
        Rule(listOf("appeler", "telephoner", "rappeler", "appel"), listOf(
            "Le téléphone, c'est le truc dans ta main. Il sert aussi à appeler.",
            "Tu as touché ton téléphone 80 fois aujourd'hui. Une de plus pour appeler ?",
        )),
        Rule(listOf("mail", "email", "e-mail", "courriel", "repondre", "message"), listOf(
            "Ce message ne va pas s'écrire avec la force de la pensée.",
            "Ta boîte de réception t'observe avec insistance.",
        )),
        Rule(listOf("menage", "ranger", "rangement", "aspirateur", "nettoyer", "poussiere"), listOf(
            "La poussière a commencé à fonder une civilisation.",
            "Les moutons sous le lit ont élu un maire.",
        )),
        Rule(listOf("devoir", "reviser", "revision", "etudier", "exam", "cours"), listOf(
            "Ton cerveau ne va pas se remplir tout seul pendant que tu scrolles.",
            "L'examen, lui, ne sera pas reporté.",
        )),
        Rule(listOf("medecin", "dentiste", "rdv", "rendez-vous"), listOf(
            "Ce rendez-vous ne va pas se prendre tout seul.",
            "Le secrétariat ferme bientôt. Ta procrastination, elle, est ouverte 24 h/24.",
        )),
        Rule(listOf("facture", "payer", "impot", "banque", "loyer", "administratif", "papier"), listOf(
            "Les factures, contrairement à toi, sont toujours à l'heure.",
            "L'administration adore les retardataires. Pour les pénalités.",
        )),
        Rule(listOf("lire", "livre", "lecture"), listOf(
            "Ce livre prend la poussière. Et la poussière prend de l'assurance.",
        )),
        Rule(listOf("cuisine", "cuisiner", "repas", "diner", "dejeuner", "manger"), listOf(
            "Le repas ne va pas se cuisiner en regardant des vidéos de recettes.",
        )),
        Rule(listOf("arroser", "plante"), listOf(
            "Tes plantes envisagent de porter plainte pour négligence.",
        )),
        Rule(listOf("chien", "promener", "promenade", "litiere"), listOf(
            "Ton animal te regarde. Il sait.",
        )),
    )

    private val generic = listOf(
        "« {task} » ne va pas se faire avec tes bonnes intentions.",
        "« {task} » : deux minutes pour le faire, deux heures à y penser.",
        "« {task} » vient de passer en tête du classement des tâches oubliées.",
        "« {task} » t'attend comme un chat devant une porte fermée.",
        "Rappel officiel : « {task} ». Version officieuse : bouge-toi.",
        "« {task} » a été aperçue en train de pleurer dans ta liste.",
    )
    private val lateTemplates = listOf(
        "Cela fait maintenant {min} minutes que tu négocies avec « {task} ».",
        "« {task} » est en retard de {min} minutes. Elle commence à le prendre personnellement.",
        "{min} minutes de retard pour « {task} ». Tu pourrais en faire un podcast.",
    )
    private val snoozeTemplates = listOf(
        "Tu as reporté « {task} » {snooze} fois. Un record est en vue.",
        "{snooze} reports pour « {task} ». À ce stade, c'est une relation.",
    )

    fun keywordLines(taskTitle: String): List<String> {
        val n = QuickAddParser.normalize(taskTitle)
        return rules.filter { r -> r.keywords.any { n.contains(it) } }.flatMap { it.lines }
    }

    fun generate(taskTitle: String, minutesLate: Long, snoozeCount: Int, random: Random): String {
        val pool = mutableListOf<String>()
        pool += keywordLines(taskTitle).filter { !it.contains("{min}") || minutesLate > 0 }
        pool += generic
        if (minutesLate > 0) pool += lateTemplates
        if (snoozeCount >= 2) pool += snoozeTemplates
        return fill(pool[random.nextInt(pool.size)], taskTitle, minutesLate, snoozeCount)
    }

    fun fill(template: String, taskTitle: String, minutesLate: Long, snoozeCount: Int): String =
        template.replace("{task}", taskTitle.trim())
            .replace("{min}", minutesLate.toString())
            .replace("{snooze}", snoozeCount.toString())
}
