'use strict';
/**
 * ATELIER E-BOOK DE JARVIS
 * ------------------------
 * « Crée un e-book sur X » -> le téléphone lance un travail ici, puis interroge /ebook/status/:id
 * pour afficher la progression étape par étape (écran style JARVIS dans l'app).
 *
 * Pipeline : analyse du sujet -> plan -> un chapitre à la fois -> visuel -> couverture -> EPUB -> contrôle.
 *
 * Gratuit de bout en bout : texte via ton « cerveau » habituel (Groq, OpenRouter…), image via
 * Gemini si une clé existe, sinon Pollinations. Aucune dépendance npm en plus (le ZIP/EPUB est écrit à la main).
 *
 * Routes (toutes protégées par x-app-secret comme le reste du backend) :
 *   POST /ebook/start          { topic, author?, chapters?, style?, photo? , llm?, geminiKey? } -> { id }
 *   GET  /ebook/status/:id     ?since=N  -> étapes, progression, nouvelles lignes de journal
 *   POST /ebook/cancel/:id
 *   GET  /ebook/:id/cover.svg
 *   GET  /ebook/:id/book.epub
 */

const crypto = require('crypto');

const MAX_JOBS = 30;
const JOB_TTL_MS = 3 * 60 * 60 * 1000;
const MAX_RUNNING = 2;
const IMAGE_MODEL = process.env.EBOOK_IMAGE_MODEL || 'gemini-3.1-flash-image';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const clamp = (n, a, b) => Math.max(a, Math.min(b, n));
const str = (v, max = 400) => (typeof v === 'string' ? v.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').trim().slice(0, max) : '');
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const upper = (s) => String(s).toLocaleUpperCase('fr-FR');

class Cancelled extends Error {}

// ─────────────────────────────────────────────────────────────────────────────
// THÈMES DE COUVERTURE (le LLM choisit selon le sujet ; « style » dans la commande peut forcer)
// ─────────────────────────────────────────────────────────────────────────────
const THEMES = {
  or_nuit: { bg1: '#07090e', bg2: '#141922', accent: '#f6c026', accent2: '#ffe08a', text: '#ffffff', muted: '#c8cdd8', ink: '#15130a', light: false },
  bordeaux_or: { bg1: '#1f0a10', bg2: '#4a1624', accent: '#e9c47c', accent2: '#fbe6b0', text: '#fff8ef', muted: '#e5cfc4', ink: '#2a1208', light: false },
  emeraude_or: { bg1: '#051a15', bg2: '#0e3b2f', accent: '#f0d27a', accent2: '#fff0b8', text: '#f7fff9', muted: '#c5ddd2', ink: '#13200f', light: false },
  graphite_corail: { bg1: '#15181e', bg2: '#2b313c', accent: '#ff7a59', accent2: '#ffb199', text: '#ffffff', muted: '#d0d4dc', ink: '#1d0d08', light: false },
  ivoire_encre: { bg1: '#f6f1e7', bg2: '#e6dcc8', accent: '#9c2f1d', accent2: '#c9573f', text: '#17120d', muted: '#4b4036', ink: '#fff7ea', light: true },
};
const THEME_ALIASES = [
  [/or|gold|jaune|dor/, 'or_nuit'], [/bordeaux|vin|rouge/, 'bordeaux_or'], [/vert|emeraude|émeraude|nature/, 'emeraude_or'],
  [/corail|orange|graphite/, 'graphite_corail'], [/clair|ivoire|creme|crème|blanc|sobre/, 'ivoire_encre'],
];
function pickTheme(...candidates) {
  for (const c of candidates) {
    const v = str(c, 40).toLowerCase();
    if (!v) continue;
    if (THEMES[v]) return v;
    const hit = THEME_ALIASES.find(([re]) => re.test(v));
    if (hit) return hit[1];
  }
  return 'or_nuit';
}

// ─────────────────────────────────────────────────────────────────────────────
// APPELS IA (avec nouvelles tentatives : les quotas gratuits sont parfois capricieux)
// ─────────────────────────────────────────────────────────────────────────────
async function llmAsk(deps, llm, system, user, retries = 3) {
  let lastErr;
  for (let i = 0; i <= retries; i++) {
    try {
      const msg = await deps.callLLM([{ role: 'system', content: system }, { role: 'user', content: user }], llm, null);
      const out = typeof msg.content === 'string' ? msg.content.trim() : '';
      if (!out) throw new Error('Réponse vide du fournisseur IA.');
      return out;
    } catch (e) {
      lastErr = e;
      const text = e.userMessage || e.message || '';
      if (/401|403|invalid api key|unauthor|incorrect api key/i.test(text)) break;
      if (i === retries) break;
      await sleep(/429|rate|limit|quota|overload|tokens/i.test(text) ? 9000 * (i + 1) : 2500 * (i + 1));
    }
  }
  throw lastErr;
}

function extractJson(text) {
  const t = String(text).replace(/```json|```/gi, '');
  const a = t.indexOf('{');
  const b = t.lastIndexOf('}');
  if (a < 0 || b <= a) throw new Error('JSON introuvable dans la réponse.');
  return JSON.parse(t.slice(a, b + 1));
}

async function llmJson(deps, llm, system, user) {
  let raw = await llmAsk(deps, llm, system, user);
  try {
    return extractJson(raw);
  } catch (_) {
    raw = await llmAsk(deps, llm, system, user + '\n\nRAPPEL : réponds UNIQUEMENT par un objet JSON valide, rien d\'autre.');
    return extractJson(raw);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANALYSE + PLAN
// ─────────────────────────────────────────────────────────────────────────────
const ICONS = ['head', 'search', 'shield', 'heart', 'book', 'bolt', 'chart', 'star'];

async function analyseTopic(deps, llm, topic, styleHint) {
  const system =
    "Tu es directeur éditorial et concepteur de couvertures d'e-books vendus en ligne. " +
    'Tu réponds UNIQUEMENT par un objet JSON valide, sans texte autour, sans markdown.';
  const user =
    `Sujet de l'e-book : « ${topic} ».\n` +
    'Conçois son identité éditoriale et le texte de sa couverture, en français, ton professionnel et rassurant. Schéma exact :\n' +
    '{\n' +
    '  "bookTitle": "titre complet de l\'ouvrage",\n' +
    '  "bookSubtitle": "sous-titre complet (une phrase)",\n' +
    '  "titleLead": "1 à 2 petits mots en haut du titre, ex. LA, LES, L\'ART DE — ou chaîne vide",\n' +
    '  "titleMain": "le mot fort du titre, 1 à 2 mots, 14 caractères maximum de préférence",\n' +
    '  "kicker": "bandeau du haut, 2 à 3 mots, ex. GUIDE COMPLET",\n' +
    '  "keywords": ["3 mots d\'action ou concepts courts"],\n' +
    '  "hook": "phrase d\'accroche, 70 caractères maximum",\n' +
    '  "bullets": [{"label": "28 caractères maximum", "icon": "head|search|shield|heart|book|bolt|chart|star"}],  // exactement 4\n' +
    '  "quote": "phrase inspirante courte, 60 caractères maximum",\n' +
    '  "closing": {"line": "début de slogan en majuscules", "accent": "dernier mot fort"},\n' +
    '  "theme": "or_nuit | bordeaux_or | emeraude_or | graphite_corail | ivoire_encre",\n' +
    '  "photoPrompt": "prompt EN ANGLAIS pour une photo de couverture réaliste et premium en rapport avec le sujet, sans texte",\n' +
    '  "photoQuery": "requête de recherche d\'images en français",\n' +
    '  "sensitive": "health | legal | finance | none"\n' +
    '}\n' +
    (styleHint ? `Préférence de style demandée par l'utilisateur : « ${styleHint} » (choisis le thème en conséquence).\n` : '') +
    'Choisis le thème qui convient le mieux au sujet ; évite le bleu. Pas de superlatifs creux, pas de promesses de résultats garantis.';
  const j = await llmJson(deps, llm, system, user);

  const bullets = (Array.isArray(j.bullets) ? j.bullets : []).slice(0, 4).map((b) => ({
    label: str(b && b.label, 40),
    icon: ICONS.includes(b && b.icon) ? b.icon : 'star',
  })).filter((b) => b.label);
  const fallbackIcons = ['book', 'search', 'shield', 'star'];
  while (bullets.length < 4) bullets.push({ label: ['Comprendre les bases', 'Les points clés', 'Les bonnes pratiques', 'Passer à l\'action'][bullets.length], icon: fallbackIcons[bullets.length] });

  let titleMain = upper(str(j.titleMain, 28)) || upper(str(topic, 14));
  const keywords = (Array.isArray(j.keywords) ? j.keywords : []).map((k) => str(k, 20)).filter(Boolean).slice(0, 3);
  return {
    bookTitle: str(j.bookTitle, 120) || topic,
    bookSubtitle: str(j.bookSubtitle, 160),
    titleLead: upper(str(j.titleLead, 18)),
    titleMain,
    kicker: upper(str(j.kicker, 26)) || 'GUIDE COMPLET',
    keywords: keywords.length ? keywords : ['Comprendre', 'Agir'],
    hook: str(j.hook, 90),
    bullets,
    quote: str(j.quote, 80),
    closing: { line: upper(str(j.closing && j.closing.line, 60)), accent: upper(str(j.closing && j.closing.accent, 20)) },
    theme: pickTheme(styleHint, j.theme),
    photoPrompt: str(j.photoPrompt, 500) || `professional editorial photograph about ${topic}`,
    photoQuery: str(j.photoQuery, 80) || topic,
    sensitive: ['health', 'legal', 'finance'].includes(j.sensitive) ? j.sensitive : 'none',
  };
}

async function planBook(deps, llm, topic, meta, n) {
  const system = "Tu es un auteur et éditeur chevronné d'ouvrages de vulgarisation. Tu réponds UNIQUEMENT par un objet JSON valide.";
  const user =
    `Ouvrage : « ${meta.bookTitle} » — ${meta.bookSubtitle}\nSujet : ${topic}\n` +
    `Propose un plan de EXACTEMENT ${n} chapitres, progressif (comprendre -> approfondir -> agir), le dernier étant une conclusion avec un plan d'action concret. Schéma :\n` +
    '{ "introduction": "2 phrases qui présentent l\'ouvrage au lecteur", "chapters": [ { "title": "titre du chapitre (sans numéro)", "summary": "ce que couvre le chapitre, 1 phrase" } ] }';
  const j = await llmJson(deps, llm, system, user);
  let chapters = (Array.isArray(j.chapters) ? j.chapters : []).map((c) => ({ title: str(c && c.title, 90), summary: str(c && c.summary, 220) })).filter((c) => c.title);
  if (chapters.length < Math.min(3, n)) throw new Error('Le plan généré est incomplet.');
  chapters = chapters.slice(0, n);
  while (chapters.length < n) chapters.push({ title: `Aller plus loin (${chapters.length + 1})`, summary: 'Approfondissement.' });
  return { introduction: str(j.introduction, 500), chapters };
}

async function writeChapter(deps, llm, meta, plan, idx, targetWords) {
  const total = plan.chapters.length;
  const ch = plan.chapters[idx];
  const outline = plan.chapters.map((c, i) => `${i + 1}. ${c.title}`).join('\n');
  const system =
    "Tu es un auteur professionnel francophone. Tu rédiges un chapitre d'e-book clair, structuré, précis et agréable à lire. " +
    "Orthographe et grammaire irréprochables. Pas d'inventions de chiffres ou d'études précises : reste exact et nuancé.";
  const user =
    `Ouvrage : « ${meta.bookTitle} » — ${meta.bookSubtitle}\nPlan complet :\n${outline}\n\n` +
    `Rédige le chapitre ${idx + 1}/${total} : « ${ch.title} » (${ch.summary}).\n` +
    `Longueur : environ ${targetWords} mots. Ne répète pas ce que les autres chapitres couvrent.\n` +
    'Format de sortie STRICT : paragraphes séparés par une ligne vide ; sous-titres sur une ligne commençant par « ## » (2 à 4 sous-titres) ; ' +
    'listes avec « - » en début de ligne quand c\'est utile. Pas de titre de chapitre en tête, pas de markdown autre que ça, pas de conclusion générique du type « en résumé ».' +
    (idx === total - 1 ? ' Ce dernier chapitre se termine par un plan d\'action en étapes concrètes.' : '');
  let text = await llmAsk(deps, llm, system, user);
  text = text.replace(/^\s*#\s+.*\n+/, '').trim();
  return text;
}

// ─────────────────────────────────────────────────────────────────────────────
// TEXTE -> XHTML
// ─────────────────────────────────────────────────────────────────────────────
function inline(s) {
  return esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/(^|[^*])\*(?!\s)([^*]+?)\*(?!\*)/g, '$1<em>$2</em>');
}
function textToXhtml(txt) {
  const blocks = String(txt).replace(/\r/g, '').split(/\n{2,}/).map((b) => b.trim()).filter(Boolean);
  const out = [];
  for (const b of blocks) {
    const lines = b.split('\n').map((l) => l.trim()).filter(Boolean);
    if (/^#{1,4}\s+/.test(lines[0])) {
      out.push(`<h2>${inline(lines[0].replace(/^#{1,4}\s+/, ''))}</h2>`);
      if (lines.length > 1) out.push(`<p>${inline(lines.slice(1).join(' '))}</p>`);
    } else if (lines.every((l) => /^[-*•]\s+/.test(l))) {
      out.push('<ul>' + lines.map((l) => `<li>${inline(l.replace(/^[-*•]\s+/, ''))}</li>`).join('') + '</ul>');
    } else if (lines.every((l) => /^\d+[.)]\s+/.test(l))) {
      out.push('<ol>' + lines.map((l) => `<li>${inline(l.replace(/^\d+[.)]\s+/, ''))}</li>`).join('') + '</ol>');
    } else {
      out.push(`<p>${inline(lines.join(' '))}</p>`);
    }
  }
  return out.join('\n');
}
const countWords = (t) => (String(t).match(/\S+/g) || []).length;

// ─────────────────────────────────────────────────────────────────────────────
// COUVERTURE — composition SVG 1024 x 1536 (le texte est dessiné par le code, jamais par l'IA :
// les IA d'image écrivent mal, le code est net et sans faute)
// ─────────────────────────────────────────────────────────────────────────────
function wrapText(text, maxChars, maxLines) {
  const words = String(text).split(/\s+/).filter(Boolean);
  const lines = [];
  let cur = '';
  for (const w of words) {
    if ((cur + ' ' + w).trim().length > maxChars && cur) { lines.push(cur); cur = w; } else cur = (cur + ' ' + w).trim();
  }
  if (cur) lines.push(cur);
  if (lines.length > maxLines) {
    const kept = lines.slice(0, maxLines);
    kept[maxLines - 1] = kept[maxLines - 1].replace(/[\s,.;:]+$/, '') + '…';
    return kept;
  }
  return lines;
}

function iconGlyph(name, c) {
  const s = `fill="none" stroke="${c}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"`;
  const f = `fill="${c}"`;
  switch (name) {
    case 'head': return `<g ${f}><circle cx="0" cy="-8" r="11"/><path d="M-20 22 C-20 6 -10 4 0 4 C10 4 20 6 20 22 Z"/></g>`;
    case 'search': return `<g ${s}><circle cx="-4" cy="-4" r="13"/><path d="M6 6 L19 19"/></g>`;
    case 'shield': return `<g ${s}><path d="M0 -23 L18 -15 V3 C18 14 8 21 0 25 C-8 21 -18 14 -18 3 V-15 Z"/><path d="M0 -7 V11 M-9 2 H9"/></g>`;
    case 'heart': return `<g ${s}><path d="M0 20 C-26 2 -18 -20 -6 -14 C-2 -12 0 -8 0 -8 C0 -8 2 -12 6 -14 C18 -20 26 2 0 20 Z"/></g>`;
    case 'book': return `<g ${s}><path d="M-20 -16 H-2 Q0 -16 0 -14 V18 Q0 16 -2 16 H-20 Z M20 -16 H2 Q0 -16 0 -14 V18 Q0 16 2 16 H20 Z"/></g>`;
    case 'bolt': return `<g ${f}><path d="M4 -24 L-14 4 H-2 L-6 24 L14 -6 H2 Z"/></g>`;
    case 'chart': return `<g ${f}><rect x="-20" y="2" width="9" height="20" rx="2"/><rect x="-4" y="-10" width="9" height="32" rx="2"/><rect x="12" y="-22" width="9" height="44" rx="2"/></g>`;
    default: return `<g ${f}><path d="M0 -24 L7 -8 L24 -7 L11 4 L15 21 L0 12 L-15 21 L-11 4 L-24 -7 L-7 -8 Z"/></g>`;
  }
}

function fallbackArt(t) {
  let rays = '';
  for (let i = 0; i < 28; i++) {
    const a = Math.PI * (i / 27);
    const x2 = 512 + Math.cos(Math.PI + a) * 700;
    const y2 = 960 + Math.sin(Math.PI + a) * 700;
    rays += `<line x1="512" y1="960" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}" stroke="${t.accent}" stroke-opacity="0.10" stroke-width="2"/>`;
  }
  const rings = [120, 220, 330, 450, 580].map((r, i) => `<circle cx="512" cy="960" r="${r}" fill="none" stroke="${t.accent}" stroke-opacity="${(0.34 - i * 0.05).toFixed(2)}" stroke-width="${3 - (i > 2 ? 1 : 0)}"/>`).join('');
  return `<g>${rays}${rings}<circle cx="512" cy="960" r="84" fill="${t.bg1}" stroke="${t.accent}" stroke-width="4"/><g transform="translate(512 960) scale(2.3)">${iconGlyph('book', t.accent)}</g></g>`;
}

// Largeur estimée d'un texte (police la plus large possible) ; si ça déborde, on force la largeur avec
// textLength : le rendu est identique quelle que soit la police du téléphone.
function fit(text, x, y, fs, avail, extra = '', anchor = 'start', k = 0.56) {
  const w = String(text).length * fs * k;
  const tl = w > avail ? ` textLength="${Math.round(avail)}" lengthAdjust="spacingAndGlyphs"` : '';
  return `<text x="${x}" y="${y}" font-size="${fs}" text-anchor="${anchor}"${tl} ${extra}>${esc(text)}</text>`;
}

function buildCoverSvg({ meta, author, photo }) {
  const t = THEMES[meta.theme] || THEMES.or_nuit;
  const W = 1024, H = 1536;
  const nMain = Math.max(1, meta.titleMain.length);
  const hasLead = !!meta.titleLead;
  const fsMain = Math.round(clamp(880 / (nMain * 0.5), 120, 250));
  const mainWidth = Math.min(880, Math.round(nMain * fsMain * 0.5));
  const cap = fsMain * 0.72;
  const mainY = hasLead ? 400 : 345;
  const leadN = Math.max(1, meta.titleLead.length);
  const fsLead = Math.round(clamp(560 / (leadN * 0.5), 70, 130));
  const leadW = Math.min(560, Math.round(leadN * fsLead * 0.7));
  const font = "'Impact','Haettenschweiler','Arial Black','Roboto Black','sans-serif-condensed',sans-serif";
  const cond = "'sans-serif-condensed','Roboto Condensed','Arial Narrow',Arial,sans-serif";
  const brush = "'Brush Script MT','Segoe Script','Dancing Script',cursive";

  const kw = meta.keywords.map((k) => esc(k)).join(`<tspan fill="${t.accent}">  \u2022  </tspan>`);
  const kwLen = meta.keywords.join('').length + meta.keywords.length * 5;
  const kwAttrs = kwLen * 43 * 0.52 > 900 ? ` textLength="900" lengthAdjust="spacingAndGlyphs"` : '';
  const subY = mainY + 62;
  const hookLines = wrapText(meta.hook, 64, 2);

  // Bandeau du haut : largeur du texte fixée, filets de part et d'autre
  const kickN = meta.kicker.length;
  const kickW = Math.min(520, kickN * 30);
  const kickCx = 470;
  const kx1 = kickCx - kickW / 2, kx2 = kickCx + kickW / 2;
  const kickRules =
    (kx1 - 130 > 24 ? `<line x1="${kx1 - 130}" y1="57" x2="${kx1 - 22}" y2="57" stroke="${t.accent}" stroke-width="2"/>` : '') +
    (kx2 + 22 < 800 ? `<line x1="${kx2 + 22}" y1="57" x2="${Math.min(kx2 + 130, 812)}" y2="57" stroke="${t.accent}" stroke-width="2"/>` : '');

  // Pictogrammes (colonne de droite) : une seule taille de police pour les 4, calculée sur la ligne la plus longue
  const bulletY = [600, 734, 868, 1002];
  const bLines = meta.bullets.map((b) => wrapText(b.label, 15, 4));
  const bMax = Math.max(...bLines.flat().map((l) => l.length), 1);
  const bFs = Math.round(clamp(152 / (bMax * 0.52), 18, 26));
  const bullets = meta.bullets.map((b, i) => {
    const y = bulletY[i];
    const lines = bLines[i];
    const lh = Math.round(bFs * 1.12);
    const top = y - ((lines.length - 1) * lh) / 2 + bFs * 0.32;
    return `<g>
      <circle cx="806" cy="${y}" r="39" fill="${t.bg1}" fill-opacity="0.6" stroke="${t.accent}" stroke-width="4"/>
      <g transform="translate(806 ${y}) scale(0.92)">${iconGlyph(b.icon, t.accent)}</g>
      <text font-family="${cond}" font-size="${bFs}" fill="${t.text}">${lines.map((l, k) => `<tspan x="860" y="${(top + k * lh).toFixed(0)}">${esc(l)}</tspan>`).join('')}</text>
    </g>`;
  }).join('');

  const quoteLines = wrapText(meta.quote, 19, 3);
  const qFs = Math.round(clamp(290 / (Math.max(...quoteLines.map((l) => l.length), 1) * 0.55), 27, 40));
  const quote = meta.quote ? `
    <g>
      <path d="M8 1346 L18 1318 L10 1296 L30 1280 L22 1256 L40 1240 L36 1214 L60 1200 L410 1146 L428 1162 L420 1186 L436 1204 L424 1236 L430 1262 L418 1292 L28 1392 Z" fill="${t.accent}"/>
      <path d="M6 1352 L420 1262" stroke="${t.accent2}" stroke-opacity="0.5" stroke-width="3" fill="none"/>
      <g transform="rotate(-9 220 1270)">
        <text font-family="${brush}" font-style="italic" font-weight="700" font-size="${qFs}" fill="${t.ink}" text-anchor="middle">${quoteLines.map((l, k) => `<tspan x="224" y="${(1262 + (k - (quoteLines.length - 1) / 2) * Math.round(qFs * 1.2)).toFixed(0)}">${esc(l)}</tspan>`).join('')}</text>
      </g>
    </g>` : '';

  const closingLines = wrapText(meta.closing.line, 18, 3);
  const cFs = Math.round(clamp(300 / (Math.max(...closingLines.map((l) => l.length), 1) * 0.62), 22, 31));
  const cBase = 1252 - (closingLines.length - 1) * 16;
  const closing = (meta.closing.line || meta.closing.accent) ? `
    <g>
      <path d="M810 1188 l8 -24 l10 15 l10 -15 l8 24 z" fill="${t.accent}"/>
      <line x1="700" y1="1182" x2="788" y2="1182" stroke="${t.accent}" stroke-width="2"/>
      <line x1="868" y1="1182" x2="960" y2="1182" stroke="${t.accent}" stroke-width="2"/>
      <text font-family="${cond}" font-weight="700" font-size="${cFs}" fill="${t.text}" text-anchor="middle">${closingLines.map((l, k) => `<tspan x="828" y="${cBase + k * Math.round(cFs * 1.1)}">${esc(l)}</tspan>`).join('')}</text>
      ${meta.closing.accent ? fit(meta.closing.accent, 828, cBase + closingLines.length * Math.round(cFs * 1.1) + 10, 40, 300, `font-family="${cond}" font-weight="800" fill="${t.accent}"`, 'middle', 0.62) : ''}
    </g>` : '';

  const photoLayer = photo
    ? `<g mask="url(#fadeTop)"><image href="${photo}" x="0" y="470" width="1024" height="970" preserveAspectRatio="xMidYMid slice"/>
       <rect x="560" y="470" width="464" height="970" fill="url(#sideShade)"/></g>`
    : fallbackArt(t);

  const byline = `PAR ${upper(author)}`;
  const byW = Math.min(520, byline.length * 26);

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${t.bg2}"/><stop offset="0.55" stop-color="${t.bg1}"/><stop offset="1" stop-color="${t.bg1}"/></linearGradient>
    <linearGradient id="gold" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${t.accent2}"/><stop offset="0.55" stop-color="${t.accent}"/><stop offset="1" stop-color="${t.accent}" stop-opacity="0.82"/></linearGradient>
    <linearGradient id="fadeTopG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000"/><stop offset="0.17" stop-color="#fff"/><stop offset="1" stop-color="#fff"/></linearGradient>
    <mask id="fadeTop" maskUnits="userSpaceOnUse" x="0" y="470" width="1024" height="970"><rect x="0" y="470" width="1024" height="970" fill="url(#fadeTopG)"/></mask>
    <linearGradient id="sideShade" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${t.bg1}" stop-opacity="0"/><stop offset="0.5" stop-color="${t.bg1}" stop-opacity="0.55"/><stop offset="1" stop-color="${t.bg1}" stop-opacity="0.82"/></linearGradient>
    <linearGradient id="bottomShade" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${t.bg1}" stop-opacity="0"/><stop offset="1" stop-color="${t.bg1}" stop-opacity="0.9"/></linearGradient>
  </defs>
  <rect width="${W}" height="${H}" fill="url(#bg)"/>
  ${photoLayer}
  <rect x="0" y="1180" width="1024" height="260" fill="url(#bottomShade)"/>

  ${kickRules}
  <text x="${kx1}" y="70" font-family="${cond}" font-size="34" fill="${t.text}" textLength="${kickW}" lengthAdjust="spacing">${esc(meta.kicker)}</text>
  <g>
    <rect x="830" y="22" width="172" height="58" rx="12" fill="${t.accent}"/>
    <g transform="translate(858 51) scale(0.6)">${iconGlyph('book', t.ink)}</g>
    <text x="884" y="60" font-family="${cond}" font-weight="800" font-size="27" fill="${t.ink}" textLength="100" lengthAdjust="spacingAndGlyphs">E-BOOK</text>
  </g>

  ${hasLead ? `<text x="${W / 2}" y="${Math.round(mainY - cap - 24)}" font-family="${font}" font-weight="900" font-size="${fsLead}" fill="${t.text}" text-anchor="middle" textLength="${leadW}" lengthAdjust="spacingAndGlyphs">${esc(meta.titleLead)}</text>` : ''}
  <text x="${W / 2}" y="${mainY}" font-family="${font}" font-weight="900" font-size="${fsMain}" fill="url(#gold)" text-anchor="middle" textLength="${mainWidth}" lengthAdjust="spacingAndGlyphs">${esc(meta.titleMain)}</text>

  <text x="${W / 2}" y="${subY}" font-family="${cond}" font-size="43" font-weight="600" fill="${t.text}" text-anchor="middle"${kwAttrs}>${kw}</text>
  ${hookLines.map((l, k) => fit(l, W / 2, subY + 46 + k * 32, 27, 940, `font-family="${cond}" fill="${t.muted}"`, 'middle', 0.5)).join('')}

  ${bullets}
  ${quote}
  ${closing}

  <rect x="0" y="1418" width="${W}" height="118" fill="${t.bg1}"/>
  <line x1="0" y1="1419" x2="${W}" y2="1419" stroke="${t.accent}" stroke-width="2.5"/>
  <line x1="${Math.round(W / 2 - byW / 2 - 160)}" y1="1480" x2="${Math.round(W / 2 - byW / 2 - 24)}" y2="1480" stroke="${t.accent}" stroke-width="2" stroke-opacity="0.8"/>
  <line x1="${Math.round(W / 2 + byW / 2 + 24)}" y1="1480" x2="${Math.round(W / 2 + byW / 2 + 160)}" y2="1480" stroke="${t.accent}" stroke-width="2" stroke-opacity="0.8"/>
  <text x="${W / 2 - byW / 2}" y="1491" font-family="${cond}" font-size="31" fill="${t.text}" textLength="${byW}" lengthAdjust="spacing">${esc(byline)}</text>
</svg>`;
}

// ─────────────────────────────────────────────────────────────────────────────
// VISUEL : Gemini (si clé) -> Pollinations -> (option) Pinterest
// ─────────────────────────────────────────────────────────────────────────────
async function fetchDataUri(deps, url, opts = {}) {
  const res = await deps.fetch(url, { signal: AbortSignal.timeout(opts.timeout || 70000), ...opts.init });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const type = (res.headers.get('content-type') || 'image/jpeg').split(';')[0];
  if (!/^image\/(jpeg|png|webp)$/.test(type)) throw new Error('Type image inattendu : ' + type);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 4000) throw new Error('Image trop petite');
  return `data:${type};base64,${buf.toString('base64')}`;
}

async function geminiImage(deps, key, prompt) {
  if (!key) return null;
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${IMAGE_MODEL}:generateContent`;
  const res = await deps.fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }] }),
    signal: AbortSignal.timeout(70000),
  });
  if (!res.ok) throw new Error('Gemini image HTTP ' + res.status);
  const data = await res.json();
  const part = (data?.candidates?.[0]?.content?.parts || []).find((p) => p.inlineData);
  if (!part) throw new Error('Gemini image : pas d\'image renvoyée');
  return `data:${part.inlineData.mimeType};base64,${part.inlineData.data}`;
}

async function pollinationsImage(deps, prompt) {
  const seed = crypto.randomInt(1, 999999);
  const url = `https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=1024&height=1024&nologo=true&model=flux&seed=${seed}`;
  return fetchDataUri(deps, url, { timeout: 90000 });
}

async function pinterestImage(deps, query) {
  const res = await deps.fetch(`https://zetbot-page.onrender.com/api/pinterest?query=${encodeURIComponent(query)}`, { signal: AbortSignal.timeout(20000) });
  if (!res.ok) throw new Error('Pinterest HTTP ' + res.status);
  const data = await res.json();
  const raw = data?.data || data?.pins || data?.results || data || [];
  const urls = (Array.isArray(raw) ? raw : []).map((p) => (typeof p === 'string' ? p : p && (p.image || p.url))).filter((u) => typeof u === 'string' && /^https:\/\//.test(u)).slice(0, 6);
  for (const u of urls) {
    try { return await fetchDataUri(deps, u, { timeout: 30000 }); } catch (_) { /* image suivante */ }
  }
  throw new Error('Aucune image Pinterest exploitable');
}

// ─────────────────────────────────────────────────────────────────────────────
// EPUB 3 (ZIP « stocké » écrit à la main : aucune dépendance)
// ─────────────────────────────────────────────────────────────────────────────
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function zipStore(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  const dosTime = 0x6000; // 12:00
  const dosDate = ((2026 - 1980) << 9) | (1 << 5) | 1;
  for (const f of files) {
    const name = Buffer.from(f.name, 'utf8');
    const data = Buffer.isBuffer(f.data) ? f.data : Buffer.from(f.data, 'utf8');
    const crc = crc32(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0x0800, 6); lh.writeUInt16LE(0, 8);
    lh.writeUInt16LE(dosTime, 10); lh.writeUInt16LE(dosDate, 12); lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(data.length, 22); lh.writeUInt16LE(name.length, 26); lh.writeUInt16LE(0, 28);
    locals.push(lh, name, data);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0x0800, 8); ch.writeUInt16LE(0, 10);
    ch.writeUInt16LE(dosTime, 12); ch.writeUInt16LE(dosDate, 14); ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(data.length, 24); ch.writeUInt16LE(name.length, 28);
    ch.writeUInt32LE(offset, 42);
    centrals.push(ch, name);
    offset += 30 + name.length + data.length;
  }
  const cdSize = centrals.reduce((n, b) => n + b.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cdSize, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, ...centrals, end]);
}

const XHTML_HEAD = (title) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="fr" lang="fr">\n<head><meta charset="utf-8"/><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>\n<body>\n`;

const EPUB_CSS = `
body{font-family:Georgia,'Times New Roman',serif;line-height:1.6;margin:5%;color:#1b1b1b}
h1{font-size:1.9em;line-height:1.2;margin:2.2em 0 .6em;text-align:left;page-break-before:always}
h2{font-size:1.25em;margin:1.8em 0 .5em}
p{margin:0 0 .9em;text-align:justify}
ul,ol{margin:0 0 1em 1.2em}li{margin:.25em 0}
.chap-num{display:block;font-size:.55em;letter-spacing:.2em;text-transform:uppercase;color:#8a6d1c;margin-bottom:.4em}
.title-page{text-align:center;margin-top:30%}.title-page h1{page-break-before:avoid;text-align:center;font-size:2.3em}
.title-page .sub{font-style:italic;margin:1em 0 3em}.title-page .by{letter-spacing:.2em;text-transform:uppercase;font-size:.85em}
.note{border-left:3px solid #8a6d1c;padding:.2em 1em;margin:1.5em 0;background:#faf6ea;font-size:.95em}
.cover{text-align:center;margin:0;padding:0}.cover img{max-width:100%;max-height:100%}
`;

const DISCLAIMERS = {
  health: "Cet ouvrage est fourni à titre d'information générale. Il ne remplace pas l'avis, le diagnostic ou le traitement d'un professionnel de santé : en cas de doute ou de symptôme, consultez un médecin.",
  legal: "Cet ouvrage est fourni à titre d'information générale. Il ne constitue pas un conseil juridique : pour votre situation personnelle, adressez-vous à un avocat ou à un juriste.",
  finance: "Cet ouvrage est fourni à titre d'information générale. Il ne constitue pas un conseil en investissement : tout placement comporte un risque de perte, renseignez-vous auprès d'un conseiller agréé.",
};

function buildEpub({ meta, author, plan, chapters, coverSvg, id }) {
  const title = meta.bookTitle;
  const now = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
  const files = [];
  const manifest = [];
  const spine = [];
  const add = (name, data, id_, type, props, inSpine) => {
    files.push({ name: 'OEBPS/' + name, data });
    manifest.push(`<item id="${id_}" href="${name}" media-type="${type}"${props ? ` properties="${props}"` : ''}/>`);
    if (inSpine) spine.push(`<itemref idref="${id_}"/>`);
  };

  add('style.css', EPUB_CSS, 'css', 'text/css');
  add('cover.svg', coverSvg, 'cover-img', 'image/svg+xml', 'cover-image');
  add('cover.xhtml', `${XHTML_HEAD(title)}<div class="cover"><img src="cover.svg" alt="Couverture : ${esc(title)}"/></div>\n</body></html>`, 'cover', 'application/xhtml+xml', 'svg', true);
  add('titlepage.xhtml', `${XHTML_HEAD(title)}<div class="title-page"><h1>${esc(title)}</h1><p class="sub">${esc(meta.bookSubtitle)}</p><p class="by">${esc(author)}</p></div>\n</body></html>`, 'titlepage', 'application/xhtml+xml', '', true);

  const disc = DISCLAIMERS[meta.sensitive];
  if (disc) add('avis.xhtml', `${XHTML_HEAD('Avis important')}<h1>Avis important</h1><div class="note"><p>${esc(disc)}</p></div>\n</body></html>`, 'avis', 'application/xhtml+xml', '', true);

  if (plan.introduction) add('intro.xhtml', `${XHTML_HEAD('Avant-propos')}<h1>Avant-propos</h1><p>${inline(plan.introduction)}</p>\n</body></html>`, 'intro', 'application/xhtml+xml', '', true);

  const navItems = [];
  chapters.forEach((body, i) => {
    const file = `chap${i + 1}.xhtml`;
    const ct = plan.chapters[i].title;
    add(file, `${XHTML_HEAD(ct)}<h1><span class="chap-num">Chapitre ${i + 1}</span>${esc(ct)}</h1>\n${textToXhtml(body)}\n</body></html>`, `chap${i + 1}`, 'application/xhtml+xml', '', true);
    navItems.push(`<li><a href="${file}">${i + 1}. ${esc(ct)}</a></li>`);
  });

  const nav = `${XHTML_HEAD('Sommaire')}<nav epub:type="toc" id="toc"><h1>Sommaire</h1><ol>${navItems.join('')}</ol></nav>\n</body></html>`;
  files.push({ name: 'OEBPS/nav.xhtml', data: nav });
  manifest.push('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>');
  spine.splice(2, 0, '<itemref idref="nav"/>');

  const opf = `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="fr">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">urn:uuid:${id}</dc:identifier>
<dc:title>${esc(title)}</dc:title>
<dc:creator>${esc(author)}</dc:creator>
<dc:language>fr</dc:language>
<dc:description>${esc(meta.bookSubtitle)}</dc:description>
<meta property="dcterms:modified">${now}</meta>
<meta name="cover" content="cover-img"/>
</metadata>
<manifest>
${manifest.join('\n')}
</manifest>
<spine>
${spine.join('\n')}
</spine>
</package>`;
  files.push({ name: 'OEBPS/content.opf', data: opf });

  const container = `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`;

  return zipStore([
    { name: 'mimetype', data: 'application/epub+zip' },
    { name: 'META-INF/container.xml', data: container },
    ...files,
  ]);
}

// ─────────────────────────────────────────────────────────────────────────────
// TRAVAIL (job) + ÉTAPES VISIBLES
// ─────────────────────────────────────────────────────────────────────────────
function makeSteps(n) {
  const steps = [
    { id: 'analyse', label: 'Analyse du sujet', w: 5 },
    { id: 'plan', label: "Plan de l'ouvrage", w: 5 },
  ];
  for (let i = 0; i < n; i++) steps.push({ id: 'ch' + (i + 1), label: `Chapitre ${i + 1}`, w: 55 / n });
  steps.push(
    { id: 'photo', label: 'Visuel de couverture', w: 10 },
    { id: 'cover', label: 'Composition de la couverture', w: 8 },
    { id: 'epub', label: "Assemblage de l'e-book", w: 8 },
    { id: 'check', label: 'Contrôle final', w: 4 }
  );
  return steps.map((s) => ({ ...s, state: 'pending', detail: '' }));
}

function pushLog(job, msg) {
  job.logSeq += 1;
  job.log.push({ n: job.logSeq, t: Math.round((Date.now() - job.createdAt) / 1000), msg });
  if (job.log.length > 200) job.log.shift();
}
function setStep(job, id, state, detail) {
  const s = job.steps.find((x) => x.id === id);
  if (!s) return;
  s.state = state;
  if (detail !== undefined) s.detail = detail;
  const done = job.steps.reduce((n, x) => n + (x.state === 'done' ? x.w : x.state === 'active' ? x.w * 0.35 : 0), 0);
  const total = job.steps.reduce((n, x) => n + x.w, 0);
  job.progress = Math.min(99, Math.round((done / total) * 100));
}
function checkCancel(job) { if (job.cancelled) throw new Cancelled(); }

async function runJob(job, deps) {
  const { llm, geminiKey } = job.ctx;
  try {
    // 1. Analyse
    checkCancel(job);
    setStep(job, 'analyse', 'active', 'Je cerne le public, le ton et l\'angle…');
    pushLog(job, `Sujet reçu : ${job.topic}`);
    job.meta = await analyseTopic(deps, llm, job.topic, job.style);
    setStep(job, 'analyse', 'done', job.meta.bookTitle);
    pushLog(job, `Titre retenu : ${job.meta.bookTitle}`);
    pushLog(job, `Thème de couverture : ${job.meta.theme.replace('_', ' / ')}`);

    // 2. Plan
    checkCancel(job);
    setStep(job, 'plan', 'active', `Structuration en ${job.nChapters} chapitres…`);
    const plan = await planBook(deps, llm, job.topic, job.meta, job.nChapters);
    job.plan = plan;
    plan.chapters.forEach((c, i) => { const s = job.steps.find((x) => x.id === 'ch' + (i + 1)); if (s) { s.label = `Chapitre ${i + 1} — ${c.title}`; } });
    setStep(job, 'plan', 'done', `${plan.chapters.length} chapitres planifiés`);
    plan.chapters.forEach((c, i) => pushLog(job, `Plan ${i + 1}/${plan.chapters.length} : ${c.title}`));

    // 3. Chapitres, un par un
    const bodies = [];
    for (let i = 0; i < plan.chapters.length; i++) {
      checkCancel(job);
      const id = 'ch' + (i + 1);
      setStep(job, id, 'active', 'Rédaction en cours…');
      pushLog(job, `Rédaction du chapitre ${i + 1}…`);
      const body = await writeChapter(deps, llm, job.meta, plan, i, job.words);
      bodies.push(body);
      const wc = countWords(body);
      job.stats.words += wc;
      job.stats.chapters = bodies.length;
      setStep(job, id, 'done', `${wc} mots`);
      pushLog(job, `Chapitre ${i + 1} terminé — ${wc} mots (total ${job.stats.words})`);
    }

    // 4. Visuel
    checkCancel(job);
    setStep(job, 'photo', 'active', job.photoMode === 'pinterest' ? 'Recherche d\'une photo…' : 'Génération du visuel…');
    let photo = null;
    const photoPrompt = `${job.meta.photoPrompt}. Professional editorial photography, cinematic lighting, premium magazine quality, subject centered, ${(THEMES[job.meta.theme] || THEMES.or_nuit).light ? 'clean bright neutral background' : 'clean dark background'}, shallow depth of field, no text, no letters, no watermark`;
    const attempts = job.photoMode === 'pinterest'
      ? [['Pinterest', () => pinterestImage(deps, job.meta.photoQuery)], ['Gemini', () => geminiImage(deps, geminiKey, photoPrompt)], ['Pollinations', () => pollinationsImage(deps, photoPrompt)]]
      : [['Gemini', () => geminiImage(deps, geminiKey, photoPrompt)], ['Pollinations', () => pollinationsImage(deps, photoPrompt)], ['Pinterest', () => pinterestImage(deps, job.meta.photoQuery)]];
    for (const [name, fn] of attempts) {
      checkCancel(job);
      try {
        const r = await fn();
        if (r) { photo = r; pushLog(job, `Visuel obtenu via ${name}`); setStep(job, 'photo', 'active', `Visuel via ${name}`); break; }
      } catch (e) {
        pushLog(job, `${name} indisponible (${String(e.message).slice(0, 60)})`);
      }
    }
    if (photo) setStep(job, 'photo', 'done', job.steps.find((s) => s.id === 'photo').detail);
    else { setStep(job, 'photo', 'done', 'Motif graphique (aucun visuel disponible)'); pushLog(job, 'Aucun visuel disponible : motif décoratif utilisé'); }

    // 5. Couverture
    checkCancel(job);
    setStep(job, 'cover', 'active', 'Titre, pictogrammes, accroche…');
    job.coverSvg = buildCoverSvg({ meta: job.meta, author: job.author, photo });
    setStep(job, 'cover', 'done', `${Math.round(job.coverSvg.length / 1024)} Ko · 1024×1536`);
    pushLog(job, 'Couverture composée');

    // 6. EPUB
    checkCancel(job);
    setStep(job, 'epub', 'active', 'Mise en page EPUB 3…');
    job.epub = buildEpub({ meta: job.meta, author: job.author, plan, chapters: bodies, coverSvg: job.coverSvg, id: job.uuid });
    setStep(job, 'epub', 'done', `${Math.round(job.epub.length / 1024)} Ko`);
    pushLog(job, 'EPUB assemblé');

    // 7. Contrôle
    setStep(job, 'check', 'active', 'Vérification de la structure…');
    const thin = bodies.map((b, i) => [i + 1, countWords(b)]).filter(([, w]) => w < 150);
    if (job.epub.slice(0, 4).toString('hex') !== '504b0304') throw new Error('EPUB invalide');
    setStep(job, 'check', 'done', thin.length ? `Chapitre(s) courts : ${thin.map(([i]) => i).join(', ')}` : `${job.stats.chapters} chapitres · ${job.stats.words} mots`);
    pushLog(job, 'Contrôle terminé — e-book prêt');

    job.progress = 100;
    job.status = 'done';
  } catch (e) {
    if (e instanceof Cancelled) {
      job.status = 'cancelled';
      job.steps.forEach((s) => { if (s.state === 'active') s.state = 'pending'; });
      pushLog(job, 'Création annulée');
    } else {
      job.status = 'error';
      job.error = e.userMessage || e.message || 'Erreur inconnue';
      job.steps.forEach((s) => { if (s.state === 'active') { s.state = 'error'; s.detail = job.error.slice(0, 120); } });
      pushLog(job, `Erreur : ${job.error.slice(0, 160)}`);
      console.error('ebook', job.id, e);
    }
  } finally {
    job.finishedAt = Date.now();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// ROUTES
// ─────────────────────────────────────────────────────────────────────────────
function createEngine(deps) {
  const jobs = new Map();

  const sweep = () => {
    const now = Date.now();
    for (const [id, j] of jobs) if (now - j.createdAt > JOB_TTL_MS) jobs.delete(id);
    while (jobs.size > MAX_JOBS) jobs.delete(jobs.keys().next().value);
  };

  function start(body) {
    sweep();
    const topic = str(body.topic, 300);
    if (!topic) { const e = new Error('Dis-moi le sujet de l\'e-book.'); e.status = 400; throw e; }
    const running = [...jobs.values()].filter((j) => j.status === 'running').length;
    if (running >= MAX_RUNNING) { const e = new Error('Deux e-books sont déjà en cours de création. Patiente un instant.'); e.status = 429; throw e; }
    let llm;
    try { llm = deps.resolveLLM(body); } catch (err) { err.status = 400; throw err; }
    if (!llm.apiKey) { const e = new Error("Aucune clé API : ajoute-la dans l'app (menu ⚙ > Clés API & fournisseurs)."); e.status = 400; throw e; }

    const nChapters = clamp(parseInt(body.chapters, 10) || 6, 3, 10);
    const words = clamp(parseInt(body.wordsPerChapter, 10) || 750, 300, 1200);
    const id = crypto.randomBytes(8).toString('hex');
    const job = {
      id, uuid: crypto.randomUUID(), createdAt: Date.now(), finishedAt: 0,
      topic, author: str(body.author, 60) || 'Jarvis', style: str(body.style, 40), photoMode: /pinterest/i.test(str(body.photo, 20)) ? 'pinterest' : 'ai',
      nChapters, words, status: 'running', progress: 0, error: '', cancelled: false,
      steps: makeSteps(nChapters), log: [], logSeq: 0, stats: { words: 0, chapters: 0 }, meta: null, plan: null, coverSvg: '', epub: null,
      ctx: { llm, geminiKey: deps.geminiKeyFrom(body) },
    };
    jobs.set(id, job);
    runJob(job, deps);
    return job;
  }

  function view(job, since) {
    return {
      id: job.id, status: job.status, progress: job.progress, error: job.error,
      topic: job.topic, elapsed: Math.round(((job.finishedAt || Date.now()) - job.createdAt) / 1000),
      title: job.meta ? job.meta.bookTitle : '', theme: job.meta ? job.meta.theme : '',
      steps: job.steps.map(({ id, label, state, detail }) => ({ id, label, state, detail })),
      log: job.log.filter((l) => l.n > since), logSeq: job.logSeq, stats: job.stats,
      ready: { cover: !!job.coverSvg, epub: !!job.epub },
    };
  }

  return { jobs, start, view, runJob };
}

function register(app, checkSecret, deps) {
  const engine = createEngine(deps);
  const fail = (res, e) => res.status(e.status || 500).json({ error: e.userMessage || e.message || 'Erreur' });
  const find = (req, res) => { const j = engine.jobs.get(req.params.id); if (!j) { res.status(404).json({ error: 'E-book introuvable (le serveur a peut-être redémarré). Relance la création.' }); return null; } return j; };

  app.post('/ebook/start', checkSecret, (req, res) => {
    try { const job = engine.start(req.body || {}); res.json({ id: job.id, steps: job.steps.length }); } catch (e) { fail(res, e); }
  });
  app.get('/ebook/status/:id', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    res.json(engine.view(j, parseInt(req.query.since, 10) || 0));
  });
  app.post('/ebook/cancel/:id', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    j.cancelled = true; res.json({ ok: true });
  });
  app.get('/ebook/:id/cover.svg', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    if (!j.coverSvg) return res.status(409).json({ error: 'La couverture n\'est pas encore prête.' });
    res.type('image/svg+xml').send(j.coverSvg);
  });
  app.get('/ebook/:id/book.epub', checkSecret, (req, res) => {
    const j = find(req, res); if (!j) return;
    if (!j.epub) return res.status(409).json({ error: 'L\'e-book n\'est pas encore prêt.' });
    res.set({ 'Content-Type': 'application/epub+zip', 'Content-Disposition': 'attachment; filename="ebook.epub"' }).send(j.epub);
  });
  return engine;
}

module.exports = {
  register,
  createEngine,
  _internals: { buildCoverSvg, buildEpub, zipStore, crc32, textToXhtml, wrapText, analyseTopic, planBook, pickTheme, THEMES },
};
