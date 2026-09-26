# Jarvis — assistant vocal Android

Squelette de projet : commande vocale → Groq (décide une action via function calling) →
exécution réelle (Intent ou AccessibilityService) → réponse vocale (TTS).

## Setup

1. Ouvrir le dossier `JarvisAndroid` dans Android Studio (Open an existing project).
2. Copier `local.properties.example` en `local.properties` à la racine, et remplir :
   - `sdk.dir` : chemin vers ton SDK Android (Android Studio le remplit souvent seul)
   - `GROQ_API_KEY` : ta clé sur https://console.groq.com
   - `GEMINI_API_KEY` : ta clé sur https://aistudio.google.com/apikey
3. Sync Gradle, puis Run sur un appareil ou émulateur (minSdk 26 = Android 8+).
4. Au premier lancement :
   - Autoriser le micro
   - Appuyer sur "Activer le contrôle d'écran" → dans les paramètres Android, activer
     le service Jarvis sous Accessibilité (Android bloque cette permission par défaut,
     c'est normal — c'est ce qui permet le contrôle avancé).

## Comment ça marche

- `MainActivity` : bouton micro → `VoiceManager` (STT natif Android) → texte
- `GroqClient` : envoie le texte à Groq avec une liste d'outils ("function calling").
  Groq répond soit du texte, soit "appelle cette fonction avec ces arguments".
- `CommandExecutor` : transforme la décision de Groq en action réelle
  (ouvrir une app, cliquer sur l'écran via `JarvisAccessibilityService`, etc.)
- `JarvisAccessibilityService` : lit l'arbre de l'écran et peut cliquer/taper dessus.
- `GeminiClient` : pas encore branché à l'orchestrateur — prévu pour les tâches
  multimodales (ex: "regarde ce qui est affiché et dis-moi ce que c'est"),
  en lui envoyant une capture d'écran en base64.

## Prochaines étapes possibles

- Ajouter un wake word ("Hey Jarvis") avec Porcupine (Picovoice) pour ne plus avoir
  à appuyer sur le bouton.
- Brancher Gemini pour analyser les captures d'écran (contexte visuel plus riche
  que juste l'arbre d'accessibilité).
- Ajouter un historique de conversation (actuellement chaque commande est traitée
  seule, sans mémoire du tour précédent).
- Remplacer l'`ACTION_SENDTO` des SMS par `SmsManager.sendTextMessage` pour un
  envoi silencieux, une fois testé et validé.
- Envelopper le contrôle d'écran (clic/frappe) dans un service en foreground
  pour que ça marche même app fermée.

## Sécurité

`local.properties` est dans `.gitignore` — ne commit jamais ce fichier ni tes clés
en dur dans le code.
