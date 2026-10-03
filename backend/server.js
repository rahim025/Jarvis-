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

async function callLLM(messages, llm) {
  const response = await fetch(`${llm.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${llm.apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: llm.model, tools: TOOLS, messages }),
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

// Analyse multimodale (capture d'écran envoyée en base64) par Gemini.
app.post('/vision', checkSecret, async (req, res) => {
  const { prompt, imageBase64, mimeType, screenText } = req.body;
  if (!prompt || !imageBase64) {
    return res.status(400).json({ error: 'Les champs "prompt" et "imageBase64" sont requis.' });
  }
  const geminiKey = geminiKeyFrom(req.body);
  if (!geminiKey) return res.status(400).json({ error: "Clé Gemini manquante : menu ⚙ > Clés API & fournisseurs." });
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
