# ☁ Home Cloud — client Android

Client Android natif (Kotlin + Jetpack Compose) pour le serveur **Home Cloud**.
Il parle exactement la même API HTTP/JSON que le client de bureau Qt : aucun changement n'est nécessaire côté serveur.

## Compiler

1. Ouvrir ce dossier dans **Android Studio** (Koala ou plus récent, JDK 17). La synchronisation Gradle télécharge tout.
2. Brancher un téléphone (débogage USB) ou lancer un émulateur, puis **Run ▶**.
3. APK en ligne de commande : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
   Pour un APK `release`, configurez une clé de signature (Build ▸ Generate Signed Bundle / APK).

Prérequis : Android 8.0+ (minSdk 26).

## Fonctionnalités

- **Détection automatique** du serveur sur le Wi‑Fi (broadcast UDP 8651, comme le client de bureau) ou saisie de l'adresse (`192.168.1.20`, `192.168.1.20:8650`, `https://cloud.exemple.fr`).
- Connexion, session mémorisée (jeton de 12 h), reconnexion si expirée.
- **Disques** (tiroir latéral) avec jauge d'espace, « Mon espace » en premier, mention lecture seule.
- **Explorateur** : fil d'Ariane, icônes par type de fichier (les mêmes que le client de bureau), cadenas sur les fichiers verrouillés.
- Appui long = sélection multiple. Actions : ouvrir avec une autre application, partager, enregistrer sur l'appareil, renommer, supprimer, détails.
- **Import** de fichiers (sélecteur système, plusieurs à la fois, avec progression), **nouveau dossier**.
- **Fichiers verrouillés par mot de passe** : verrouiller / déverrouiller ; le mot de passe est demandé (et vérifié via `/api/lock_check`) pour ouvrir, télécharger, modifier, renommer ou supprimer, sauf pour l'administrateur — la règle reste appliquée par le serveur.
- **Éditeur de texte intégré** (fichiers texte jusqu'à 512 Ko) : modifie et renvoie le fichier sur le serveur.
- **Compte** : photo de profil, changement de mot de passe, thème clair / sombre / automatique, déconnexion.
- **HomeCloud Sync** (*Compte et paramètres ▸ HomeCloud Sync*) : envoi automatique, en arrière-plan, des photos, vidéos, audio et documents du téléphone vers un disque Home Cloud (voir ci-dessous).
- **Administration** (compte admin) : utilisateurs (création, suppression, quota), disques partagés (création, suppression), autorisations par utilisateur (aucun accès / lecture / lecture‑écriture).

## HomeCloud Sync

Version Android de [HomeCloud Sync](../homecloud-sync) : le téléphone envoie ses fichiers vers le cloud, sans rien supprimer.

**Réglages** (écran *HomeCloud Sync*) :

- **Disque et dossier de destination** (par défaut : *Mon espace*, dossier `/HomeCloud Sync/<modèle du téléphone>`).
- **Types de fichiers** : Photos, Vidéos, Audio, Documents (PDF, Word, Excel, PowerPoint, ODF, TXT…), Archives, plus des **extensions personnalisées** (`apk, kdbx`, ou `*` pour tout).
- **Sources** : la *galerie de l'appareil* (photos/vidéos/audio, via MediaStore) et/ou des **dossiers** que vous ajoutez avec le sélecteur système (sous-dossiers compris). Les documents, archives et extensions personnalisées sont cherchés dans ces dossiers.
- **Planification** : toutes les 15 min à 24 h, Wi‑Fi uniquement (par défaut), seulement en charge (option), recherche du serveur par son nom si son adresse change (option, désactivée par défaut).
- Bouton **Synchroniser maintenant**, **journal** des derniers envois, **réinitialisation** de la mémoire de synchro.

**Organisation sur le cloud** : `<dossier>/<Type>/<album ou dossier source>/<fichier>` (ex. `Photos/Camera/IMG_0001.jpg`, `Documents/Téléchargements/facture.pdf`).

**Règles** (identiques à la version terminal, en sens unique téléphone → cloud) :

| Situation | Action |
|-----------|--------|
| Nouveau fichier sur le téléphone | envoyé |
| Fichier modifié sur le téléphone (taille ou date) | renvoyé, remplace la version du cloud |
| Déjà sur le cloud avec la même taille (ex. après réinstallation) | non renvoyé |
| Supprimé du téléphone | **rien n'est supprimé** sur le cloud |
| Fichier du cloud verrouillé par mot de passe / dossier du même nom | ignoré, signalé dans le journal |
| Disque en lecture seule | synchro refusée |

**Fonctionnement** : WorkManager (tâche périodique, minimum 15 min imposé par Android). Un gros envoi est repris au passage suivant ; une notification de progression s'affiche quand Android autorise le travail au premier plan. La mémoire de synchro (`sync_state.json`) et le journal restent dans le stockage privé de l'application.

**Mot de passe** : pour se reconnecter seule, la synchro conserve le mot de passe du compte, **chiffré avec le Keystore Android** (AES‑GCM, clé non exportable) ; il est effacé à la désactivation et mis à jour si vous le changez depuis l'appli. La synchro reste active si vous vous déconnectez de l'appli.

## À savoir

- **Pas de TLS** côté serveur : le trafic circule en clair sur le réseau local (voir README du serveur). L'application autorise donc le HTTP ; pour un accès hors de chez vous, passez par un VPN (WireGuard, Tailscale) ou un reverse proxy HTTPS et saisissez `https://…`.
- Le jeton de session est stocké dans les préférences privées de l'application.
- Contrairement au client de bureau, une application externe ouverte depuis le téléphone travaille sur une **copie temporaire** (supprimée au prochain lancement) : ses modifications ne sont pas renvoyées automatiquement. Pour modifier un fichier texte et le renvoyer, utilisez *Modifier le texte* ; pour les autres, ré‑importez le fichier (le mot de passe du verrou est demandé si nécessaire).
- Le téléchargement de dossiers entiers n'existe pas côté serveur : seuls les fichiers sont téléchargeables.

## Structure

```
app/src/main/java/fr/survivalier/homecloud/
  Api.kt            client REST (OkHttp)
  Discovery.kt      détection UDP
  MainViewModel.kt  état et logique (session, fichiers, verrous, transferts, admin)
  LoginScreen.kt  MainScreen.kt  SettingsScreen.kt  AdminScreen.kt  EditorScreen.kt
  Components.kt  Theme.kt  Models.kt  Prefs.kt  Util.kt
  SyncConfig.kt     réglages HomeCloud Sync, catégories, chiffrement du mot de passe
  SyncEngine.kt     inventaire des fichiers, mémoire de synchro, envoi, journal
  SyncWorker.kt     tâche WorkManager et planification
  SyncScreen.kt     écran de réglages HomeCloud Sync
app/src/main/res/drawable-nodpi/   icônes de fichiers reprises du client de bureau
```
