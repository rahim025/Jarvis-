require('dotenv').config();
const express = require('express');
const cors = require('cors');
const fetch = require('node-fetch');
const { buildMemoryContext, saveTurn } = require('./jarvisMemory');

const app = express();
app.use(cors());
app.use(express.json({ limit: '10mb' })); // limit généreux pour les images en base64

const PORT = process.env.PORT || 3000;
const GROQ_API_KEY = process.env.GROQ_API_KEY;
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;

// Optionnel : un secret partagé simple pour que seule ton app puisse appeler le backend.
// Si APP_SHARED_SECRET n'est pas défini, la vérification est désactivée (pratique en dev).
const APP_SHARED_SECRET = process.env.APP_SHARED_SECRET;

// Utilisé si l'app envoie encore une requête sans userId (ancienne version, etc.).
const DEFAULT_USER_ID = 'rahim';

function checkSecret(req, res, next) {
  if (!APP_SHARED_SECRET) return next(); // pas configuré = pas de check
  const header = req.headers['x-app-secret'];
  if (header !== APP_SHARED_SECRET) {
    return res.status(401).json({ error: 'Non autorisé' });
  }
  next();
}

// Mêmes outils que côté Android (GroqClient.kt) — à garder synchronisés.
const tools = [
  tool('open_app', "Ouvre une application par son nom", { app_name: "nom de l'app, ex: WhatsApp" }),
  tool('send_sms', "Envoie un SMS à un contact", { contact: "nom ou numéro", message: "contenu du SMS" }),
  tool('call_contact', "Appelle un contact par son nom ou son numéro", { contact: "nom ou numéro" }),
  tool('click_on_screen', "Clique sur un élément visible à l'écran par son texte/label", { label: "texte du bouton/élément" }),
  tool('type_text', "Tape du texte dans le champ actuellement sélectionné", { text: "texte à taper" }),
  tool('go_home', "Retourne à l'écran d'accueil du téléphone", {}),
  tool('go_back', "Appuie sur le bouton retour", {}),
  tool('close_app', "Ferme/quitte l'application actuellement ouverte au premier plan", {}),
  tool('scroll_up', "Fait défiler l'écran vers le haut (remonter dans le contenu)", {}),
  tool('scroll_down', "Fait défiler l'écran vers le bas (voir la suite du contenu)", {}),
  tool('scroll_left', "Fait défiler l'écran vers la gauche", {}),
  tool('scroll_right', "Fait défiler l'écran vers la droite", {}),
];

function tool(name, description, params) {
  const properties = {};
  Object.entries(params).forEach(([key, desc]) => {
    properties[key] = { type: 'string', description: desc };
  });
  return {
    type: 'function',
    function: {
      name,
      description,
      parameters: {
        type: 'object',
        properties,
        required: Object.keys(params),
      },
    },
  };
}

app.get('/', (req, res) => {
  res.json({ status: 'ok', service: 'jarvis-backend' });
});

// L'app Android envoie le texte reconnu par la voix ici.
// Cet endpoint parle à Groq et renvoie soit du texte, soit une action à exécuter.
app.post('/ask', checkSecret, async (req, res) => {
  const { text } = req.body;
  const userId = req.body.userId || DEFAULT_USER_ID;
  if (!text) return res.status(400).json({ error: 'Le champ "text" est requis.' });

  try {
    // Faits connus + souvenirs pertinents, à injecter dans le prompt système.
    const memoryContext = await buildMemoryContext(userId, text).catch((err) => {
      console.error('Mémoire indisponible:', err);
      return ''; // ne bloque jamais la réponse si la mémoire échoue
    });

    const response = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${GROQ_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'openai/gpt-oss-120b',
        tools,
        messages: [
          {
            role: 'system',
            content:
              "Tu es Jarvis, un assistant personnel sur Android. " +
              "Si la demande nécessite une action sur le téléphone, appelle l'outil correspondant. " +
              "Sinon, réponds simplement en texte, en français, de façon concise." +
              (memoryContext ? `\n\n${memoryContext}` : ''),
          },
          { role: 'user', content: text },
        ],
      }),
    });

    const data = await response.json();
    const message = data.choices?.[0]?.message;

    if (!message) {
      console.error('Réponse Groq invalide:', JSON.stringify(data));
      return res.status(502).json({ error: 'Réponse Groq invalide', raw: data });
    }

    const toolCall = message.tool_calls?.[0];
    if (toolCall) {
      const args = JSON.parse(toolCall.function.arguments);
      // On garde une trace de l'action dans la mémoire (sans bloquer la réponse).
      saveTurn(userId, 'user', text).catch(console.error);
      saveTurn(userId, 'assistant', `[action] ${toolCall.function.name}`).catch(console.error);
      return res.json({ type: 'action', name: toolCall.function.name, args });
    }

    const reply = message.content || '...';
    saveTurn(userId, 'user', text).catch(console.error);
    saveTurn(userId, 'assistant', reply).catch(console.error);
    return res.json({ type: 'speak', text: reply });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

// Pour l'analyse multimodale (ex: capture d'écran envoyée en base64).
app.post('/vision', checkSecret, async (req, res) => {
  const { prompt, imageBase64, mimeType } = req.body;
  if (!prompt || !imageBase64) {
    return res.status(400).json({ error: 'Les champs "prompt" et "imageBase64" sont requis.' });
  }

  try {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${GEMINI_API_KEY}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{
          parts: [
            { text: prompt },
            { inline_data: { mime_type: mimeType || 'image/png', data: imageBase64 } },
          ],
        }],
      }),
    });

    const data = await response.json();
    const text = data.candidates?.[0]?.content?.parts?.[0]?.text || '...';
    res.json({ type: 'speak', text });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'Erreur serveur', detail: err.message });
  }
});

app.listen(PORT, () => {
  console.log(`Jarvis backend en écoute sur le port ${PORT}`);
});
