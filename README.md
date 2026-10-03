# Jarvis — assistant vocal Android (orbe + mémoire d'éléphant)

Commande vocale → cerveau (Groq, via ton backend Render) → action réelle sur le téléphone → réponse vocale.
L'interface reprend l'**orbe 3D** et le style de la version Windows/mobile.

## Installer l'APK (GitHub Actions)

1. Pousse ce dossier sur la branche `main` de ton dépôt GitHub.
2. Settings → Secrets and variables → Actions : ajoute `BACKEND_URL` et `APP_SHARED_SECRET`.
3. Onglet Actions → *Build APK* → télécharge l'artefact `jarvis-debug-apk`, installe `app-debug.apk`.
   (le workflow télécharge tout seul `three.min.js` pour l'orbe et le modèle de suivi de la main)
4. Au premier lancement : autoriser micro/contacts/téléphone, puis menu ⚙ → « Activer le contrôle d'écran ».

## Backend (Render)

Dossier `backend/`. Variables d'environnement : `GROQ_API_KEY`, `GEMINI_API_KEY`, `APP_SHARED_SECRET`,
et en option `CREATOR_NAME`, `DEFAULT_CITY` (météo), `TIKTOK_USERNAME`. **Redéploie le backend** : l'app a besoin de la nouvelle version.

## Mémoire d'éléphant

- Sur le téléphone (SQLite, aucune limite pratique) : **faits** (« retiens que… ») + **journal de tous les échanges**.
- À chaque question, Jarvis reçoit : tes faits, les 20 derniers échanges, et les vieux échanges qui parlent du même sujet.
- Dis : « Jarvis, retiens que mon plat préféré est… », « oublie… », « qu'est-ce que tu sais sur moi ? ».
- Menu ⚙ → « Ce que Jarvis sait de moi » / « Effacer toute la mémoire ». Sauvegarde automatique Android (allowBackup).

## Fonctions

Ouvrir/fermer une app, cliquer, taper, défiler, SMS, **appels** (contact, numéro dicté, surnom, WhatsApp voix/vidéo, rappeler le dernier numéro, raccrocher, haut-parleur), filtre d'appels entrants, curseur main (caméra), bulle flottante, et :
météo, recherche web (actus, sport, prix), abonnés TikTok, volume, musique (Spotify/YouTube) et contrôle média, lampe torche,
luminosité, alarmes, minuteurs, ouvrir un site, navigation GPS, état du téléphone (batterie/RAM/stockage),
**vision d'écran** (« qu'est-ce que je regarde ? », Android 11+, via Gemini), plusieurs commandes dans la même phrase.

## Sécurité

`local.properties` et `backend/.env` sont dans `.gitignore` — ne les publie jamais.
