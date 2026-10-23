# Run Matos (RunCoach Matos)

Assistant météo, équipement, chaussures, nutrition et rétroplanning pour la course sur route et le trail. Appli Android personnelle, partie d'un export Google AI Studio et maintenue désormais directement dans ce dépôt (plus de retour vers AI Studio).

## Récupérer l'APK

Chaque push sur `main` compile l'appli, lance les tests unitaires via GitHub Actions et publie l'APK dans les [Releases](../../releases/latest). Les autres branches sont compilées et testées sans publier de release.

Sur le téléphone : télécharger `run-matos.apk`, l'ouvrir, autoriser l'installation depuis le navigateur la première fois.

Les mises à jour s'installent par-dessus l'ancienne version sans perdre les données, grâce à la clé de signature fixe `debug.keystore` à la racine. La base n'est plus jamais effacée lors d'une mise à jour : tout changement de schéma doit fournir une migration Room explicite.

## Principes

- **Aucune donnée inventée** : sans vraie météo Open-Meteo (réseau ou cache de moins de 12 h pour le même lieu), l'appli n'affiche pas de recommandation. Sans réponse d'OpenStreetMap, le revêtement reste « inconnu ».
- **Règles fixes, sans IA** : seuils de tenue, boue, UV (OMS), gels (repères ACSM / Jeukendrup), frontale selon le lever et le coucher du soleil calculés pour le lieu.
- **Pas de notifications** : le rétroplanning de course est affiché, sans rappels.

## Sauvegarde

Onglet « Mon matos » → « Sauvegarde de mes données » : export et import d'un fichier JSON (chaussures, matos, check-lists, historique, réglages).

## Ouvrir dans Android Studio

Ouvrir le dossier du projet dans Android Studio et laisser la synchronisation Gradle se faire. L'appli n'utilise pas l'API Gemini, donc aucune clé n'est nécessaire.
