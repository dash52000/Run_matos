# Run Matos (RunCoach Matos)

Assistant météo, équipement, chaussures, nutrition et rétroplanning pour la course sur route et le trail. Appli Android générée avec Google AI Studio.

## Récupérer l'APK

Chaque push sur `main` compile l'appli via GitHub Actions et publie l'APK dans les [Releases](../../releases/latest).
Sur le téléphone : télécharger `run-matos.apk`, l'ouvrir, autoriser l'installation depuis le navigateur la première fois.

Les mises à jour s'installent par-dessus l'ancienne version sans perdre les données, grâce à la clé de signature fixe `debug.keystore` à la racine.

## Ouvrir dans Android Studio

Ouvrir le dossier du projet dans Android Studio et laisser la synchronisation Gradle se faire. L'appli n'utilise pas l'API Gemini, donc aucune clé n'est nécessaire.

Projet d'origine dans AI Studio : https://ai.studio/apps/d4ddd8dc-e7c3-408e-acc1-bd310fbce47d
