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

## Clés API dans l'app

Menu ⚙ → « Clés API & fournisseurs » : colle ta clé Groq / OpenAI / OpenRouter / autre (compatible OpenAI) et Gemini (vision + recherche web),
choisis le modèle et le « cerveau » actif, puis « Tester la clé ». Les clés sont chiffrées sur le téléphone et envoyées (en HTTPS) au backend
à chaque commande ; si rien n'est saisi dans l'app, le backend retombe sur ses variables Render (`GROQ_API_KEY`, `GEMINI_API_KEY`).

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

## Clavier visuel & vision de l'écran

**Écrire au lieu de parler** (Jarvis répond alors par écrit, sans parler) :
- Dans l'app : bouton ⌨ en bas à gauche → barre de saisie. Entrée ou ➤ pour envoyer.
- Par-dessus les autres apps (mode bulle) : **maintiens la bulle** appuyée, ou touche « Écrire » dans la notification. Une barre de saisie s'ouvre en haut de l'écran ; la réponse s'affiche dans une carte qui disparaît toute seule.
- Tout ce que tu peux dire, tu peux le taper (commandes, tâches en plusieurs étapes, mémoire…).

**Vision (Jarvis voit ce que tu vois)** :
- Bouton 👁 (dans l'app ou dans la barre de saisie de la bulle), ou dis/tape « active la vision » / « désactive la vision ».
- Vision active : chaque question de conversation est répondue en regardant ton écran (capture + texte lu, via Gemini). Les vraies commandes (ouvrir une app, appeler, minuteur…) marchent comme avant.
- Nécessite : contrôle d'écran activé (menu ⚙) et Android 11+. **Gemini n'est pas obligatoire** : sans clé Gemini, Jarvis utilise ton cerveau (OpenAI, OpenRouter… avec un modèle qui lit les images, ex. `gpt-4o-mini`) ; si le modèle ne lit pas les images, il se base sur le texte lu à l'écran. **Redéploie le backend** pour cette version. Pour qu'elle serve, utilise la bulle par-dessus l'app que tu regardes (dans l'app Jarvis, elle ne voit que Jarvis).

## Curseur main (caméra)

Menu ⚙ > « Activer le curseur main », puis **« Réglages du curseur main »** (effet immédiat, sans redémarrer) :

- **Aperçu caméra** : une petite fenêtre en haut à droite montre ce que voit la caméra, avec le squelette de ta main, le point suivi (vert, rouge quand tu cliques) et l'état du geste. Le nombre « 0.41/0.22 » = écart des doigts / seuil : tu cliques quand il passe sous le seuil.
- **Sensibilité du pointeur** (50–300 %) : plus c'est haut, moins ta main a besoin de bouger pour parcourir l'écran.
- **Stabilité** (lissage) : amortit les tremblements ; les grands mouvements restent rapides.
- **Seuil de clic** : à baisser si tu cliques sans le vouloir, à monter si le clic ne part pas. Il se règle selon la taille de la main, donc il marche à n'importe quelle distance de la caméra.

Gestes de clic au choix :

| Geste | Points forts | Limites |
|---|---|---|
| **Pouce + majeur** | le plus précis : l'index pointe et ne bouge pas pendant le clic | un peu moins évident au début |
| **Pouce + index** | le plus naturel ; le pointeur suit le dos de la main pour ne pas dériver | un peu moins précis pour viser |
| **Rester immobile 1 s** | aucun faux clic, aucun geste à faire | plus lent |
| **Poing fermé** (expérimental) | pratique pour glisser | moins précis pour viser |

Un appui court = tap, un appui long = appui long, un appui avec déplacement = glissé / défilement. Le tap part de l'endroit où le geste a *commencé*.

## Jouer à tes jeux

Dis ou tape « **Joue à Ludo King pour moi** », « joue à 2048 et fais le plus de points possible »…
Jarvis ouvre le jeu puis boucle : il **regarde l'écran** (capture), le cerveau choisit quelques gestes (tap, glissé, appui long, attente), il les fait, puis re-regarde.

- Nécessite : contrôle d'écran activé, Android 11+, et **« Jarvis en arrière-plan » (la bulle) actif** : c'est ta façon de l'arrêter.
- **Arrêter** : touche la bulle, ou dis « arrête de jouer ». Garde-fous : 150 tours ou 15 minutes maximum, arrêt si l'écran ne change plus.
- Adapté aux jeux **au tour par tour, puzzles, cartes, jeux « idle »** (taper vite au même endroit). Chaque tour prend quelques secondes : trop lent pour les jeux d'action en temps réel.
- Sécurité : il ne touche pas aux achats, aux pubs « Installer », aux chats d'autres joueurs ni aux comptes. S'il hésite, il s'arrête et te le dit.
- Fonctionne avec Gemini, ou avec ton fournisseur (OpenAI, OpenRouter… modèle qui lit les images). **Redéploie le backend** (nouvelle route `/game-step`).
- Attention : certains jeux en ligne interdisent l'automatisation dans leurs conditions d'utilisation.

## Tâches en plusieurs étapes

Dis une phrase naturelle : « Ouvre WhatsApp, cherche Crépin, écris “Salut” et envoie le message ».
Jarvis ouvre l'app, cherche, écrit et envoie, **en vérifiant chaque étape** (l'écran doit prouver que ça a marché) avant la suivante,
puis confirme à voix haute. Si l'interface a changé ou si un élément est introuvable, il bascule sur une boucle adaptative :
il regarde l'écran, le cerveau choisit la prochaine action, on vérifie son effet, et il essaie une autre piste si rien ne bouge.
Par sécurité il refuse de toucher à un bouton d'achat / paiement / suppression que tu n'as pas demandé. Un appui sur le micro ou la bulle interrompt la tâche.
Nécessite le contrôle d'écran activé (menu ⚙). Le mode « Jarvis en arrière-plan » (bulle) est conseillé : il continue d'écouter pendant qu'une autre app est ouverte.

## Voir les commandes

« Affiche les commandes » / « Montre-moi les commandes » ouvre la liste de toutes les commandes, par catégories ; un appui sur une commande la lance
(celles avec un [champ] te demandent la valeur). « Cherche les commandes pour WhatsApp » (ou « …système », « …appels ») filtre la liste.

**Ajouter une capacité** : outil dans `backend/server.js` + parsing dans `BackendClient.parseAction` + une ligne `cmd(...)` dans
`CommandCatalog.kt`. Le script `scripts/check-commands.js` (lancé par GitHub Actions) fait échouer le build s'il manque la ligne du catalogue :
la liste affichée est donc toujours à jour. Pense à redéployer le backend.

## Sécurité

`local.properties` et `backend/.env` sont dans `.gitignore` — ne les publie jamais.
