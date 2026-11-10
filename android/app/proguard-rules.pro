# Room, WorkManager et Compose fournissent leurs propres règles consumer.
# On garde les noms des enums persistés par Room (stockés sous forme de texte).
-keepclassmembers enum com.brr.app.domain.model.** { *; }
-keepclassmembers enum com.brr.app.punchline.PunchlineCategory { *; }
