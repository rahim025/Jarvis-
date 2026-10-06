require('dotenv').config();
const express = require('express');
const cors = require('cors');
const fetch = require('node-fetch');

const app = express();
app.use(cors());
app.use(express.json({ limit: '10mb' })); // limite généreuse pour les captures d'écran en base64

const PORT = process.env.PORT || 3000;
const GROQ_API_KEY = process.env.GROQ_API_KEY;
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const GROQ_MODEL = process.env.GROQ_MODEL || 'openai/gpt-oss-120b';
// Modèle Gemini (vision d'écran + recherche web). L'alias -latest suit toujours le dernier Flash.
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-flash-latest';
const CREATOR_NAME = process.env.CREATOR_NAME || 'Rahim Batchabi';
const DEFAULT_CITY = process.env.DEFAULT_CITY || 'Cotonou';
const TIKTOK_USERNAME = (process.env.TIKTOK_USERNAME || '').trim().replace(/^@/, '');

// Optionnel : un secret partagé simple pour que seule ton app puisse appeler le backend.
// Si APP_SHARED_SECRET n'est pas défini, la vérification est désactivée (pratique en dev).
const APP_SHARED_SECRET = process.env.APP_SHARED_SECRET;

// Sauvegarde cloud optionnelle (Firestore). La vraie mémoire d'éléphant vit sur le
// téléphone et est envoyée avec chaque question ; Firestore n'est qu'un bonus.
let cloudMemory = null;
if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON && process.env.EMBEDDING_PROVIDER_URL) {
  try {
    const m = require('./jarvisMemory');
    if (m.enabled) cloudMemory = m;
  } catch (err) {
    console.error('Mémoire cloud désactivée:', err.message);
  }
}

const DEFAULT_USER_ID = 'rahim';

function checkSecret(req, res, next) {
  if (!APP_SHARED_SECRET) return next();
  if (req.headers['x-app-secret'] !== APP_SHARED_SECRET) {
    return res.status(401).json({ error: 'Non autorisé' });
  }
  next();
}

// ─────────────────────────────────────────────────────────────────────────────
// OUTILS
// ─────────────────────────────────────────────────────────────────────────────

function tool(name, description, params = {}, required = Object.keys(params)) {
  const properties = {};
  Object.entries(params).forEach(([key, desc]) => {
    properties[key] = { type: 'string', description: desc };
  });
  return {
    type: 'function',
    function: { name, description, parameters: { type: 'object', properties, required } },
  };
}

// Outils exécutés sur le TÉLÉPHONE (mêmes noms que BackendClient.kt — à garder synchronisés).
const CLIENT_TOOLS = [
  tool('open_app', "Ouvre une application par son nom", { app_name: "nom de l'app, ex: WhatsApp" }),
  tool('send_sms', "Prépare un SMS à un contact", { contact: "nom ou numéro", message: "contenu du SMS" }),
  tool('call_contact', "Passe un appel téléphonique normal à un contact (nom dit à voix haute, surnom ou numéro)", {
    contact: "nom, surnom ou numéro de téléphone",
    app: "whatsapp seulement si l'utilisateur demande un appel WhatsApp (optionnel)",
  }, ['contact']),
  tool('whatsapp_call', "Appelle un contact sur WhatsApp (voix ou vidéo)", {
    contact: "nom du contact",
    video: "true pour un appel vidéo, false pour un appel vocal",
  }, ['contact']),
  tool('redial', "Rappelle le dernier numéro appelé (« rappelle le dernier numéro », « refais l'appel »)"),
  tool('end_call', "Raccroche l'appel en cours"),
  tool('speaker', "Active ou coupe le haut-parleur pendant un appel", { state: "on ou off" }),
  tool('click_on_screen', "Clique sur un élément visible à l'écran par son texte/label", { label: "texte du bouton/élément" }),
  tool('type_text', "Tape du texte dans le champ actuellement sélectionné", { text: "texte à taper" }),
  tool('go_home', "Retourne à l'écran d'accueil du téléphone"),
  tool('go_back', "Appuie sur le bouton retour"),
  tool('close_app', "Ferme/quitte l'application actuellement ouverte au premier plan"),
  tool('scroll_up', "Fait défiler l'écran vers le haut"),
  tool('scroll_down', "Fait défiler l'écran vers le bas"),
  tool('scroll_left', "Fait défiler l'écran vers la gauche"),
  tool('scroll_right', "Fait défiler l'écran vers la droite"),

  // Mémoire d'éléphant
  tool('memorize', "Retient une information durable sur l'utilisateur (prénom, goûts, anniversaires, proches, projets, habitudes, préférences...). À appeler dès que l'utilisateur dit « retiens », « n'oublie pas », ou partage une info personnelle qui servira plus tard.", {
    key: "clé courte en minuscules, ex: prénom, plat préféré, anniversaire de maman",
    value: "la valeur à retenir, formulée clairement",
  }),
  tool('forget', "Oublie une information précédemment retenue", { key: "clé de l'information à oublier" }),
  tool('list_memory', "Liste à voix haute ce que Jarvis a retenu sur l'utilisateur"),

  // Fonctions portées de la version Windows
  tool('set_volume', "Règle le volume du téléphone", {
    direction: "up (monter), down (baisser), mute (couper) ou set (niveau précis)",
    percent: "niveau en pourcentage de 0 à 100 (seulement pour set)",
  }, ['direction']),
  tool('media_control', "Contrôle la musique/vidéo en cours (lecture, pause, suivant, précédent, stop)", {
    command: "play, pause, play_pause, next, previous ou stop",
  }),
  tool('play_music', "Cherche et lance une chanson ou un artiste (Spotify si installé, sinon YouTube)", {
    query: "chanson ou artiste",
    app: "spotify ou youtube (optionnel)",
  }, ['query']),
  tool('flashlight', "Allume ou éteint la lampe torche", { state: "on ou off" }),
  tool('set_brightness', "Règle la luminosité de l'écran", { percent: "de 1 à 100" }),
  tool('set_alarm', "Programme une alarme à une heure précise", {
    hour: "heure 0-23", minute: "minute 0-59", label: "nom de l'alarme (optionnel)",
  }, ['hour', 'minute']),
  tool('set_timer', "Lance un minuteur", { seconds: "durée en secondes", label: "nom (optionnel)" }, ['seconds']),
  tool('open_url', "Ouvre un site web dans le navigateur", { url: "adresse du site" }),
  tool('navigate', "Lance la navigation GPS vers un lieu", { destination: "adresse ou lieu" }),
  tool('device_status', "Donne l'état du téléphone : batterie, mémoire vive, stockage"),
  tool('run_task', "Exécute une tâche en PLUSIEURS ÉTAPES dans une application (ouvrir l'app, chercher, écrire, envoyer…), en vérifiant chaque étape et en s'adaptant si l'écran change. À utiliser dès qu'une demande enchaîne plusieurs actions à l'écran (ex: « ouvre WhatsApp, cherche Crépin, écris Salut et envoie ») au lieu d'enchaîner open_app / click_on_screen / type_text.", {
    kind: "send_message (envoyer un message à un contact dans une app de messagerie), search (chercher quelque chose dans une app) ou other (toute autre tâche à plusieurs étapes)",
    app: "nom de l'application, ex: WhatsApp",
    goal: "objectif complet en une phrase claire, ex: Ouvrir WhatsApp, chercher Crépin, écrire Salut et envoyer",
    contact: "pour send_message : nom du contact (optionnel sinon)",
    message: "pour send_message : texte EXACT du message à envoyer",
    query: "pour search : le texte à chercher (optionnel sinon)",
    submit: "true pour valider la recherche avec Entrée (YouTube, Google, Play Store…), false pour une simple recherche de contact ou de discussion",
  }, ['kind', 'app', 'goal']),
  tool('set_toggle', "Active ou désactive le Wi-Fi ou le Bluetooth du téléphone", {
    setting: "wifi ou bluetooth",
    state: "on ou off",
  }),
  tool('show_commands', "Affiche à l'écran la liste des commandes disponibles, regroupées par catégories, quand l'utilisateur demande les commandes, ce que tu sais faire, ou « les commandes pour WhatsApp »", {
    filter: "application ou thème à filtrer, ex: WhatsApp, système, appels (vide = toutes les commandes)",
  }, []),
  tool('keep_conversation', "L'utilisateur a une discussion WhatsApp / Messenger OUVERTE à l'écran et demande à Jarvis de garder la conversation avec cette personne, de continuer la discussion ou de lui répondre à sa place (« garde la conversation avec lui », « discute avec elle à ma place », « continue cette discussion », « réponds-lui »). Jarvis lit l'écran, retient la discussion et répond désormais à sa place. À utiliser quand l'utilisateur dit « lui / elle / cette personne / cette discussion » SANS nommer de contact ; s'il nomme un contact sans être dans sa discussion, utilise auto_reply.", {
    contact: "nom du contact seulement si l'utilisateur le dit ; sinon vide (Jarvis le lit à l'écran)",
  }, []),
  tool('auto_reply', "Active, coupe ou résume les RÉPONSES AUTOMATIQUES : Jarvis répond à la place de l'utilisateur, comme s'il écrivait lui-même, aux messages WhatsApp / Messenger / Facebook de certains contacts (ex: « réponds à ma place à Crépin », « discute avec Maman sur WhatsApp », « arrête les réponses automatiques », « pour qui réponds-tu ? »). N'utilise PAS run_task pour ça.", {
    mode: "on (activer), off (arrêter), status (dire pour qui c'est actif) ou forget (effacer ce que Jarvis retient de la conversation avec ce contact)",
    contact: "nom du contact, ou « tout le monde » ; vide pour tout arrêter ou pour status",
    app: "whatsapp, messenger ou facebook (optionnel)",
  }, ['mode']),
  tool('describe_screen', "Regarde l'écran du téléphone (capture + texte affiché) comme le ferait l'utilisateur : à utiliser dès qu'il dit « regarde », « qu'est-ce qui est affiché », « lis-moi l'écran », « résume/traduis cette page », « c'est quoi cette erreur », « que dit ce message »…", {
    question: "ce que l'utilisateur veut savoir sur l'écran",
  }),
];

// Outils exécutés ICI, sur le serveur, avant de répondre.
const SERVER_TOOLS = [
  tool('get_weather', "Donne la météo actuelle et la prévision du jour pour une ville", {
    city: "nom de la ville (optionnel : ville habituelle de l'utilisateur sinon)",
  }, []),
  tool('web_search', "Cherche sur internet : actualités, résultats sportifs, classements, prix, faits récents, toute info qui peut avoir changé", {
    query: "la recherche, formulée complètement",
  }),
  tool('tiktok_followers', "Donne le nombre d'abonnés TikTok de l'utilisateur"),
];

const TOOLS = [...CLIENT_TOOLS, ...SERVER_TOOLS];
const SERVER_TOOL_NAMES = new Set(SERVER_TOOLS.map((t) => t.function.name));

// ─────────────────────────────────────────────────────────────────────────────
// OUTILS SERVEUR (portés de la version Windows)
// ─────────────────────────────────────────────────────────────────────────────

const CODES_METEO = {
  0: 'ciel dégagé', 1: 'principalement clair', 2: 'partiellement nuageux', 3: 'couvert',
  45: 'brouillard', 48: 'brouillard givrant', 51: 'bruine légère', 53: 'bruine modérée',
  55: 'bruine dense', 61: 'pluie faible', 63: 'pluie modérée', 65: 'pluie forte',
  71: 'neige faible', 73: 'neige modérée', 75: 'neige forte', 80: 'averses faibles',
  81: 'averses modérées', 82: 'averses violentes', 85: 'averses de neige',
  86: 'averses de neige fortes', 95: 'orage', 96: 'orage avec grêle', 99: 'orage violent avec grêle',
};

async function getWeather(city) {
  try {
    const name = city || DEFAULT_CITY;
    const geoRes = await fetch(
      'https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&format=json&name=' +
        encodeURIComponent(name)
    );
    const geo = await geoRes.json();
    const place = geo.results && geo.results[0];
    if (!place) return `Je ne trouve pas la ville ${name}.`;

    const url =
      'https://api.open-meteo.com/v1/forecast?latitude=' + place.latitude +
      '&longitude=' + place.longitude +
      '&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weathercode' +
      '&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max' +
      '&timezone=auto&forecast_days=1&wind_speed_unit=kmh';
    const data = await (await fetch(url)).json();
    const cur = data.current;
    const day = data.daily;
    const desc = CODES_METEO[cur.weathercode] || 'conditions inconnues';
    return (
      `Météo à ${place.name} : ${Math.round(cur.temperature_2m)} degrés (ressenti ${Math.round(cur.apparent_temperature)}), ` +
      `${desc}, humidité ${Math.round(cur.relative_humidity_2m)} pour cent, vent ${Math.round(cur.wind_speed_10m)} km/h. ` +
      `Aujourd'hui : minimum ${Math.round(day.temperature_2m_min[0])}, maximum ${Math.round(day.temperature_2m_max[0])}, ` +
      `risque de pluie ${Math.round(day.precipitation_probability_max[0] || 0)} pour cent.`
    );
  } catch (err) {
    console.error('Météo:', err.message);
    return "Je n'arrive pas à récupérer la météo pour le moment.";
  }
}

async function webSearch(query, geminiKey = GEMINI_API_KEY, geminiModel = GEMINI_MODEL) {
  if (!geminiKey) return "La recherche web n'est pas configurée (clé Gemini manquante : menu ⚙ > Clés API).";
  try {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent?key=${geminiKey}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{
          parts: [{
            text:
              'Réponds en français, de façon courte et factuelle (4 phrases maximum), sans markdown, ' +
              'avec des chiffres arrondis. Question : ' + query,
          }],
        }],
        tools: [{ google_search: {} }],
      }),
    });
    const data = await res.json();
    const text = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || '').join(' ').trim();
    return text || "Je n'ai rien trouvé pour cette recherche.";
  } catch (err) {
    console.error('Recherche web:', err.message);
    return "La recherche web a échoué pour le moment.";
  }
}

async function tiktokFollowers() {
  if (!TIKTOK_USERNAME) {
    return "Le compte TikTok n'est pas configuré. Ajoute TIKTOK_USERNAME dans les variables du backend.";
  }
  try {
    const res = await fetch(`https://www.tiktok.com/@${TIKTOK_USERNAME}`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
        'Accept-Language': 'fr-FR,fr;q=0.9,en;q=0.8',
      },
    });
    const html = await res.text();
    const m = html.match(/"followerCount"\s*:\s*(\d+)/);
    if (!m) return `Je n'arrive pas à lire les abonnés de @${TIKTOK_USERNAME} : TikTok bloque parfois les lectures automatiques.`;
    return `Le compte TikTok @${TIKTOK_USERNAME} a ${Number(m[1])} abonnés.`;
  } catch (err) {
    return "Je n'arrive pas à joindre TikTok pour le moment.";
  }
}

async function runServerTool(name, args, geminiKey, geminiModel) {
  if (name === 'get_weather') return getWeather(args.city);
  if (name === 'web_search') return webSearch(args.query, geminiKey, geminiModel);
  if (name === 'tiktok_followers') return tiktokFollowers();
  return 'Outil inconnu.';
}

// ─────────────────────────────────────────────────────────────────────────────
// PERSONNALITÉ + MÉMOIRE (portées de la version Windows)
// ─────────────────────────────────────────────────────────────────────────────

function buildSystemPrompt({ facts, relevant, now, cloudContext }) {
  const factEntries = Object.entries(facts || {});
  const userName =
    (facts && (facts['prénom'] || facts['prenom'] || facts['surnom'])) || 'Monsieur';

  const factLines = factEntries.map(([k, v]) => `  - ${k} : ${v}`).join('\n');
  const relevantLines = (relevant || [])
    .map((t) => `  - [${t.date}] ${userName} : ${t.user}\n    Toi : ${t.jarvis}`)
    .join('\n');

  return [
    `Tu es JARVIS, une IA sophistiquée, élégante et experte, qui vit dans le téléphone Android de ${userName}. ` +
      `${CREATOR_NAME} est ton créateur. Appelle l'utilisateur ${userName}, avec respect et une pointe de sarcasme affectueux.`,
    `Nous sommes le ${now || 'date inconnue'}.`,

    'Tu as une MÉMOIRE D\'ÉLÉPHANT : tu te souviens de tout ce qui a été dit lors des conversations passées. ' +
      'Les derniers échanges te sont fournis dans la conversation, les plus anciens pertinents ci-dessous. ' +
      'Réfère-toi-y naturellement quand c\'est utile, sans dire que tu consultes une mémoire.',

    'Expertise de niveau professionnel : mathématiques (solutions pas à pas), langue française (orthographe et grammaire irréprochables), ' +
      'conversions d\'unités et de devises, traduction, high-tech, ingénierie, sport, conseils pratiques.',

    'DIRECTIVES DE RÉPONSE :\n' +
      '- Sois direct et percutant, va à l\'essentiel : tes réponses sont lues à voix haute.\n' +
      '- N\'utilise JAMAIS de markdown (pas de **, *, # ni de listes à puces), ni d\'émojis.\n' +
      '- Arrondis les nombres (dis « 20 degrés », jamais « 20 virgule 3 »).\n' +
      '- Si la demande nécessite une action sur le téléphone ou une recherche, appelle l\'outil correspondant. ' +
      'Tu peux appeler plusieurs outils dans la même réponse si la phrase contient plusieurs demandes.\n' +
      '- Pour la météo sans ville précisée, utilise la ville des faits connus si elle existe.\n' +
      '- Dès que l\'utilisateur te dit de retenir quelque chose, ou partage une information personnelle durable ' +
      '(prénom, ville, goûts, anniversaires, proches, projets), appelle memorize avec une clé courte.\n' +
      '- Quand une demande enchaîne plusieurs actions DANS une application (ouvrir, chercher, écrire, envoyer…), appelle UNE SEULE fois run_task ' +
      '(send_message pour envoyer un message, search pour chercher, other sinon) : n\'enchaîne jamais open_app, click_on_screen et type_text toi-même, ' +
      'car run_task attend et vérifie chaque étape. Pour le Wi-Fi ou le Bluetooth, utilise set_toggle. Pour « affiche les commandes », utilise show_commands.\n' +
      '- Pour appeler : « appelle X » = call_contact (appel normal), « appelle X sur WhatsApp » = whatsapp_call. ' +
      'Si l\'utilisateur désigne quelqu\'un par un surnom (maman, mon amour...), garde exactement ses mots dans contact ; ' +
      'l\'application retrouvera le bon contact.\n' +
      '- Sinon, réponds simplement en texte, en français.',

    factLines ? `FAITS QUE TU CONNAIS SUR ${userName.toUpperCase()} :\n${factLines}` : '',
    relevantLines ? `ANCIENS ÉCHANGES EN LIEN AVEC CETTE QUESTION :\n${relevantLines}` : '',
    cloudContext ? `SOUVENIRS CLOUD :\n${cloudContext}` : '',
  ].filter(Boolean).join('\n\n');
}

function stripMarkdown(text) {
  return String(text || '').replace(/[*#`]+/g, '').replace(/\s{2,}/g, ' ').trim();
}

// ─────────────────────────────────────────────────────────────────────────────
// ROUTES
// ─────────────────────────────────────────────────────────────────────────────

app.get('/', (req, res) => {
  res.json({ status: 'ok', service: 'jarvis-backend' });
});

// ── Fournisseur LLM : celui envoyé par l'app (menu ⚙ > Clés API), sinon les variables Render ──

const DEFAULT_LLM_BASE_URL = 'https://api.groq.com/openai/v1';

function isSafeBaseUrl(raw) {
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:') return false;
    const h = url.hostname.toLowerCase();
    if (h === 'localhost' || h.endsWith('.local') || h.endsWith('.internal')) return false;
    if (h.startsWith('[')) return false; // adresses IPv6 littérales
    if (/^(0\.|10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h)) return false;
    return true;
  } catch (e) {
    return false;
  }
}

function resolveLLM(body) {
  const o = body && typeof body.llm === 'object' && body.llm ? body.llm : {};
  const str = (v) => (typeof v === 'string' ? v.trim() : '');
  const apiKey = str(o.apiKey) || GROQ_API_KEY;
  const model = str(o.model) || GROQ_MODEL;
  const baseUrl = (str(o.baseUrl) || DEFAULT_LLM_BASE_URL).replace(/\/+$/, '');
  if (!isSafeBaseUrl(baseUrl)) {
    const err = new Error("URL du fournisseur refusée (https obligatoire, pas d'adresse locale).");
    err.userMessage = err.message;
    throw err;
  }
  return { apiKey, model, baseUrl };
}

function geminiModelFrom(body) {
  const m = body && typeof body.geminiModel === 'string' ? body.geminiModel.trim() : '';
  return /^[\w.\-]+$/.test(m) ? m : GEMINI_MODEL;
}

function geminiKeyFrom(body) {
  const k = body && typeof body.geminiKey === 'string' ? body.geminiKey.trim() : '';
  return k || GEMINI_API_KEY;
}

async function callLLM(messages, llm, tools = TOOLS) {
  const response = await fetch(`${llm.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${llm.apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: llm.model, ...(tools ? { tools } : {}), messages }),
  });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch (e) { data = { error: { message: text.slice(0, 200) } }; }
  const message = data.choices?.[0]?.message;
  if (!message) {
    // On remonte le VRAI message du fournisseur (clé invalide, modèle arrêté, quota…) au lieu d'un "invalide" muet.
    const detail = data.error?.message || `réponse vide (HTTP ${response.status})`;
    console.error('LLM', llm.baseUrl, llm.model, response.status, detail);
    const err = new Error(detail);
    err.userMessage = `Fournisseur IA (${response.status}) : ${detail}`;
    throw err;
  }
  // Certains modèles (MiniMax…) glissent leur raisonnement dans <think>…</think> : on ne le lit pas à voix haute.
  if (typeof message.content === 'string') {
    message.content = message.content.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  }
  return message;
}

// L'app Android envoie le texte reconnu + la mémoire utile (faits, derniers échanges, souvenirs).
app.post('/ask', checkSecret, async (req, res) => {
  const { text } = req.body;
  const userId = req.body.userId || DEFAULT_USER_ID;
  if (!text) return res.status(400).json({ error: 'Le champ "text" est requis.' });
  let llm;
  try {
    llm = resolveLLM(req.body);
  } catch (e) {
    return res.status(400).json({ error: e.userMessage || e.message });
  }
  if (!llm.apiKey) {
    return res.status(400).json({ error: "Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)." });
  }
  const geminiKey = geminiKeyFrom(req.body);
  const geminiModel = geminiModelFrom(req.body);

  const facts = req.body.facts && typeof req.body.facts === 'object' ? req.body.facts : {};
  const recent = Array.isArray(req.body.recent) ? req.body.recent : [];
  const relevant = Array.isArray(req.body.relevant) ? req.body.relevant : [];

  try {
    let cloudContext = '';
    if (cloudMemory) {
      cloudContext = await cloudMemory.buildMemoryContext(userId, text).catch(() => '');
    }

    const messages = [
      { role: 'system', content: buildSystemPrompt({ facts, relevant, now: req.body.now, cloudContext }) },
    ];
    recent.forEach((t) => {
      if (!t || !t.user) return;
      messages.push({ role: 'user', content: `[${t.date || ''}] ${t.user}` });
      messages.push({ role: 'assistant', content: t.jarvis || '' });
    });
    messages.push({ role: 'user', content: text });

    let finalText = '';
    for (let round = 0; round < 4; round++) {
      const message = await callLLM(messages, llm);
      const calls = message.tool_calls || [];

      if (calls.length === 0) {
        finalText = stripMarkdown(message.content) || '...';
        break;
      }

      const clientCalls = calls.filter((c) => !SERVER_TOOL_NAMES.has(c.function.name));
      const serverCalls = calls.filter((c) => SERVER_TOOL_NAMES.has(c.function.name));

      // Des actions pour le téléphone : on les renvoie (avec la phrase d'accompagnement éventuelle).
      if (clientCalls.length > 0) {
        const actions = [];
        const lead = stripMarkdown(message.content);
        if (lead) actions.push({ name: 'speak', args: { text: lead } });
        clientCalls.forEach((c) => {
          let args = {};
          try { args = JSON.parse(c.function.arguments || '{}'); } catch (e) { /* args vides */ }
          actions.push({ name: c.function.name, args });
        });
        if (cloudMemory) {
          cloudMemory.saveTurn(userId, 'user', text).catch(() => {});
        }
        return res.json({ type: 'actions', actions });
      }

      // Uniquement des outils serveur : on les exécute puis on laisse le modèle conclure.
      messages.push({ role: 'assistant', content: message.content || '', tool_calls: calls });
      for (const c of serverCalls) {
        let args = {};
        try { args = JSON.parse(c.function.arguments || '{}'); } catch (e) { /* args vides */ }
        const result = await runServerTool(c.function.name, args, geminiKey, geminiModel);
        messages.push({ role: 'tool', tool_call_id: c.id, content: String(result) });
      }
    }

    if (!finalText) finalText = "Je n'ai pas réussi à conclure, pouvez-vous reformuler ?";
    if (cloudMemory) {
      cloudMemory.saveTurn(userId, 'user', text).catch(() => {});
      cloudMemory.saveTurn(userId, 'assistant', finalText).catch(() => {});
    }
    return res.json({ type: 'speak', text: finalText });
  } catch (err) {
    console.error(err);
    if (err.userMessage) return res.status(502).json({ error: err.userMessage });
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

// Boucle adaptative : l'app envoie l'écran actuel + l'historique, on renvoie la PROCHAINE action.
const AGENT_ACTIONS = new Set(['click', 'type', 'enter', 'scroll', 'back', 'home', 'open_app', 'wait', 'done', 'fail']);

function parseJsonObject(text) {
  const m = String(text || '').match(/\{[\s\S]*\}/);
  if (!m) return null;
  try { return JSON.parse(m[0]); } catch (e) { return null; }
}

app.post('/agent', checkSecret, async (req, res) => {
  const { goal } = req.body;
  if (!goal) return res.status(400).json({ error: 'Le champ "goal" est requis.' });
  let llm;
  try {
    llm = resolveLLM(req.body);
  } catch (e) {
    return res.status(400).json({ error: e.userMessage || e.message });
  }
  if (!llm.apiKey) {
    return res.status(400).json({ error: "Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)." });
  }
  const elements = Array.isArray(req.body.elements) ? req.body.elements.slice(0, 80) : [];
  const history = Array.isArray(req.body.history) ? req.body.history.slice(-12) : [];

  const system =
    "Tu pilotes un téléphone Android pour atteindre un objectif, UNE action à la fois. " +
    "Tu reçois l'objectif, l'application au premier plan, la liste numérotée des éléments visibles " +
    "(k = texte | bouton | champ | case, c = état d'une case) et l'historique des actions déjà tentées avec leur résultat.\n" +
    'Réponds UNIQUEMENT par un objet JSON, sans markdown : {"action": "click|type|enter|scroll|back|home|open_app|wait|done|fail", ' +
    '"index": numéro de l\'élément (click, type, enter), "text": "texte à taper (type)", "direction": "up|down|left|right (scroll)", ' +
    '"app": "nom (open_app)", "reason": "justification courte", "say": "phrase à dire à voix haute (done ou fail)"}.\n' +
    "Règles :\n" +
    "- Choisis l'élément qui fait avancer l'objectif. Pour écrire, utilise type sur un champ (ou le champ actif).\n" +
    "- Regarde l'historique : si une action a eu « aucun effet » ou a échoué, change de stratégie (autre élément, défiler, retour, autre chemin). Ne répète jamais la même action qui a échoué.\n" +
    "- Si l'élément cherché n'est pas visible, essaie de défiler ou d'ouvrir un menu avant d'abandonner.\n" +
    "- Utilise done UNIQUEMENT quand l'écran prouve que l'objectif est atteint ; say = confirmation courte en français.\n" +
    "- Ne fais jamais d'achat, de paiement, de suppression ni d'envoi d'argent, sauf si l'objectif le demande explicitement.\n" +
    "- Si l'objectif est impossible ou bloqué, utilise fail avec une explication courte dans say.";

  const user =
    `Objectif : ${goal}\n` +
    `Application au premier plan : ${req.body.package || 'inconnue'}\n` +
    `Éléments visibles :\n${JSON.stringify(elements)}\n` +
    `Historique :\n${history.length ? history.join('\n') : '(aucune action pour le moment)'}`;

  try {
    const message = await callLLM(
      [{ role: 'system', content: system }, { role: 'user', content: user }],
      llm,
      null
    );
    const decision = parseJsonObject(message.content);
    if (!decision || !AGENT_ACTIONS.has(String(decision.action))) {
      return res.json({ action: 'wait', reason: 'réponse du cerveau illisible' });
    }
    res.json({
      action: String(decision.action),
      index: decision.index === undefined || decision.index === null ? null : Number(decision.index),
      text: decision.text ? String(decision.text) : '',
      direction: decision.direction ? String(decision.direction) : '',
      app: decision.app ? String(decision.app) : '',
      reason: decision.reason ? String(decision.reason) : '',
      say: decision.say ? stripMarkdown(decision.say) : '',
    });
  } catch (err) {
    console.error(err);
    if (err.userMessage) return res.status(502).json({ error: err.userMessage });
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

// Durée écoulée, dite comme à l'oral (« il y a 5 min », « hier »).
function agoText(ms) {
  const m = Math.max(0, Math.round(ms / 60000));
  if (m < 2) return "à l'instant";
  if (m < 60) return `il y a ${m} min`;
  const h = Math.round(m / 60);
  if (h < 24) return `il y a ${h} h`;
  const d = Math.round(h / 24);
  return d === 1 ? 'hier' : `il y a ${d} jours`;
}

function historyLines(history, nowMs, contact) {
  return history
    .map((m) => `[${agoText(nowMs - Number(m.ts || nowMs))}] ${m.fromMe ? 'Moi' : contact} : ${String(m.text || '').slice(0, 500)}`)
    .join('\n');
}

// Réponses automatiques : écrit la réponse que l'utilisateur aurait donnée, en gardant le fil de la conversation.
app.post('/reply', checkSecret, async (req, res) => {
  const contact = String(req.body.contact || '').slice(0, 80);
  const history = Array.isArray(req.body.history) ? req.body.history.slice(-30) : [];
  if (!contact || !history.length) return res.status(400).json({ error: 'contact et history sont requis.' });
  let llm;
  try {
    llm = resolveLLM(req.body);
  } catch (e) {
    return res.status(400).json({ error: e.userMessage || e.message });
  }
  if (!llm.apiKey) return res.status(400).json({ error: "Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)." });

  const nowMs = Number(req.body.nowMs) || Date.now();
  const nowText = new Date(nowMs).toLocaleString('fr-FR', { timeZone: 'Africa/Porto-Novo', dateStyle: 'full', timeStyle: 'short' });
  const summary = typeof req.body.summary === 'string' ? req.body.summary.slice(0, 2500) : '';

  const system =
    `Tu écris à la place de ${CREATOR_NAME}, sur ${req.body.app || 'une messagerie'}, pour répondre à son contact « ${contact} ». ` +
    "Tu dois te comporter comme lui : un vrai interlocuteur qui suit la conversation, pas un répondeur qui réagit au dernier message isolé.\n" +
    "Tu reçois : ce qu'on sait de lui, un résumé des échanges plus anciens avec ce contact (détails retenus : projets, rendez-vous, questions en suspens), " +
    "et le fil récent daté (« Moi » = lui). Lis TOUT avant d'écrire.\n" +
    "Garder le fil :\n" +
    "- Réponds au dernier message EN TENANT COMPTE de ce qui s'est dit avant : sujet en cours, question restée sans réponse, détail donné par le contact (prénoms, projets, rendez-vous, humeur).\n" +
    "- Ne te représente pas et ne resalue pas si l'échange est en cours (dernier message il y a moins de ~3 h). Après une longue pause, un petit salut naturel suffit.\n" +
    "- Cohérence : ne contredis JAMAIS ce que « Moi » a déjà dit ou promis plus haut, et ne répète pas une phrase déjà envoyée.\n" +
    "- Fais vivre l'échange comme un humain : tu peux rebondir sur un détail ou poser une question simple, sans en faire trop. Sache aussi conclure (« ok à plus », « merci ») sans relancer.\n" +
    "- Si le dernier message n'appelle pas de réponse (simple « ok », « 👍 », « merci » qui clôt la discussion), mets skip à true avec la raison « rien à répondre ».\n" +
    "Style : même langue que le contact, même ton, mêmes tournures, même longueur et mêmes habitudes (abréviations, émojis, ponctuation) que les messages « Moi » de l'historique. " +
    "Court et naturel, sans markdown, sans guillemets, sans te présenter.\n" +
    'Réponds UNIQUEMENT par un objet JSON : {"skip": true|false, "reason": "raison courte en français si skip", "reply": "le message à envoyer", ' +
    '"remember": "UN détail durable et utile à retenir sur ce contact ou la conversation (ex: passe son examen vendredi ; attend une réponse sur le prix), ou vide"}.\n' +
    "Mets skip à true (et ne réponds pas) dans ces cas :\n" +
    "- argent, paiement, transfert, prêt, mot de passe, code de vérification, données personnelles ou bancaires ;\n" +
    "- le contact demande un engagement (rendez-vous, promesse, décision, accord) ou une information que tu ne connais pas ;\n" +
    "- le contact demande sincèrement s'il parle à un robot / une IA / au vrai " + CREATOR_NAME + " (ne mens jamais là-dessus) ;\n" +
    "- urgence, santé, conflit, sujet grave ou émotionnel, ou tu n'es pas sûr de ce qu'il répondrait.\n" +
    "N'invente jamais de faits sur sa vie : en cas de doute, skip.";

  const user =
    `Date et heure actuelles (Bénin) : ${nowText}\n` +
    `Ce qu'on sait de ${CREATOR_NAME} : ${JSON.stringify(req.body.facts || {})}\n` +
    `Résumé des échanges plus anciens avec ${contact} :\n${summary || '(aucun pour le moment)'}\n` +
    `Fil récent (du plus ancien au plus récent) :\n${historyLines(history, nowMs, contact)}`;

  try {
    const message = await callLLM([{ role: 'system', content: system }, { role: 'user', content: user }], llm, null);
    const out = parseJsonObject(message.content);
    if (!out) return res.json({ skip: true, reason: 'réponse du cerveau illisible', reply: '', remember: '' });
    const reply = out.reply ? stripMarkdown(String(out.reply)).trim() : '';
    res.json({
      skip: out.skip === true || !reply,
      reason: out.reason ? String(out.reason) : '',
      reply,
      remember: out.remember ? String(out.remember).slice(0, 160) : '',
    });
  } catch (err) {
    console.error(err);
    if (err.userMessage) return res.status(502).json({ error: err.userMessage });
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

// Condense les vieux échanges d'une conversation + l'ancien résumé en un résumé court et à jour.
app.post('/summarize', checkSecret, async (req, res) => {
  const contact = String(req.body.contact || '').slice(0, 80);
  const messages = Array.isArray(req.body.messages) ? req.body.messages.slice(-120) : [];
  const previous = typeof req.body.summary === 'string' ? req.body.summary.slice(0, 3000) : '';
  if (!contact) return res.status(400).json({ error: 'contact requis.' });
  let llm;
  try {
    llm = resolveLLM(req.body);
  } catch (e) {
    return res.status(400).json({ error: e.userMessage || e.message });
  }
  if (!llm.apiKey) return res.status(400).json({ error: 'Aucune clé API.' });

  const nowMs = Date.now();
  const system =
    `Tu tiens la mémoire des conversations de ${CREATOR_NAME}. Fusionne l'ancien résumé et les nouveaux échanges avec « ${contact} » ` +
    "en UN résumé de 12 lignes maximum, en français, sous forme de puces « • ». Garde ce qui compte pour reprendre la conversation plus tard : " +
    "qui est ce contact pour lui et le ton de leur relation, sujets en cours, projets et dates, ce qui a été promis ou demandé, questions restées sans réponse. " +
    "Supprime le bavardage sans importance et ce qui est périmé. N'invente rien. Réponds UNIQUEMENT par le résumé.";
  const user =
    `Ancien résumé :\n${previous || '(aucun)'}\n\nNouveaux échanges :\n${historyLines(messages, nowMs, contact)}`;
  try {
    const message = await callLLM([{ role: 'system', content: system }, { role: 'user', content: user }], llm, null);
    res.json({ summary: stripMarkdown(String(message.content || '')).trim().slice(0, 2000) });
  } catch (err) {
    console.error(err);
    if (err.userMessage) return res.status(502).json({ error: err.userMessage });
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

// Lecture d'une discussion affichée à l'écran : contact, groupe ou pas, messages (moi / lui), petit résumé.
app.post('/read-chat', checkSecret, async (req, res) => {
  const elements = Array.isArray(req.body.elements) ? req.body.elements.slice(0, 150) : [];
  const imageBase64 = typeof req.body.imageBase64 === 'string' ? req.body.imageBase64 : '';
  const appName = String(req.body.app || 'messagerie');
  if (!elements.length && !imageBase64) return res.status(400).json({ error: 'Rien à lire.' });

  const instruction =
    `Tu regardes l'écran du téléphone de ${CREATOR_NAME}, avec une discussion ${appName} ouverte (ou pas). ` +
    "Tu reçois la capture (si disponible) et la liste des textes visibles avec leur position (x, y en pourcentage de l'écran : x petit = gauche, x grand = droite, y petit = haut).\n" +
    'Réponds UNIQUEMENT par un objet JSON : {"isChat": true|false, "group": true|false, "contact": "nom affiché en haut de la discussion", ' +
    '"messages": [{"fromMe": true|false, "text": "texte exact"}], "summary": "2 à 4 puces « • » : qui est ce contact pour lui (si ça se voit), sujets en cours, ce qui attend une réponse"}.\n' +
    "Règles : isChat = false si ce n'est pas une discussion de messagerie ouverte (liste de discussions, autre écran). " +
    "group = true si c'est une discussion de groupe (plusieurs participants, nom de groupe, noms d'auteurs au-dessus des messages). " +
    "messages : du plus ancien au plus récent, seulement les bulles visibles ; fromMe = true pour les bulles de l'utilisateur (à droite, souvent colorées), false pour celles du contact (à gauche). " +
    "Ignore dates, « en ligne », « vu », heures, boutons et champ de saisie. Recopie le texte exactement ; pour une photo, un vocal ou un sticker écris [photo], [vocal] ou [sticker]. N'invente rien.";
  const listing = `Textes visibles :\n${elements.map((e) => `${String(e.t || '').slice(0, 200)} (x=${e.x}, y=${e.y})`).join('\n')}`;

  try {
    let raw = '';
    const geminiKey = geminiKeyFrom(req.body);
    if (imageBase64 && geminiKey) {
      const geminiModel = geminiModelFrom(req.body);
      const url = `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent?key=${geminiKey}`;
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [{
            parts: [
              { text: `${instruction}\n\n${listing}` },
              { inline_data: { mime_type: req.body.mimeType || 'image/jpeg', data: imageBase64 } },
            ],
          }],
          generationConfig: { responseMimeType: 'application/json' },
        }),
      });
      const data = await response.json();
      raw = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || '').join(' ');
      if (!raw) console.error('read-chat Gemini', response.status, data.error?.message);
    }
    if (!raw) {
      // Sans capture (ou si Gemini échoue) : on lit uniquement le texte et les positions.
      let llm;
      try { llm = resolveLLM(req.body); } catch (e) { return res.status(400).json({ error: e.userMessage || e.message }); }
      if (!llm.apiKey) return res.status(400).json({ error: "Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)." });
      const message = await callLLM(
        [{ role: 'system', content: instruction }, { role: 'user', content: listing }], llm, null
      );
      raw = message.content || '';
    }
    const out = parseJsonObject(raw);
    if (!out) return res.status(502).json({ error: 'Lecture de la discussion illisible.' });
    const messages = (Array.isArray(out.messages) ? out.messages : [])
      .map((m) => ({ fromMe: m && m.fromMe === true, text: String((m && m.text) || '').trim().slice(0, 500) }))
      .filter((m) => m.text)
      .slice(-40);
    res.json({
      isChat: out.isChat === true && messages.length > 0,
      group: out.group === true,
      contact: out.contact ? String(out.contact).trim().slice(0, 80) : '',
      messages,
      summary: out.summary ? String(out.summary).trim().slice(0, 800) : '',
    });
  } catch (err) {
    console.error(err);
    if (err.userMessage) return res.status(502).json({ error: err.userMessage });
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

// Vision SANS Gemini : on utilise le « cerveau » choisi dans l'app (OpenAI, OpenRouter, Groq…).
// 1) capture + texte lu à l'écran (si le modèle sait lire les images), 2) sinon texte lu à l'écran seul.
async function visionWithBrain(req, res, { prompt, imageBase64, mimeType, screenText }) {
  let llm;
  try {
    llm = resolveLLM(req.body);
  } catch (err) {
    return res.status(400).json({ error: err.userMessage || err.message });
  }
  if (!llm.apiKey) {
    return res.status(400).json({
      error: 'Aucune clé IA : ajoute une clé (OpenAI, OpenRouter, Groq…) ou Gemini dans le menu ⚙ > Clés API & fournisseurs.',
    });
  }
  const hasText = typeof screenText === 'string' && screenText.trim();
  const screenPart = hasText
    ? "\n\nTexte exact lu à l'écran par le téléphone ([bouton] = on peut appuyer, [champ] = zone de saisie) :\n" +
      screenText.slice(0, 3000)
    : '';
  const style = 'Réponds en français, naturellement, en 4 phrases maximum, sans markdown, à sa demande : ';
  const withImage = [
    {
      type: 'text',
      text: "Tu es Jarvis. Voici une capture de l'écran du téléphone de l'utilisateur. Regarde-la comme le ferait " +
        "l'utilisateur. " + style + prompt + screenPart,
    },
    { type: 'image_url', image_url: { url: `data:${mimeType || 'image/jpeg'};base64,${imageBase64}` } },
  ];
  const textOnly =
    "Tu es Jarvis. Tu n'as pas l'image de l'écran, seulement le texte que le téléphone y lit : base-toi dessus " +
    "et dis-le franchement si cela ne suffit pas pour répondre. " + style + prompt + screenPart;

  let lastErr = null;
  try {
    const m = await callLLM([{ role: 'user', content: withImage }], llm, null);
    const text = stripMarkdown(m.content || '');
    if (text) return res.json({ type: 'speak', text });
  } catch (err) {
    lastErr = err; // modèle sans vision, image refusée, etc. : on retente avec le texte seul
  }
  if (hasText) {
    try {
      const m = await callLLM([{ role: 'user', content: textOnly }], llm, null);
      const text = stripMarkdown(m.content || '');
      if (text) return res.json({ type: 'speak', text });
    } catch (err) {
      lastErr = err;
    }
  }
  const detail = lastErr ? (lastErr.userMessage || lastErr.message) : 'réponse vide';
  return res.status(502).json({
    error: `Vision impossible avec « ${llm.model} » (${detail}). Choisis un modèle qui lit les images ` +
      '(ex. gpt-4o-mini chez OpenAI) ou ajoute une clé Gemini.',
  });
}

// Analyse multimodale (capture d'écran envoyée en base64) par Gemini, ou par le cerveau à défaut.
app.post('/vision', checkSecret, async (req, res) => {
  const { prompt, imageBase64, mimeType, screenText } = req.body;
  if (!prompt || !imageBase64) {
    return res.status(400).json({ error: 'Les champs "prompt" et "imageBase64" sont requis.' });
  }
  const geminiKey = geminiKeyFrom(req.body);
  if (!geminiKey) return visionWithBrain(req, res, { prompt, imageBase64, mimeType, screenText });
  const geminiModel = geminiModelFrom(req.body);

  const screenPart = typeof screenText === 'string' && screenText.trim()
    ? "\n\nTexte exact lu à l'écran par le téléphone ([bouton] = on peut appuyer, [champ] = zone de saisie) :\n" +
      screenText.slice(0, 3000)
    : '';

  try {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent?key=${geminiKey}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{
          parts: [
            {
              text:
                "Tu es Jarvis. Voici une capture de l'écran du téléphone de l'utilisateur. Regarde-la comme le ferait " +
                "l'utilisateur : repère l'application ouverte, le contenu, les messages, les boutons, les erreurs, les couleurs. " +
                'Réponds en français, naturellement, en 4 phrases maximum, sans markdown, à sa demande : ' + prompt + screenPart,
            },
            { inline_data: { mime_type: mimeType || 'image/png', data: imageBase64 } },
          ],
        }],
      }),
    });

    const data = await response.json();
    const raw = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || '').join(' ');
    const text = stripMarkdown(raw);
    if (!text) {
      const detail = data.error?.message || `réponse vide (HTTP ${response.status})`;
      console.error('Vision', geminiModel, response.status, detail);
      return res.status(502).json({ error: `Gemini (${response.status}) : ${detail}` });
    }
    res.json({ type: 'speak', text });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

app.listen(PORT, () => {
  console.log(`Jarvis backend en écoute sur le port ${PORT}`);
});
