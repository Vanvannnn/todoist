# Configurer les calendriers

Bullylist lit tes calendriers **en lecture seule** pour deux choses :
- ne jamais planifier une tâche pendant un engagement ;
- mettre les rappels en pause pendant les cours.

Dans l'application : **Réglages → Calendriers et mode pause**.

## Emploi du temps CELCAT

CELCAT Calendar propose, selon les universités, un **lien d'abonnement iCal** (ICS). Bullylist l'utilise tel quel, sans identifiants ni lecture de pages web.

1. Ouvre ton emploi du temps CELCAT (portail de l'université / ENT).
2. Cherche « Abonnement iCal », « S'abonner », « Exporter », « ICS » ou « Partager ».
3. Copie le lien (il commence souvent par `https://` ou `webcal://`).
4. Dans Bullylist : **Lien iCal (CELCAT, iCloud…)** → colle le lien → profil **Cours**.

Selon l'établissement, le lien peut aussi s'obtenir :
- par e-mail, envoyé par la plateforme ;
- auprès du service des emplois du temps ;
- via un bouton « Partager » qui génère un lien public.

Si ton université ne propose qu'un **téléchargement de fichier .ics**, utilise **Fichier .ics**. Un fichier n'est pas actualisé automatiquement : réimporte-le après un changement d'emploi du temps.

Ce qui est pris en compte :
- cours, horaires, salles ;
- répétitions hebdomadaires ;
- cours déplacés (`RECURRENCE-ID`) et annulés (`STATUS:CANCELLED`) ;
- exceptions (`EXDATE`) ;
- fuseaux horaires et changements d'heure.

Les journées entières (vacances, jours fériés) ne bloquent pas le planning.

> Le lien iCal donne accès à ton emploi du temps : il reste uniquement sur ton téléphone. Ne le partage pas.

## Google Agenda

Aucune connexion à Google n'est demandée à Bullylist :
1. ajoute ton compte Google au téléphone (Réglages Android → Comptes) et active la synchronisation de l'agenda ;
2. dans Bullylist : **Agendas du téléphone** → autorise « Agenda » → coche les agendas voulus.

Les événements récurrents et les modifications sont lus tels qu'Android les connaît. Une connexion OAuth directe à l'API Google Calendar n'a **pas** été développée : elle exigerait un projet Google Cloud, un identifiant client et une validation par Google, pour un résultat identique sur Android.

Autre possibilité : dans Google Agenda sur ordinateur → Paramètres de l'agenda → **Adresse secrète au format iCal** → à ajouter comme lien iCal.

## Calendrier Apple (iCloud)

Il n'existe **pas** d'intégration native Apple Calendar sur Android. Deux méthodes fiables :
1. **Calendrier public** : dans Calendrier sur iPhone ou Mac → informations du calendrier → « Calendrier public » → partager le lien `webcal://…` → dans Bullylist, **Lien iCal**. Ce lien est public : toute personne qui l'obtient voit le calendrier.
2. **CalDAV via DAVx⁵** (application libre) : ajoute ton compte iCloud avec un mot de passe pour application. Le calendrier apparaît alors dans **Agendas du téléphone**. Bullylist ne voit jamais ton mot de passe.

## Mode pause

| Profil | Pause par défaut | Trajet après |
|---|---|---|
| Cours | oui | 20 min |
| Rendez-vous | oui | 20 min |
| Travail | oui | 30 min |
| Temps personnel | non | 0 min |
| Sommeil | plage horaire autorisée des rappels (Réglages → Personnalisation des alarmes) | — |

- Pendant un engagement et son trajet, les rappels attendent : ils sont **décalés**, jamais supprimés. Exemple : cours de 14 h à 16 h, 30 min de trajet → reprise à 16 h 30.
- Le niveau 4 respecte aussi la pause, sauf si tu actives « Sonner même pendant un cours » sur une tâche précise.
- Le profil et le trajet se règlent par calendrier. Les profils qui suspendent les rappels se règlent globalement.
- **Synchronisation** : automatique toutes les 6 heures, manuelle avec « Synchroniser maintenant », désactivable temporairement (les données déjà importées restent utilisées).
- **Pause automatique** : désactivable d'un geste.
