# Compréhension vocale — moteur local (hors ligne)

Généré le 2026-11-10 18:15 UTC par `web/test/assistant-eval.mjs`. Date de référence des scénarios : lundi 9 novembre 2026, 10h00.

**32/34 scénarios réussis.**

| Catégorie | Réussis | Échoués |
|---|---|---|
| dates | 11 | 0 |
| échéance vs planification | 1 | 1 |
| corrections | 4 | 0 |
| répétition | 3 | 0 |
| contraintes | 2 | 0 |
| dépendances | 2 | 0 |
| conversation | 3 | 0 |
| programme multi-jours | 1 | 0 |
| tâches composées | 2 | 0 |
| priorités | 2 | 0 |
| planification | 0 | 1 |
| hésitations | 1 | 0 |

## Détail

### ✅ 1. dates

> Mardi, je fais mes courses. Mercredi, je révise. Dimanche, je range.

Résultat : Faire mes courses [2026-11-10] 70min · Réviser [2026-11-11] 70min · Ranger [2026-11-15] 45min

### ✅ 2. échéance vs planification

> Je dois terminer mon projet avant vendredi, mais je veux commencer mardi.

Résultat : Terminer mon projet [2026-11-10] ⏰2026-11-12 80min

### ✅ 3. corrections

> Mardi, je travaille deux heures sur le dossier. Enfin non, une heure trente.

Résultat : Travailler sur le dossier [2026-11-10] 90min

### ✅ 4. répétition

> Cette semaine, je veux réviser les maths un peu chaque jour, sauf mercredi.

Résultat : Réviser les maths 70min

### ✅ 5. contraintes

> Jeudi, j'ai cours le matin, donc prévois les révisions l'après-midi.

Résultat : Révisions l' [2026-11-12] 70min · ⛔ 2026-11-12 480-720

### ✅ 6. dépendances

> Il faut que je fasse les recherches avant de commencer les diapositives.

Résultat : Faire les recherches 30min · Commencer les diapositives 25min

### ❌ 7. échéance vs planification

> Je dois rendre le dossier le 25 novembre, mais je veux qu'il soit terminé deux jours avant.

Résultat : Rendre le dossier ⏰2026-11-25 135min

Écarts :
- « dossier » : échéance 2026-11-25 (attendu 2026-11-23)

### ✅ 8. dates

> Dimanche, je fais les courses, je prépare mes repas et je range l'appartement.

Résultat : Faire les courses [2026-11-15] 70min · Préparer mes repas [2026-11-15] 45min · Ranger l'appartement [2026-11-15] 90min

### ✅ 9. dates

> Ah, et n'oublie pas de mettre le rendez-vous chez le dentiste jeudi.

Résultat : Rendez-vous chez le dentiste [2026-11-12] 70min

### ✅ 10. conversation

> **Tour 1.** Mardi, je dois faire les courses. Mercredi, je dois appeler le garage.
> **Tour 2.** Finalement, déplace la tâche de mardi à samedi.

Résultat : Faire les courses [2026-11-14] 70min · Appeler le garage [2026-11-11] 15min

### ✅ 11. programme multi-jours

> Alors, mardi j'ai cours le matin. Après, j'aimerais bien avancer sur mon dossier de marketing. Il faut que je fasse aussi les recherches pour la deuxième partie, et surtout que je n'oublie pas d'envoyer le mail à mon professeur. Mercredi, je voudrais réviser les maths, mais pas forcément toute la journée. Je pense qu'une heure et demie devrait suffire. Jeudi, j'ai envie de terminer le dossier marketing. Il faudrait que je prévoie un peu de temps pour relire ce que j'ai fait mercredi aussi. Et dimanche, je veux préparer mes affaires pour la semaine, faire les courses et ranger un peu l'appartement. Ah, et il faudrait absolument que j'aie terminé le dossier avant vendredi soir.

Résultat : Avancer sur mon dossier de marketing [2026-11-10] ⏰2026-11-13 80min · Faire les recherches pour la deuxième partie [2026-11-10] 30min · Envoyer le mail à mon professeur [2026-11-10] 10min · Réviser les maths [2026-11-11] 90min · Terminer le dossier marketing [2026-11-12] ⏰2026-11-13 50min · Relire ce que j'ai fait mercredi [2026-11-12] 15min · Préparer mes affaires pour la semaine [2026-11-15] 45min · Faire les courses [2026-11-15] 70min · Ranger l'appartement [2026-11-15] 90min · ⛔ 2026-11-10 480-720

### ✅ 12. tâches composées

> Mardi, je voudrais travailler sur mon exposé. Il faudrait que je fasse les recherches, que je trouve des images, que je prépare les diapositives et que je regarde aussi si les sources sont bonnes. Ah oui, et il faut que je pense à contacter Paul pour récupérer les documents. Je pense que les recherches vont prendre une heure, les diapositives peut-être deux heures, mais je ne suis pas certain.

Résultat : Travailler sur mon exposé [2026-11-10] 110min · Faire les recherches [2026-11-10] 60min · Trouver des images [2026-11-10] 45min · Préparer les diapositives [2026-11-10] 120min · Regarder si les sources sont bonnes [2026-11-10] 45min · Penser à contacter Paul pour récupérer les documents [2026-11-10] 45min

### ✅ 13. corrections

> Mercredi, je vais réviser les maths pendant deux heures. Enfin non, plutôt une heure et demie, parce que j'ai aussi envie de sortir.

Résultat : Réviser les maths [2026-11-11] 90min

### ✅ 14. corrections

> Jeudi, je vais faire les courses. Ah non, finalement, mets ça dimanche.

Résultat : Faire les courses [2026-11-15] 70min

### ✅ 15. tâches composées

> Pour le dossier marketing, je voudrais faire les recherches mardi et la rédaction jeudi.

Résultat : Faire les recherches [2026-11-10] 30min · Faire la rédaction [2026-11-12] 135min

### ✅ 16. conversation

> **Tour 1.** Mardi, je dois faire les courses. Mercredi, je dois réviser les maths.
> **Tour 2.** Ah, j'ai oublié quelque chose. Mardi, il faut aussi que je passe à la pharmacie. Et mercredi, ne me mets rien après 18 heures.

Résultat : Faire les courses [2026-11-10] 70min · Réviser les maths [2026-11-11] 70min · Passer à la pharmacie [2026-11-10] 45min · ⛔ 2026-11-11 1080-1440

### ✅ 17. dates

> Dans deux semaines, je dois rendre mon projet.

Résultat : Rendre mon projet ⏰2026-11-23 135min

### ✅ 18. dates

> Le 18 novembre, je dois envoyer mon dossier.

Résultat : Envoyer mon dossier [2026-11-18] 135min

### ✅ 19. dates

> Jeudi prochain, je dois terminer mon dossier.

Résultat : Terminer mon dossier [2026-11-12] 80min

### ✅ 20. dates

> Je dois appeler ma grand-mère.

Résultat : Appeler ma grand-mère 15min

### ✅ 21. priorités

> Si j'ai le temps, je voudrais lire un livre.

Résultat : Lire un livre 45min

### ✅ 22. priorités

> Je dois absolument envoyer ma candidature avant demain midi.

Résultat : Envoyer ma candidature ⏰2026-11-10 45min

### ✅ 23. conversation

> **Tour 1.** Mardi, je dois faire les courses et appeler le garage.
> **Tour 2.** Finalement, laisse tomber les courses.

Résultat : Appeler le garage [2026-11-10] 15min

### ✅ 24. dates

> Le 3 novembre, je dois payer le loyer.

Résultat : Payer le loyer 30min

### ✅ 25. contraintes

> Mercredi après-midi, je ne suis pas disponible. Je dois quand même réviser l'anglais mercredi.

Résultat : Réviser l'anglais [2026-11-11] 70min · ⛔ 2026-11-11 810-1080

### ✅ 26. répétition

> Je veux faire du sport trois fois cette semaine.

Résultat : Faire du sport 70min

### ✅ 27. dates

> Vendredi à 20h30, je dois aller au cinéma avec Léa.

Résultat : Aller au cinéma avec Léa [2026-11-13 1230] 45min

### ❌ 28. planification

> Je dois écrire un rapport qui va me prendre quatre heures, à rendre vendredi. J'aimerais le répartir entre mardi et jeudi.

Résultat : Écrire un rapport qui va me ⏰2026-11-13 240min

Écarts :
- « rapport » : aucune séance le 2026-11-10
- « rapport » : aucune séance le 2026-11-12

### ✅ 29. répétition

> Mardi je dois faire les courses. Oui, mardi, les courses.

Résultat : Faire les courses [2026-11-10] 70min

### ✅ 30. dépendances

> Après avoir fini les recherches, je rédigerai l'introduction. Les recherches, je les fais mardi.

Résultat : Rédiger l'introduction 135min · Finir les recherches [2026-11-10] 20min

### ✅ 31. dates

> Ce week-end, je dois ranger le garage et laver la voiture.

Résultat : Ranger le garage 45min · Laver la voiture 45min

### ✅ 32. dates

> Demain matin, je dois appeler la banque à 9h.

Résultat : Appeler la banque [2026-11-10 540] 15min

### ✅ 33. hésitations

> Euh, alors, mardi, je dois, euh, faire le ménage, enfin, le ménage de la cuisine.

Résultat : Faire le ménage [2026-11-10] 45min

### ✅ 34. corrections

> Jeudi, je travaille sur le mémoire, au moins trois heures, peut-être quatre.

Résultat : Travailler sur le mémoire [2026-11-12] 180min
