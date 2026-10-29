import { api, markOffline, ago } from "./api.js";
import { card, el } from "./cards.js";

// Known endings: baked in at build time, so they show without the server.
const cases = JSON.parse(document.getElementById("case-data").textContent);
document.getElementById("cases").replaceChildren(...cases.map(card));

const list = document.getElementById("watch-curated");
const status = document.getElementById("watch-status");

async function load() {
  const w = await api("/api/watchlist");
  status.textContent = w.lastRun
    ? `Last checked ${ago(w.lastRun)}, again every ${w.periodHours} hours.`
    : "Checking now. The first run after the server wakes takes a minute or two.";
  list.replaceChildren(...(w.curated.length
    ? w.curated.map((r) => card({ ...r, threshold: r.threshold ?? w.threshold }))
    : [el("li", "empty", "Results appear here as each startup is checked.")]));
  if (w.running || !w.lastRun) setTimeout(() => load().catch(() => {}), 6000);
}

load().catch(() => {
  markOffline();
  status.textContent = "";
});
