'use strict';
/**
 * ATELIER CODE DE JARVIS
 * « Code-moi un script Python… » -> le LLM écrit un projet complet (fichiers) -> ZIP prêt pour GitHub.
 *
 * Routes (protégées par x-app-secret) :
 *   POST /code/start          { request, language?, name?, ...clés } -> { id }
 *   GET  /code/status/:id     -> { status, progress, error, name, files, log }
 *   GET  /code/:id/project.zip
 */

const crypto = require('crypto');
const { _internals } = require('./ebook');
const { zipStore } = _internals;

const MAX_JOBS = 30;
const JOB_TTL_MS = 3 * 60 * 60 * 1000;
const MAX_RUNNING = 3;
const MAX_FILES = 15;
const MAX_TOTAL_CHARS = 250000;

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
      const out = typeof msg.content === 'string' ? msg.content : '';
      return extractJson(out);
    } catch (e) {
      lastErr = e;
      const text = e.userMessage || e.message || '';
      if (/401|403|invalid api key|unauthor|incorrect api key/i.test(text)) break;
      if (i < retries) await sleep(/429|rate|limit|quota|overload|tokens/i.test(text) ? 8000 * (i + 1) : 2000 * (i + 1));
    }
  }
  throw lastErr;
}

const slugify = (s) =>
  String(s || '')
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
    .slice(0, 48) || 'projet';

function safePath(p) {
  const clean = String(p || '').replace(/\\/g, '/').replace(/^\/+/, '').trim();
  if (!clean || clean.includes('..') || clean.length > 150) return null;
  if (!/^[A-Za-z0-9._\-\/]+$/.test(clean)) return null;
  return clean;
}

async function generateProject(deps, llm, request, language, name, log) {
  const system =
    "Tu es un développeur logiciel senior. Tu écris du code complet, propre, fonctionnel et prêt à être mis sur GitHub. " +
    "Tu réponds UNIQUEMENT par un objet JSON valide, sans texte autour, sans markdown.";
  const user =
    `Demande : « ${request} »\n` +
    `Langage demandé : ${language || 'choisis le plus adapté à la demande'}\n` +
    (name ? `Nom du projet souhaité : ${name}\n` : '') +
    `Crée un projet de ${MAX_FILES} fichiers au maximum (moins si possible). Règles :\n` +
    '- Code complet, sans « TODO » ni placeholders, avec les dépendances nécessaires déclarées (requirements.txt, package.json, build.gradle…).\n' +
    '- Commentaires utiles en français. Pas de secrets ni de clés API en dur (utiliser des variables d\'environnement).\n' +
    '- Inclure un README.md en français : description, installation, lancement.\n' +
    '- Inclure un .gitignore adapté au langage.\n' +
    '- Chemins relatifs simples (ex: src/main.py), sans ".." ni espaces.\n' +
    'Schéma exact :\n' +
    '{ "projectName": "nom-court-en-minuscules-avec-tirets", "summary": "une phrase", ' +
    '"files": [ { "path": "README.md", "content": "contenu complet du fichier" } ] }';
  const j = await askJson(deps, llm, system, user);
  log('Projet conçu, vérification des fichiers…');

  const files = [];
  let total = 0;
  for (const f of Array.isArray(j.files) ? j.files : []) {
    const path = safePath(f && f.path);
    const content = typeof (f && f.content) === 'string' ? f.content : null;
    if (!path || content === null) continue;
    total += content.length;
    if (total > MAX_TOTAL_CHARS) break;
    if (!files.some((x) => x.path === path)) files.push({ path, content });
    if (files.length >= MAX_FILES) break;
  }
  if (!files.length) throw new Error("Le projet généré ne contient aucun fichier exploitable.");

  if (!files.some((f) => /^readme/i.test(f.path))) {
    files.unshift({ path: 'README.md', content: `# ${str(j.projectName, 80) || name || 'Projet'}\n\n${str(j.summary, 300)}\n` });
  }
  if (!files.some((f) => f.path === '.gitignore')) {
    files.push({ path: '.gitignore', content: 'node_modules/\n__pycache__/\n*.pyc\n.env\n*.log\n.DS_Store\nbuild/\ndist/\n.idea/\n.vscode/\n' });
  }
  return {
    projectName: slugify(name || j.projectName || request),
    summary: str(j.summary, 300),
    files,
  };
}

/** Site ou application web : UN fichier index.html autonome, affichable tout de suite dans l'aperçu. */
async function generateWeb(deps, llm, request, name, log) {
  const system =
    "Tu es un développeur web senior et designer UI. Tu écris un site ou une application web complète dans UN SEUL fichier HTML autonome " +
    '(HTML, CSS et JavaScript à l\'intérieur). Tu réponds UNIQUEMENT par un objet JSON valide, sans markdown.';
  const user =
    `Demande : « ${request} »\n` +
    (name ? `Nom : ${name}\n` : '') +
    'Règles : un seul fichier index.html ; CSS et JavaScript inline ; aucune dépendance externe ni CDN ; ' +
    'design soigné et moderne, responsive, utilisable au pouce sur téléphone ; fonctionnalités réellement opérationnelles, sans TODO ; ' +
    'textes en français ; pas de secret ni de clé en dur.\n' +
    'Schéma exact : { "projectName": "nom-court-en-minuscules", "summary": "une phrase", ' +
    '"files": [ { "path": "index.html", "content": "HTML complet" } ] }';
  const j = await askJson(deps, llm, system, user);
  const page = (Array.isArray(j.files) ? j.files : []).find((f) => f && f.path === 'index.html' && typeof f.content === 'string');
  if (!page || page.content.length < 200) throw new Error('La page générée est vide ou incomplète.');
  log('Page générée, ajout du README…');
  const projectName = slugify(name || j.projectName || request);
  return {
    projectName,
    summary: str(j.summary, 300),
    files: [
      { path: 'index.html', content: page.content },
      { path: 'README.md', content: `# ${projectName}\n\nOuvre index.html dans ton navigateur, ou publie-le gratuitement avec GitHub Pages.\n` },
      { path: '.gitignore', content: '.DS_Store\n' },
    ],
  };
}

function buildZip(project) {
  const root = project.projectName;
  const entries = project.files.map((f) => ({ name: `${root}/${f.path}`, data: Buffer.from(f.content, 'utf8') }));
  return zipStore(entries);
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
    const request = str(body.request, 2000);
    if (!request) { const e = new Error('Dis-moi ce que je dois coder.'); e.status = 400; throw e; }
    const running = [...jobs.values()].filter((j) => j.status === 'running').length;
    if (running >= MAX_RUNNING) { const e = new Error('Trois projets sont déjà en cours. Patiente un instant.'); e.status = 429; throw e; }
    let llm;
    try { llm = deps.resolveLLM(body); } catch (err) { err.status = 400; throw err; }
    if (!llm.apiKey) { const e = new Error("Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)."); e.status = 400; throw e; }

    const id = crypto.randomBytes(8).toString('hex');
    const job = {
      id, createdAt: Date.now(), finishedAt: 0, status: 'running', progress: 5, error: '',
      request, language: str(body.language, 40), mode: body.mode === 'web' ? 'web' : 'files', html: null, name: '', summary: '', files: [], zip: null,
      log: [], logSeq: 0,
    };
    const log = (msg) => { job.logSeq += 1; job.log.push({ n: job.logSeq, msg }); if (job.log.length > 100) job.log.shift(); };
    jobs.set(id, job);

    (async () => {
      try {
        log('Analyse de la demande…');
        job.progress = 15;
        const onLog = (m) => { job.progress = 60; log(m); };
        const project = job.mode === 'web'
          ? await generateWeb(deps, llm, request, str(body.name, 60), onLog)
          : await generateProject(deps, llm, request, job.language, str(body.name, 60), onLog);
        job.name = project.projectName;
        job.summary = project.summary;
        job.files = project.files.map((f) => f.path);
        job.html = (project.files.find((f) => f.path === 'index.html') || {}).content || null;
        job.progress = 85;
        log(`${project.files.length} fichiers écrits`);
        job.zip = buildZip(project);
        job.progress = 100;
        log('ZIP prêt pour GitHub');
        job.status = 'done';
      } catch (e) {
        job.status = 'error';
        job.error = e.userMessage || e.message || 'Erreur inconnue';
        log(`Erreur : ${job.error.slice(0, 160)}`);
        console.error('code', id, e);
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
    if (!j) { res.status(404).json({ error: 'Projet introuvable (le serveur a peut-être redémarré). Relance la demande.' }); return null; }
    return j;
  };

  app.post('/code/start', checkSecret, (req, res) => {
    try {
      const job = engine.start(req.body || {});
      res.json({ id: job.id });
    } catch (e) {
      res.status(e.status || 500).json({ error: e.userMessage || e.message || 'Erreur' });
    }
  });
  app.get('/code/status/:id', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    res.json({
      status: j.status, progress: j.progress, error: j.error, name: j.name, summary: j.summary,
      files: j.files, log: j.log, logSeq: j.logSeq, mode: j.mode, web: !!j.html,
      elapsed: Math.round(((j.finishedAt || Date.now()) - j.createdAt) / 1000),
    });
  });
  app.get('/code/:id/project.zip', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    if (!j.zip) return res.status(409).json({ error: "Le ZIP n'est pas encore prêt." });
    res.set({ 'Content-Type': 'application/zip', 'Content-Disposition': `attachment; filename="${j.name || 'projet'}.zip"` }).send(j.zip);
  });
  app.get('/code/:id/source', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    if (!j.html) return res.status(409).json({ error: "Pas d'aperçu pour ce projet." });
    res.type('text/plain; charset=utf-8').send(j.html);
  });
  return engine;
}

module.exports = { register, createEngine, _internals: { generateProject, generateWeb, buildZip, safePath } };
