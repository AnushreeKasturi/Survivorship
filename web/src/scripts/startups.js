import { api, onSubmit, markOffline, ago } from "./api.js";
import { pct, word, maturityNote } from "./verdict.js";

const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

function meter(r) {
  const m = el("div", "meter");
  if (r.maturity?.level === "established") m.classList.add("faded");
  const dot = el("i");
  dot.style.left = pct(r.financialOnly);
  dot.title = `Without news: ${pct(r.financialOnly)}`;
  m.append(dot);
  if (r.financialSentiment != null) {
    const news = el("i", "news");
    news.style.left = pct(r.financialSentiment);
    news.title = `With news: ${pct(r.financialSentiment)}`;
    m.append(news);
  }
  m.setAttribute("aria-label", `Chance it keeps going: ${pct(r.financialOnly)}`);
  return m;
}

function newsLine(r) {
  if (r.newsError) return "The news search failed this time, so only the structural model ran.";
  if (r.ambiguousName && !r.scopedSearch) return `“${r.profile.name}” is an everyday word, so its headlines are set aside.`;
  const scoped = r.scopedSearch ? ` (search narrowed with “${r.profile.keywords.join("”, “")}”)` : "";
  if (!r.headlines.length) return `No headlines in the two years before ${r.asOf} named the company${scoped}.`;
  return `${r.headlines.length} headlines from the two years before ${r.asOf} were about the company${scoped}.`;
}

/** One row: the verdict up front, the evidence one click away. */
export function row(r) {
  const p = r.profile;
  const li = el("li", "row");
  if (r.error) {
    li.append(el("p", "empty", `${p.name}: ${r.error}`));
    return li;
  }
  const details = el("details");
  const summary = el("summary");

  const head = el("div");
  const name = el("span", "name", p.name);
  if (p.outcome) name.append(el("span", `pill ${p.outcome}`, p.outcome === "closed" ? `closed ${p.outcomeDate.slice(0, 4)}` : "still going"));
  else if (r.maturity && r.maturity.level !== "young") name.append(el("span", `pill ${r.maturity.level}`, r.maturity.level));
  head.append(name, el("span", "sub", `${p.categories.join(", ") || "Startup"}, founded ${p.foundedAt.slice(0, 4)}`));

  const call = el("div", "call");
  if (p.outcome) {
    const hit = (r.financialOnly >= 0.5) === (p.outcome === "operating");
    call.classList.add(hit ? "hit" : "miss");
    call.append(el("b", null, hit ? "Called it" : "Missed"), el("span", "muted", pct(r.financialOnly)));
  } else {
    call.append(el("b", null, word(r.financialOnly)), el("span", "muted", pct(r.financialOnly)));
  }
  summary.append(head, meter(r), call);

  const ev = el("div", "evidence");
  const left = el("div");
  const scores = el("p");
  scores.append("Without news ", el("strong", null, pct(r.financialOnly)));
  if (r.financialSentiment != null) scores.append(", with news ", el("strong", null, pct(r.financialSentiment)));
  scores.append(` chance of still going, as of ${r.asOf}.`);
  left.append(scores);
  const note = maturityNote(r.maturity);
  if (note) left.append(el("p", "notice", note));
  if (p.note) left.append(el("p", null, p.note));
  left.append(el("p", null, newsLine(r)));
  if (p.sources?.length) {
    const src = el("p", "sources");
    p.sources.forEach((u) => {
      const a = el("a", null, new URL(u).hostname.replace(/^www\./, ""));
      a.href = u;
      a.rel = "noopener";
      src.append(a);
    });
    left.append(src);
  }
  if (!p.outcome && r.checkedAt) left.append(el("p", "muted", `Checked ${ago(r.checkedAt)}.`));

  const heads = el("ul", "heads");
  r.headlines.slice(-6).reverse().forEach((h) => {
    const item = el("li");
    const t = el("time", null, h.date);
    t.dateTime = h.date;
    item.append(t, el("span", null, h.text), el("span", `s ${h.score > 0 ? "pos" : h.score < 0 ? "neg" : ""}`, (h.score > 0 ? "+" : "") + h.score));
    heads.append(item);
  });
  ev.append(left, r.headlines.length ? heads : el("p", null, "No matching headlines to show."));

  details.append(summary, ev);
  li.append(details);
  return li;
}

// ---------- known endings: baked in at build time ----------

const cases = JSON.parse(document.getElementById("case-data").textContent);
document.getElementById("cases").replaceChildren(...cases.map(row));

// ---------- live watchlist ----------

const curatedList = document.getElementById("watch-curated");
const visitorList = document.getElementById("watch-visitors");
const status = document.getElementById("watch-status");

const fill = (list, rows, empty) => list.replaceChildren(...(rows.length ? rows.map(row) : [el("li", "empty", empty)]));

async function loadWatchlist() {
  const w = await api("/api/watchlist");
  status.textContent = w.lastRun
    ? `Last checked ${ago(w.lastRun)}, again every ${w.periodHours} hours.`
    : "Checking now. The first run after the server wakes takes about a minute.";
  fill(curatedList, w.curated, "Results appear here as each startup is checked.");
  fill(visitorList, w.visitors, "Nobody has added one yet. Be the first.");
  if (w.running || !w.lastRun) setTimeout(() => loadWatchlist().catch(() => {}), 6000);
}

loadWatchlist().catch(() => {
  markOffline();
  status.textContent = "";
});

onSubmit("track-form", async (f) => {
  const r = await api("/api/track", {
    name: f.get("name"),
    foundedAt: f.get("foundedAt"),
    firstFundingAt: f.get("firstFundingAt"),
    country: f.get("country") || undefined,
    categories: f.getAll("categories"),
    keywords: (f.get("keywords") || "").split(",").map((k) => k.trim()).filter(Boolean),
  });
  visitorList.querySelector(".empty")?.remove();
  const li = row(r);
  li.querySelector("details").open = true;
  visitorList.prepend(li);
  li.scrollIntoView({ behavior: "smooth", block: "center" });
});
