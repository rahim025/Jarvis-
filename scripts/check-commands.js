#!/usr/bin/env node
/**
 * Garde-fou : la liste « Affiche les commandes » ne peut pas être en retard sur les capacités de Jarvis.
 *
 * Vérifie, à chaque build (GitHub Actions) :
 *  1. chaque outil déclaré dans backend/server.js (téléphone ET serveur) a au moins une commande
 *     cmd("outil", ...) dans CommandCatalog.kt  -> sinon la nouvelle capacité serait invisible dans la liste ;
 *  2. chaque outil « téléphone » est géré par BackendClient.parseAction ;
 *  3. chaque commande du catalogue pointe vers un outil qui existe (pas de faute de frappe).
 *
 * Usage : node scripts/check-commands.js
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

const server = read('backend/server.js');
const catalog = read('app/src/main/java/com/Jarvis/app/ai/CommandCatalog.kt');
const client = read('app/src/main/java/com/Jarvis/app/ai/BackendClient.kt');

const serverToolsAt = server.indexOf('const SERVER_TOOLS');
const toolsEndAt = server.indexOf('const TOOLS =');
if (serverToolsAt < 0 || toolsEndAt < 0) {
  console.error('check-commands : impossible de repérer CLIENT_TOOLS / SERVER_TOOLS dans backend/server.js');
  process.exit(2);
}
const names = (text) => [...text.matchAll(/\btool\(\s*'([a-z_]+)'/g)].map((m) => m[1]);
const clientTools = names(server.slice(0, serverToolsAt));
const serverTools = names(server.slice(serverToolsAt, toolsEndAt));
const allTools = new Set([...clientTools, ...serverTools]);

const catalogTools = new Set([...catalog.matchAll(/\bcmd\(\s*"([a-z_]+)"/g)].map((m) => m[1]));

const parseStart = client.indexOf('private fun parseAction');
const parsed = new Set([...client.slice(parseStart).matchAll(/^\s*"([a-z_]+)"\s*->/gm)].map((m) => m[1]));

const problems = [];
for (const t of allTools) {
  if (!catalogTools.has(t)) {
    problems.push(`Outil « ${t} » : aucune commande dans CommandCatalog.kt -> ajoute cmd("${t}", CommandCategory.XXX, "Exemple de phrase").`);
  }
}
for (const t of clientTools) {
  if (!parsed.has(t)) {
    problems.push(`Outil téléphone « ${t} » : non géré par BackendClient.parseAction.`);
  }
}
for (const t of catalogTools) {
  if (!allTools.has(t)) {
    problems.push(`CommandCatalog.kt : la commande « ${t} » ne correspond à aucun outil de backend/server.js (faute de frappe ?).`);
  }
}

if (problems.length) {
  console.error('❌ Le catalogue des commandes n\'est pas à jour :\n - ' + problems.join('\n - '));
  process.exit(1);
}
console.log(`✅ Catalogue à jour : ${allTools.size} outils, ${catalogTools.size} couverts.`);
