import { api, onSubmit, markOffline, pct, ago } from "./api.js";

const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

function strip(r) {
  const s = el("div", "strip");
  s.append(el("div", "rail"), el("div", "mid"));
  const mark = el("div", "mark");
  mark.style.left = pct(r.financialOnly);
  mark.title = `Without news: ${pct(r.financialOnly)}`;
  s.append(mark);
  if (r.financialSentiment != null) {
    const news = el("div", "mark news");
    news.style.left = pct(r.financialSentiment);
    news.title = `With news: ${pct(r.financialSentiment)}`;
    s.append(news);
  }
  const ends = el("div", "ends");
  ends.append(el("span", null, "closes"), el("span", null, "keeps operating"));
  s.append(ends);
  return s;
}

function newsLine(r) {
  if (r.newsError) return "The news search failed, so only the structural model ran.";
  if (r.ambiguousName) return `“${r.profile.name}” is an ordinary word, so NameMatcher rejects its headlines and only the structural model runs.`;
  if (!r.headlines.length) return `${r.rawHeadlines} headlines found in the two years before ${r.asOf}; none named the company.`;
  return `${r.rawHeadlines} headlines found in the two years before ${r.asOf}; ${r.headlines.length} matched the name.`;
}

/** One ledger row; `outcome` is set for case studies with a known ending. */
export function row(r) {
  const p = r.profile;
  const li = el("li", "case");
  if (r.error) {
    li.append(el("div", "name", p.name), el("p", "muted", r.error));
    return li;
  }

  const head = el("div");
  head.append(el("div", "name", p.name));
  head.append(el("span", "meta", `${p.categories.join(", ") || "No category"}, ${p.country || "other country"}, founded ${p.foundedAt.slice(0, 7)}`));
  if (p.outcome) head.append(el("span", `tag ${p.outcome}`, p.outcome === "closed" ? `Closed ${p.outcomeDate.slice(0, 7)}` : "Still operating"));

  const mid = el("div");
  mid.append(strip(r));
  const readout = el("p", "readout");
  readout.append("Without news ", el("b", null, pct(r.financialOnly)));
  if (r.financialSentiment != null) readout.append(", with news ", el("b", null, pct(r.financialSentiment)));
  readout.append(` chance of still operating, as of ${r.asOf}.`);
  mid.append(readout);

  const right = el("div", "called");
  if (p.outcome) {
    const hit = (r.financialOnly >= 0.5) === (p.outcome === "operating");
    right.classList.add(hit ? "hit" : "miss");
    right.append(el("b", null, hit ? "Called it" : "Missed"));
    if (r.financialSentiment != null) {
      const newsHit = (r.financialSentiment >= 0.5) === (p.outcome === "operating");
      right.append(el("span", "muted", newsHit ? "News model called it" : "News model missed"));
    }
  } else {
    right.append(el("b", null, r.financialOnly >= 0.5 ? "Likely to last" : "At risk"));
    right.append(el("span", "muted", `checked ${ago(r.checkedAt)}`));
  }

  const details = el("details");
  details.append(el("summary", null, "Show the evidence"));
  const detail = el("div", "detail");
  const left = el("div");
  if (p.note) left.append(el("p", null, p.note));
  left.append(el("p", "muted", newsLine(r)));
  if (p.sources?.length) {
    const src = el("div", "sources");
    p.sources.forEach((u) => {
      const a = el("a", null, new URL(u).hostname.replace(/^www\./, ""));
      a.href = u;
      a.rel = "noopener";
      src.append(a);
    });
    left.append(src);
  }
  const heads = el("ul", "heads");
  r.headlines.slice(-8).reverse().forEach((h) => {
    const item = el("li");
    const t = el("time", null, h.date);
    t.dateTime = h.date;
    item.append(t, el("span", null, h.text), el("span", `s ${h.score > 0 ? "pos" : h.score < 0 ? "neg" : ""}`, (h.score > 0 ? "+" : "") + h.score));
    heads.append(item);
  });
  detail.append(left, r.headlines.length ? heads : el("p", "muted", "No matched headlines to show."));
  details.append(detail);

  li.append(head, mid, right, details);
  return li;
}

// ---------- case studies: rendered from the JSON baked in at build time ----------

const cases = JSON.parse(document.getElementById("case-data").textContent);
document.getElementById("cases").replaceChildren(...cases.map(row));

// ---------- live watchlist ----------

const curatedList = document.getElementById("watch-curated");
const visitorList = document.getElementById("watch-visitors");
const status = document.getElementById("watch-status");

function fill(list, rows, empty) {
  list.replaceChildren(...(rows.length ? rows.map(row) : [el("li", "empty", empty)]));
}

async function loadWatchlist() {
  const w = await api("/api/watchlist");
  status.replaceChildren();
  if (w.lastRun) {
    status.append("Last checked ", el("b", null, ago(w.lastRun)), `. The scheduler re-runs the pipeline on every startup here every ${w.periodHours} hours.`);
  } else {
    status.append(el("b", null, "Checking now."), " The first run after the server wakes up takes about a minute.");
  }
  fill(curatedList, w.curated, "Results appear here as each startup is checked.");
  fill(visitorList, w.visitors, "Nobody has added a startup since the server last restarted. Add yours below.");
  if (w.running || !w.lastRun) setTimeout(() => loadWatchlist().catch(() => {}), 6000);
}

loadWatchlist().catch(() => {
  markOffline();
  status.textContent = "The live watchlist needs the Scala server.";
});

onSubmit("track-form", async (f) => {
  const r = await api("/api/track", {
    name: f.get("name"),
    foundedAt: f.get("foundedAt"),
    firstFundingAt: f.get("firstFundingAt"),
    country: f.get("country") || undefined,
    categories: f.getAll("categories"),
  });
  visitorList.querySelector(".empty")?.remove();
  const li = row(r);
  visitorList.prepend(li);
  li.scrollIntoView({ behavior: "smooth", block: "center" });
});
