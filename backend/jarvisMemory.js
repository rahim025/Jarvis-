// jarvisMemory.js — version Firestore
// À ajouter à ton backend Node/Express sur Render.
//
// Dépendance à installer : npm install firebase-admin
//
// Variable d'environnement nécessaire (Render > Environment) :
//   FIREBASE_SERVICE_ACCOUNT_JSON = contenu complet du fichier JSON téléchargé
//   (Firebase Console > Paramètres du projet > Comptes de service > Générer une clé)
//   -> colle tout le JSON sur une seule ligne comme valeur de cette variable.

// NOTE : cette mémoire cloud est OPTIONNELLE. La mémoire d'éléphant principale vit sur le
// téléphone (SQLite) et est envoyée avec chaque question. Ce module ne s'active que si
// FIREBASE_SERVICE_ACCOUNT_JSON et EMBEDDING_PROVIDER_URL sont définis.

let admin = null;
let db = null;
let FACTS = null;
let CONVERSATIONS = null;
let enabled = false;

try {
  if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    admin = require('firebase-admin');
    if (!admin.apps.length) {
      admin.initializeApp({
        credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON)),
      });
    }
    db = admin.firestore();
    FACTS = db.collection('jarvis_facts');
    CONVERSATIONS = db.collection('jarvis_conversations');
    enabled = true;
  }
} catch (err) {
  console.error('Firestore indisponible, mémoire cloud désactivée:', err.message);
}

// --- 1) Faits simples (clé/valeur) --------------------------------------

/** Enregistre ou met à jour un fait ("cle" -> "valeur") pour un utilisateur. */
async function saveFact(userId, key, value) {
  await FACTS.doc(`${userId}_${key}`).set(
    { userId, key, value, updatedAt: admin.firestore.FieldValue.serverTimestamp() },
    { merge: true }
  );
}

/** Récupère tous les faits connus sur un utilisateur, sous forme d'objet. */
async function getFacts(userId) {
  const snap = await FACTS.where('userId', '==', userId).get();
  const facts = {};
  snap.forEach((doc) => {
    const d = doc.data();
    facts[d.key] = d.value;
  });
  return facts;
}

// --- 2) Mémoire sémantique (historique + embeddings) --------------------

/**
 * Calcule l'embedding d'un texte. Branche ici le fournisseur de ton choix
 * (OpenAI text-embedding-3-small, Cohere, ou un modèle local gratuit via
 * @xenova/transformers). Exemple avec une API HTTP compatible OpenAI :
 */
async function getEmbedding(text) {
  const res = await fetch(process.env.EMBEDDING_PROVIDER_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${process.env.EMBEDDING_PROVIDER_KEY}`,
    },
    body: JSON.stringify({ input: text, model: 'text-embedding-3-small' }),
  });
  const json = await res.json();
  return json.data[0].embedding; // adapte selon la forme de réponse du fournisseur
}

/** Sauvegarde un tour de conversation (utilisateur ou assistant) avec son embedding. */
async function saveTurn(userId, role, content) {
  const embedding = await getEmbedding(content);
  await CONVERSATIONS.add({
    userId,
    role,
    content,
    embedding: admin.firestore.FieldValue.vector(embedding),
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}

/**
 * Renvoie les `limit` souvenirs les plus proches sémantiquement de `queryText`.
 * Nécessite un index vectoriel Firestore sur (userId, embedding) — voir l'étape
 * suivante (commande gcloud) avant que cette fonction ne marche.
 */
async function recallRelevant(userId, queryText, limit = 5) {
  const embedding = await getEmbedding(queryText);
  const snap = await CONVERSATIONS.where('userId', '==', userId)
    .findNearest('embedding', embedding, { limit, distanceMeasure: 'COSINE' })
    .get();
  return snap.docs.map((d) => d.data());
}

// --- 3) Contexte prêt à injecter dans le prompt Groq ---------------------

/**
 * Assemble faits + souvenirs pertinents en un bloc de texte à mettre
 * dans le prompt système envoyé à Groq, avant la question de l'utilisateur.
 */
async function buildMemoryContext(userId, userText) {
  const [facts, memories] = await Promise.all([
    getFacts(userId),
    recallRelevant(userId, userText).catch(() => []), // ne bloque pas si ça échoue
  ]);

  const factLines = Object.entries(facts)
    .map(([k, v]) => `- ${k}: ${v}`)
    .join('\n');

  const memoryLines = memories.map((m) => `- (${m.role}) ${m.content}`).join('\n');

  return [
    factLines && `Faits connus sur l'utilisateur:\n${factLines}`,
    memoryLines && `Souvenirs pertinents de conversations passées:\n${memoryLines}`,
  ]
    .filter(Boolean)
    .join('\n\n');
}

module.exports = { enabled, saveFact, getFacts, saveTurn, recallRelevant, buildMemoryContext };


// --- Exemple d'intégration dans ta route /ask (à adapter) -----------------
//
// const { buildMemoryContext, saveTurn } = require('./jarvisMemory');
//
// app.post('/ask', async (req, res) => {
//   const { text, userId } = req.body; // userId : identifiant stable envoyé par l'app Android
//
//   const memoryContext = await buildMemoryContext(userId, text);
//
//   const groqResponse = await callGroq({
//     system: `${basePrompt}\n\n${memoryContext}`,
//     user: text,
//   });
//
//   saveTurn(userId, 'user', text).catch(console.error);
//   saveTurn(userId, 'assistant', groqResponse.text).catch(console.error);
//
//   res.json(groqResponse);
// });
