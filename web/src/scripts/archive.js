import { api, onSubmit, markOffline, ago } from "./api.js";
import { card, queuedCard, el } from "./cards.js";

// Every startup ever listed: known endings (static), the watchlist, visitor
// additions and suggestions still waiting in the queue (live).
const grid = document.getElementById("archive");
const status = document.getElementById("archive-status");
const search = document.getElementById("archive-search");
const cases = JSON.parse(document.getElementById("case-data").textContent);
let entries = cases.map((r) => ({ kind: "ending", name: r.profile.name, node: card(r) }));
let filter = "all";

function render() {
  const q = search.value.trim().toLowerCase();
  const shown = entries.filter((e) => (filter === "all" || e.kind === filter) && e.name.toLowerCase().includes(q));
  grid.replaceChildren(...(shown.length ? shown.map((e) => e.node) : [el("li", "empty", "Nothing here yet.")]));
}

document.querySelectorAll("[data-filter]").forEach((b) => b.addEventListener("click", () => {
  filter = b.dataset.filter;
  document.querySelectorAll("[data-filter]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
  render();
}));
search.addEventListener("input", render);
render();

async function load() {
  const w = await api("/api/watchlist");
  const withT = (r) => ({ ...r, threshold: r.threshold ?? w.threshold });
  entries = [
    ...cases.map((r) => ({ kind: "ending", name: r.profile.name, node: card(r) })),
    ...w.curated.map((r) => ({ kind: "watchlist", name: r.profile.name, node: card(withT(r)) })),
    ...w.visitors.map((r) => ({ kind: "visitor", name: r.profile.name, node: card(withT(r)) })),
    ...w.queue.map((q) => ({ kind: "queued", name: q.profile.name, node: queuedCard(q) })),
  ];
  const n = w.news || {};
  status.textContent = `${entries.length} startups. ${n.articles ?? 0} news articles archived from GDELT so far` +
    (w.lastRun ? `; last checked ${ago(w.lastRun)}.` : "; checking now.");
  render();
  if (w.running || !w.lastRun || w.queue.length) setTimeout(() => load().catch(() => {}), w.queue.length ? 30000 : 6000);
}

load().catch(() => {
  markOffline();
  status.textContent = `${entries.length} startups with known endings. The live archive needs the Scala server.`;
});

onSubmit("suggest-form", async (f) => {
  const r = await api("/api/track", {
    name: f.get("name"),
    foundedAt: f.get("foundedAt"),
    firstFundingAt: f.get("firstFundingAt"),
    country: f.get("country") || undefined,
    categories: f.getAll("categories"),
    keywords: (f.get("keywords") || "").split(",").map((k) => k.trim()).filter(Boolean),
  });
  const done = document.getElementById("suggest-done");
  done.textContent = `${r.profile.name} is in the queue (position ${r.position}). It will be scored at the next check.`;
  entries.push({ kind: "queued", name: r.profile.name, node: queuedCard({ profile: r.profile, queuedAt: new Date().toISOString() }) });
  filter = "all";
  render();
  document.getElementById("suggest-form").reset();
});
