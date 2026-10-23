# Plan de Résolution des Failles de Robustesse de l'Application

Ce plan vise à corriger l'ensemble des failles techniques, points de fragilité réseau, cas limites de données et risques de perte accidentelle identifiés lors de l'audit.

---

## 1. Résilience Réseau & DNS pour Open-Meteo & Géocodage

### Problème identifié
Comme pour le serveur Overpass, les requêtes météo vers `api.open-meteo.com`, `geocoding-api.open-meteo.com` et le service d'archives utilisaient la configuration DNS système par défaut d'Android. Sur certains réseaux mobiles, réseaux d'entreprise ou dans l'émulateur Android, un échec de résolution DNS bloque le chargement de la météo et la recherche de ville.

### Actions à mener
1. **Implémentation d'un `OpenMeteoResilientDns`** dans `OpenMeteoApi.kt` :
   - Table d'adresses IPv4 officielles de secours pour les serveurs Open-Meteo :
     - `api.open-meteo.com` -> `157.90.83.168`
     - `geocoding-api.open-meteo.com` -> `202.61.206.6`
     - `archive-api.open-meteo.com` -> `157.90.83.168`
   - Interception des `UnknownHostException` pour basculer instantanément sur les adresses directes avec négociation TLS/SNI standard.
2. **Configuration OkHttpClient** :
   - Ajout de `.dns(OpenMeteoResilientDns)` sur le client HTTP d'Open-Meteo.
   - Ajustement des timeouts (connect 8s, read 15s) pour une bascule rapide.
3. **Sécurisation du géocodage inversé GPS** :
   - Dans `resolveCityName` (`RunCoachViewModel.kt`), encadrer l'appel Geocoder avec fallback garanti sur les coordonnées formatées sans bloquer le thread IO.

---

## 2. Assainissement & Sécurisation du Parsing GPX

### Problème identifié
Si l'utilisateur importe une trace GPX corrompue, issue d'une montre avec perte de signal GPS, ou un fichier massif :
- Des coordonnées invalides (`NaN`, infinies ou hors de `[-90, 90]`, `[-180, 180]`) propagent des `NaN` dans la formule haversine, cassant la distance totale et le calcul de durée.
- Des valeurs aberrantes d'altitude (`ele` à 99999 m ou -9999 m) créent un dénivelé artificiel de plusieurs dizaines de milliers de mètres.
- Un fichier GPX très volumineux (> 30 000 points) peut saturer la mémoire vive de l'appareil.

### Actions à mener
1. **Validation stricte des points dans `GpxAndOverpassAnalyzer.parseGpxContent`** :
   - Vérifier que `lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0`. Les points non valides sont ignorés.
   - Valider et borner l'altitude : `ele` doit être compris entre `-500.0` et `9000.0` mètres.
   - Détection des artefacts de perte GPS : si deux points consécutifs ont un écart vertical > 150 m pour une distance horizontale < 20 m, ne pas cumuler de faux $D+$.
2. **Plafond de sécurité mémoire** :
   - Sous-échantillonnage adaptatif automatique si le nombre de points dans le flux XML dépasse 25 000 points.
3. **Garanties sur les valeurs de retour** :
   - Garantir que `totalDistanceKm` est toujours fini et $\ge 0.1$, et $D+ \ge 0$.

---

## 3. Sécurité des Données Chaussures & Références Orphelines

### Problème identifié
1. Dans `GearAndShoesScreen.kt`, un simple tap sur l'icône de corbeille d'une paire supprime immédiatement la chaussure de la base SQLite Room sans confirmation, risquant d'effacer des centaines de kilomètres cumulés par mégarde.
2. Si la chaussure supprimée était activement sélectionnée via le remplacement manuel (`manualShoeOverrideId`), cette variable pointait vers un identifiant inexistant.

### Actions à mener
1. **Ajout d'un dialogue de confirmation de suppression** (`DeleteShoeConfirmDialog`) dans `GearAndShoesScreen.kt` :
   - Affichage du nom de la paire, de son kilométrage actuel (`currentKm / maxKm`) et d'un texte d'avertissement clair.
   - Boutons "Annuler" et "Supprimer définitivement" (avec style de confirmation explicite).
2. **Nettoyage automatique dans `RunCoachViewModel.deleteShoe`** :
   - Si `_formState.value.manualShoeOverrideId == shoe.id`, réinitialiser immédiatement `manualShoeOverrideId = null`.
   - Message de confirmation dans le bandeau de statut.

---

## 4. Robustesse des Saisies & Prévention des Divisions par Zéro

### Actions à mener
1. **Saisie des distances et dénivelés** :
   - Remplacement systématique des virgules par des points (`txt.replace(",", ".")`).
   - Borner la distance saisie à `coerceIn(0.1, 200.0)` pour autoriser les courtes sorties (ex. 500 m) tout en empêchant les valeurs négatives ou nulles.
   - Borner le dénivelé $D+$ à `coerceIn(0, 10000)`.
2. **Calculs d'allure et de durée** :
   - Dans `estimateDurationForDistance`, borner `paceSecPerKm` (`210..540` s/km) et vérifier strictement que `distanceKm > 0` pour éviter toute division par zéro ou durée nulle.
3. **Calcul de nutrition** :
   - Dans `calculateNutrition`, garantir que `safeGelGrams >= 15` et que `durationMinutes >= 15` pour éviter toute valeur négative ou division par zéro lors de la génération de la frise chronologique des gels.

---

## 5. Vérification & Tests

1. Exécution de `compile_applet` pour valider l'absence d'erreurs de syntaxe ou de typage.
2. Exécution des tests unitaires Robolectric (`gradle :app:testDebugUnitTest`) pour vérifier la non-régression.
3. Test des cas limites : trace GPX avec coordonnées corrompues, suppression de chaussure avec confirmation, et simulation réseau dégradé.
