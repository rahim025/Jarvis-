'use strict';
/**
 * ATELIER HISTOIRES DE JARVIS (BD en cases pour Facebook)
 * « Fais une histoire sur… » -> scénario en cases (répliques + scènes) -> illustrations -> PNG côté app.
 *
 * Routes (protégées par x-app-secret) :
 *   POST /story/start              { topic, pageName?, panels?, ...clés } -> { id }
 *   GET  /story/status/:id         -> { status, progress, title, panels:[{text,speaker,bubble,hasImage}], log }
 *   GET  /story/:id/panel/:n       -> image de la case n (1 à N)
 */

const crypto = require('crypto');

const MAX_JOBS = 30;
const JOB_TTL_MS = 3 * 60 * 60 * 1000;
const MAX_RUNNING = 2;
const MAX_PANELS = 8;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const str = (v, max = 400) => (typeof v === 'string' ? v.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').trim().slice(0, max) : '');

function extractJson(text) {
  const t = String(text).replace(/```json|```/gi, '');
  const a = t.indexOf('{');
  const b = t.lastIndexOf('}');
  if (a < 0 || b <= a) throw new Error('JSON introuvable dans la réponse.');
  return JSON.parse(t.slice(a, b + 1));
}

async function askJson(deps, llm, system, user, retries = 2) {
  let lastErr;
  for (let i = 0; i <= retries; i++) {
    try {
      const msg = await deps.callLLM([{ role: 'system', content: system }, { role: 'user', content: user }], llm, null);
      return extractJson(typeof msg.content === 'string' ? msg.content : '');
    } catch (e) {
      lastErr = e;
      const text = e.userMessage || e.message || '';
      if (/401|403|invalid api key|unauthor|incorrect api key/i.test(text)) break;
      if (i < retries) await sleep(/429|rate|limit|quota|overload|tokens/i.test(text) ? 8000 * (i + 1) : 2000 * (i + 1));
    }
  }
  throw lastErr;
}

async function planStory(deps, llm, topic, n) {
  const system =
    'Tu es scénariste de bandes dessinées courtes pour les réseaux sociaux. Tu écris des histoires en français, ' +
    'avec des répliques naturelles et une chute à la dernière case. Tu réponds UNIQUEMENT par un objet JSON valide, sans markdown.';
  const user =
    `Sujet de l'histoire : « ${topic} ».\n` +
    `Écris une histoire en EXACTEMENT ${n} cases. Schéma :\n` +
    '{ "title": "titre court", ' +
    '"characters": [ { "name": "prénom", "look": "description physique et vestimentaire EN ANGLAIS, précise, identique à chaque case" } ], ' +
    '"panels": [ { "scene": "description visuelle EN ANGLAIS : décor, action, expression, cadrage, SANS aucun texte", ' +
    '"speaker": "prénom de celui qui parle", "text": "réplique en français, 90 caractères maximum", "bubble": "top ou bottom" } ] }\n' +
    'Règles : répliques courtes ; pas de contenu explicite ni de violence graphique ; ' +
    'alterne bubble top et bottom pour l\'équilibre ; la dernière case contient la chute.';
  const j = await askJson(deps, llm, system, user);
  const panels = (Array.isArray(j.panels) ? j.panels : [])
    .map((p) => ({
      scene: str(p && p.scene, 500),
      speaker: str(p && p.speaker, 30),
      text: str(p && p.text, 120),
      bubble: p && p.bubble === 'bottom' ? 'bottom' : 'top',
    }))
    .filter((p) => p.scene && p.text)
    .slice(0, n);
  if (panels.length < Math.min(2, n)) throw new Error('Le scénario généré est incomplet.');
  const characters = (Array.isArray(j.characters) ? j.characters : [])
    .map((c) => ({ name: str(c && c.name, 30), look: str(c && c.look, 300) }))
    .filter((c) => c.look)
    .slice(0, 4);
  return { title: str(j.title, 80) || topic, characters, panels };
}

async function fetchImage(deps, prompt, seed) {
  const url = `https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=1024&height=1024&nologo=true&model=flux&seed=${seed}`;
  let lastErr;
  for (let i = 0; i < 3; i++) {
    try {
      const res = await deps.fetch(url, { signal: AbortSignal.timeout(150000) });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const type = (res.headers.get('content-type') || '').split(';')[0];
      if (!/^image\/(jpeg|png|webp)$/.test(type)) throw new Error('Type image inattendu : ' + type);
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length < 4000) throw new Error('Image trop petite');
      return { buf, type };
    } catch (e) {
      lastErr = e;
      await sleep(5000 * (i + 1));
    }
  }
  throw lastErr;
}

function createEngine(deps) {
  const jobs = new Map();

  const sweep = () => {
    const now = Date.now();
    for (const [id, j] of jobs) if (now - j.createdAt > JOB_TTL_MS) jobs.delete(id);
    while (jobs.size > MAX_JOBS) jobs.delete(jobs.keys().next().value);
  };

  function start(body) {
    sweep();
    const topic = str(body.topic, 400);
    if (!topic) { const e = new Error("Dis-moi le sujet de l'histoire."); e.status = 400; throw e; }
    const running = [...jobs.values()].filter((j) => j.status === 'running').length;
    if (running >= MAX_RUNNING) { const e = new Error('Deux histoires sont déjà en cours. Patiente un instant.'); e.status = 429; throw e; }
    let llm;
    try { llm = deps.resolveLLM(body); } catch (err) { err.status = 400; throw err; }
    if (!llm.apiKey) { const e = new Error("Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)."); e.status = 400; throw e; }

    const n = Math.min(MAX_PANELS, Math.max(2, parseInt(body.panels, 10) || 4));
    const id = crypto.randomBytes(8).toString('hex');
    const job = {
      id, createdAt: Date.now(), finishedAt: 0, status: 'running', progress: 5, error: '',
      title: '', panels: [], log: [], logSeq: 0,
    };
    const log = (msg) => { job.logSeq += 1; job.log.push({ n: job.logSeq, msg }); if (job.log.length > 100) job.log.shift(); };
    jobs.set(id, job);

    (async () => {
      try {
        log('Écriture du scénario…');
        const story = await planStory(deps, llm, topic, n);
        job.title = story.title;
        job.panels = story.panels.map((p) => ({ ...p, hasImage: false, image: null, type: 'image/jpeg' }));
        log(`Scénario : « ${story.title} », ${job.panels.length} cases`);
        job.progress = 15;

        const look = story.characters.map((c) => `${c.name}: ${c.look}`).join('. ');
        const seed = parseInt(id.slice(0, 6), 16) % 999999;
        const step = 80 / job.panels.length;
        for (let i = 0; i < job.panels.length; i++) {
          const p = job.panels[i];
          log(`Dessin de la case ${i + 1}…`);
          const prompt = `${p.scene}. ${look}. Semi-realistic digital painting, cinematic lighting, square composition, sharp focus, no text, no letters, no speech bubbles, no watermark`;
          try {
            const img = await fetchImage(deps, prompt, seed);
            p.image = img.buf;
            p.type = img.type;
            p.hasImage = true;
          } catch (e) {
            log(`Case ${i + 1} sans image (${String(e.message).slice(0, 60)})`);
          }
          job.progress = Math.round(15 + step * (i + 1));
        }
        job.progress = 100;
        log('Histoire prête');
        job.status = 'done';
      } catch (e) {
        job.status = 'error';
        job.error = e.userMessage || e.message || 'Erreur inconnue';
        log(`Erreur : ${job.error.slice(0, 160)}`);
        console.error('story', id, e);
      } finally {
        job.finishedAt = Date.now();
      }
    })();
    return job;
  }

  return { jobs, start };
}

function register(app, checkSecret, deps) {
  const engine = createEngine(deps);
  const find = (req, res) => {
    const j = engine.jobs.get(req.params.id);
    if (!j) { res.status(404).json({ error: 'Histoire introuvable (le serveur a peut-être redémarré). Relance la demande.' }); return null; }
    return j;
  };

  app.post('/story/start', checkSecret, (req, res) => {
    try {
      const job = engine.start(req.body || {});
      res.json({ id: job.id });
    } catch (e) {
      res.status(e.status || 500).json({ error: e.userMessage || e.message || 'Erreur' });
    }
  });
  app.get('/story/status/:id', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    res.json({
      status: j.status, progress: j.progress, error: j.error, title: j.title,
      panels: j.panels.map(({ text, speaker, bubble, hasImage }) => ({ text, speaker, bubble, hasImage })),
      log: j.log, logSeq: j.logSeq,
    });
  });
  app.get('/story/:id/panel/:n', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    const p = j.panels[(parseInt(req.params.n, 10) || 0) - 1];
    if (!p || !p.image) return res.status(404).json({ error: 'Case introuvable ou sans image.' });
    res.type(p.type).send(p.image);
  });
  return engine;
}

module.exports = { register, createEngine, _internals: { planStory } };
