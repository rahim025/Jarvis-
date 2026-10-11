'use strict';
/**
 * Générateur PDF minimal (sans dépendance) pour l'atelier e-book.
 * Polices standard PDF (Times / Helvetica) en WinAnsi : les accents français s'affichent correctement.
 */

const PAGE_W = 595, PAGE_H = 842, MX = 62, MY = 70, FOOT = 34;
const W = { F1: 0.46, F2: 0.5, F3: 0.44, F4: 0.6, F5: 0.55 }; // largeur moyenne d'un caractère (em)
const WIN = { 0x2019: 0x92, 0x2018: 0x91, 0x201C: 0x93, 0x201D: 0x94, 0x2013: 0x96, 0x2014: 0x97, 0x2026: 0x85, 0x0153: 0x9C, 0x0152: 0x8C, 0x20AC: 0x80, 0x2022: 0x95, 0x2122: 0x99 };

function winBytes(s) {
  const out = [];
  for (const ch of String(s)) {
    const c = ch.codePointAt(0);
    if ((c < 256 && (c < 0x80 || c > 0x9f)) ) out.push(c);
    else if (WIN[c]) out.push(WIN[c]);
    else out.push(0x3f);
  }
  return out;
}
const pdfStr = (s) => '(' + String.fromCharCode(...winBytes(s)).replace(/[\\()]/g, '\\$&') + ')';
const rgb = (hex) => {
  const n = parseInt(String(hex).slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => (v / 255).toFixed(3)).join(' ');
};
const textWidth = (s, font, size) => String(s).length * size * W[font];

function wrap(text, font, size, maxW) {
  const words = String(text).split(/\s+/).filter(Boolean);
  const lines = [];
  let cur = '';
  for (const w of words) {
    const cand = cur ? cur + ' ' + w : w;
    if (textWidth(cand, font, size) > maxW && cur) { lines.push(cur); cur = w; } else cur = cand;
  }
  if (cur) lines.push(cur);
  return lines;
}

const clean = (s) => String(s).replace(/\*\*(.+?)\*\*/g, '$1').replace(/\*(?!\s)([^*]+?)\*/g, '$1');

function buildPdf({ meta, author, plan, chapters, theme }) {
  const T = theme || { bg1: '#07090e', accent: '#f6c026', text: '#ffffff', muted: '#c8cdd8', ink: '#15130a' };
  const pages = [];
  let cur = null;
  let y = 0;

  const newPage = (cover = false) => {
    cur = { ops: [], cover };
    pages.push(cur);
    y = PAGE_H - MY;
    return cur;
  };
  const ensure = (h) => { if (y - h < MY + FOOT) newPage(); };
  const put = (font, size, color, x, yy, s) =>
    cur.ops.push(`BT /${font} ${size} Tf ${rgb(color)} rg ${x.toFixed(1)} ${yy.toFixed(1)} Td ${pdfStr(s)} Tj ET`);
  const centered = (font, size, color, yy, s) => put(font, size, color, (PAGE_W - textWidth(s, font, size)) / 2, yy, s);
  const line = (font, size, color, s, { lead = size * 1.45, x = MX } = {}) => {
    ensure(lead);
    put(font, size, color, x, y - size, s);
    y -= lead;
  };
  const para = (text, { font = 'F1', size = 11.5, color = '#1b1b1b', indent = 0, gap = 8 } = {}) => {
    const maxW = PAGE_W - 2 * MX - indent;
    for (const l of wrap(clean(text), font, size, maxW)) line(font, size, color, l, { x: MX + indent });
    y -= gap;
  };

  // ── Couverture ──
  newPage(true);
  cur.ops.push(`${rgb(T.bg1)} rg 0 0 ${PAGE_W} ${PAGE_H} re f`);
  cur.ops.push(`${rgb(T.accent)} rg ${MX} ${PAGE_H - 250} ${PAGE_W - 2 * MX} 2 re f`);
  cur.ops.push(`${rgb(T.accent)} rg ${MX} 140 ${PAGE_W - 2 * MX} 2 re f`);
  centered('F5', 13, T.accent, PAGE_H - 220, meta.kicker || 'GUIDE COMPLET');
  if (meta.titleLead) centered('F4', 26, T.text, 560, meta.titleLead);
  let fsMain = 96;
  while (textWidth(meta.titleMain || '', 'F4', fsMain) > PAGE_W - 2 * MX && fsMain > 36) fsMain -= 4;
  centered('F4', fsMain, T.accent, 470, meta.titleMain || '');
  let by = 400;
  for (const l of wrap(meta.bookTitle, 'F2', 22, 440)) { centered('F2', 22, T.text, by, l); by -= 28; }
  by -= 6;
  for (const l of wrap(meta.bookSubtitle || '', 'F3', 14, 420)) { centered('F3', 14, T.muted, by, l); by -= 20; }
  centered('F5', 12, T.accent, 190, `PAR ${String(author || 'Jarvis').toUpperCase()}`);

  // ── Titre, avis, avant-propos, sommaire ──
  newPage();
  line('F2', 26, '#1b1b1b', meta.bookTitle, { lead: 34 });
  y -= 6;
  para(meta.bookSubtitle || '', { font: 'F3', size: 13, color: '#4b4036', gap: 4 });
  para(`Par ${author || 'Jarvis'}`, { font: 'F5', size: 10, color: '#8a6d1c', gap: 20 });

  const DISC = {
    health: "Cet ouvrage est fourni à titre d'information générale. Il ne remplace pas l'avis, le diagnostic ou le traitement d'un professionnel de santé : en cas de doute ou de symptôme, consultez un médecin.",
    legal: "Cet ouvrage est fourni à titre d'information générale. Il ne constitue pas un conseil juridique : pour votre situation personnelle, adressez-vous à un avocat ou à un juriste.",
    finance: "Cet ouvrage est fourni à titre d'information générale. Il ne constitue pas un conseil en investissement : tout placement comporte un risque de perte, renseignez-vous auprès d'un conseiller agréé.",
  };
  if (DISC[meta.sensitive]) {
    newPage();
    line('F2', 20, '#1b1b1b', 'Avis important', { lead: 30 });
    para(DISC[meta.sensitive], { size: 11.5 });
  }
  if (plan.introduction) {
    newPage();
    line('F2', 20, '#1b1b1b', 'Avant-propos', { lead: 30 });
    para(plan.introduction, { size: 11.5 });
  }
  newPage();
  line('F2', 20, '#1b1b1b', 'Sommaire', { lead: 30 });
  plan.chapters.forEach((c, i) => para(`${i + 1}. ${c.title}`, { size: 12, gap: 6 }));

  // ── Chapitres ──
  chapters.forEach((body, i) => {
    newPage();
    line('F5', 9, '#8a6d1c', `CHAPITRE ${i + 1}`, { lead: 14 });
    for (const l of wrap(plan.chapters[i].title, 'F2', 20, PAGE_W - 2 * MX)) line('F2', 20, '#1b1b1b', l, { lead: 26 });
    y -= 14;
    const blocks = String(body).replace(/\r/g, '').split(/\n{2,}/).map((b) => b.trim()).filter(Boolean);
    for (const b of blocks) {
      const lines = b.split('\n').map((l) => l.trim()).filter(Boolean);
      if (/^#{1,4}\s+/.test(lines[0])) {
        ensure(40); y -= 8;
        for (const l of wrap(clean(lines[0].replace(/^#{1,4}\s+/, '')), 'F2', 13, PAGE_W - 2 * MX)) line('F2', 13, '#8a6d1c', l, { lead: 19 });
        y -= 4;
        if (lines.length > 1) para(lines.slice(1).join(' '));
      } else if (lines.every((l) => /^[-*•]\s+/.test(l))) {
        for (const l of lines) {
          const txt = clean(l.replace(/^[-*•]\s+/, ''));
          const ws = wrap(txt, 'F1', 11.5, PAGE_W - 2 * MX - 16);
          ws.forEach((w, k) => line('F1', 11.5, '#1b1b1b', (k === 0 ? '\u2022  ' : '') + w, { x: MX + (k === 0 ? 4 : 16), lead: 16 }));
        }
        y -= 8;
      } else {
        para(lines.join(' '));
      }
    }
  });

  // ── Pieds de page (numéros, hors couverture) ──
  pages.forEach((p, i) => {
    if (p.cover) return;
    p.ops.push(`BT /F5 9 Tf ${rgb('#8a8a8a')} rg ${((PAGE_W - textWidth(String(i + 1), 'F5', 9)) / 2).toFixed(1)} ${FOOT - 10} Td ${pdfStr(String(i + 1))} Tj ET`);
  });

  return assemble(pages, { title: meta.bookTitle, author: author || 'Jarvis' });
}

function assemble(pages, info) {
  const objs = [];
  const add = (body) => { objs.push(body); return objs.length; };
  add(null); // 1 : catalogue
  add(null); // 2 : pages
  const fontNames = { F1: 'Times-Roman', F2: 'Times-Bold', F3: 'Times-Italic', F4: 'Helvetica-Bold', F5: 'Helvetica' };
  const fontRefs = {};
  for (const [k, v] of Object.entries(fontNames)) {
    fontRefs[k] = add(`<< /Type /Font /Subtype /Type1 /BaseFont /${v} /Encoding /WinAnsiEncoding >>`);
  }
  const fontRes = '<< ' + Object.entries(fontRefs).map(([k, n]) => `/${k} ${n} 0 R`).join(' ') + ' >>';
  const infoRef = add(`<< /Title ${pdfStr(info.title)} /Author ${pdfStr(info.author)} /Producer (Jarvis) >>`);
  const kids = [];
  for (const p of pages) {
    const content = Buffer.from(p.ops.join('\n'), 'latin1');
    const cn = add(Buffer.concat([Buffer.from(`<< /Length ${content.length} >>\nstream\n`, 'latin1'), content, Buffer.from('\nendstream', 'latin1')]));
    kids.push(add(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PAGE_W} ${PAGE_H}] /Resources ${fontRes} /Contents ${cn} 0 R >>`));
  }
  objs[0] = '<< /Type /Catalog /Pages 2 0 R >>';
  objs[1] = `<< /Type /Pages /Kids [${kids.map((k) => k + ' 0 R').join(' ')}] /Count ${kids.length} >>`;

  const chunks = [Buffer.from('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n', 'latin1')];
  let len = chunks[0].length;
  const offs = [];
  objs.forEach((o, i) => {
    offs.push(len);
    const b = Buffer.concat([
      Buffer.from(`${i + 1} 0 obj\n`, 'latin1'),
      Buffer.isBuffer(o) ? o : Buffer.from(o, 'latin1'),
      Buffer.from('\nendobj\n', 'latin1'),
    ]);
    chunks.push(b);
    len += b.length;
  });
  const xref = len;
  let x = `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offs.map((o) => String(o).padStart(10, '0') + ' 00000 n \n').join('');
  x += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R /Info ${infoRef} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  chunks.push(Buffer.from(x, 'latin1'));
  return Buffer.concat(chunks);
}

module.exports = { buildPdf };
