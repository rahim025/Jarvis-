// Extrait à intégrer dans server.js — remplace ta route /ask existante par
// une version qui lit userId (avec secours si absent) et branche la mémoire.

const { buildMemoryContext, saveTurn } = require('./jarvisMemory');

const DEFAULT_USER_ID = 'rahim'; // secours si l'app ne l'envoie pas (ancienne version, etc.)

app.post('/ask', async (req, res) => {
  const { text } = req.body;
  const userId = req.body.userId || DEFAULT_USER_ID;

  const memoryContext = await buildMemoryContext(userId, text);

  const groqResponse = await callGroq({
    system: `${basePrompt}\n\n${memoryContext}`, // basePrompt = ton prompt système actuel
    user: text,
  });

  saveTurn(userId, 'user', text).catch(console.error);
  saveTurn(userId, 'assistant', groqResponse.text).catch(console.error);

  res.json(groqResponse);
});
