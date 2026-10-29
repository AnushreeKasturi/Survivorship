// Startup cards for the Startups page. Text goes in with
// textContent only: visitor-suggested names are untrusted.
import { ago } from "./api.js";
import { pct, score, word, maturityNote } from "./verdict.js";

export const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

function sparkline(points) {
  if (!points || points.length < 2) return null;
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", "0 0 100 24");
  svg.setAttribute("preserveAspectRatio", "none");
  svg.classList.add("spark");
  const ys = points.map((p) => p.financialOnly);
  const lo = Math.min(...ys) - 0.01, hi = Math.max(...ys) + 0.01;
  const line = document.createElementNS(NS, "polyline");
  line.setAttribute("points", ys.map((y, i) => `${(i / (ys.length - 1)) * 100},${22 - ((y - lo) / (hi - lo)) * 20}`).join(" "));
  svg.append(line);
  const title = document.createElementNS(NS, "title");
  title.textContent = `${points.length} checks, from ${score(ys[0])} to ${score(ys.at(-1))}`;
  svg.append(title);
  return svg;
}

function newsLine(r) {
  if (r.newsError) return "The Google News search failed this time; archived news and the structural model still ran.";
  if (r.ambiguousName && !r.scopedSearch) return `“${r.profile.name}” is an everyday word, so its headlines are set aside.`;
  const found = r.sources ? r.sources.googleNews + r.sources.gdelt : r.rawHeadlines;
  const named = r.nameMatched ?? r.headlines.length;
  return `${found} headlines found in the two years before ${r.asOf}; ${named} named the company and ${r.headlines.length} were business news about it, so only those count.`;
}

let dialog;
function openEvidence(r) {
  if (!dialog) {
    dialog = el("dialog", "evidence-dialog");
    dialog.addEventListener("click", (e) => { if (e.target === dialog) dialog.close(); });
    document.body.append(dialog);
  }
  const p = r.profile;
  const body = el("div", "evidence");
  const head = el("header");
  head.append(el("h3", "h3", p.name));
  const close = el("button", "theme close", "×");
  close.type = "button";
  close.setAttribute("aria-label", "Close");
  close.addEventListener("click", () => dialog.close());
  head.append(close);

  const left = el("div");
  const scores = el("p");
  scores.append("Scores ", el("strong", null, score(r.financialOnly)), " out of 100 without news");
  if (r.financialSentiment != null) scores.append(" and ", el("strong", null, score(r.financialSentiment)), " with news");
  scores.append(`, as of ${r.asOf}. The ${r.model || "model"} counts a score above ${score(r.threshold ?? 0.5)} as a survivor. Scores rank startups; they aren't probabilities.`);
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
  const heads = el("ul", "heads");
  r.headlines.slice(-8).reverse().forEach((h) => {
    const item = el("li");
    const t = el("time", null, h.date);
    t.dateTime = h.date;
    const text = el("span", null, h.text);
    if (h.source) text.append(el("small", "outlet", h.source));
    item.append(t, text, el("span", `s ${h.score > 0 ? "pos" : h.score < 0 ? "neg" : ""}`, (h.score > 0 ? "+" : "") + h.score));
    heads.append(item);
  });
  if (r.setAside?.length) {
    const aside = el("details", "inline");
    aside.append(el("summary", null, `Set aside: ${r.setAside.reduce((n, g) => n + g.count, 0)} headlines that named the company but weren't business news`));
    const list = el("ul", "aside");
    r.setAside.forEach((g) => {
      const li = el("li");
      li.append(el("strong", null, `${g.count} `), `${g.reason}, e.g. “${g.examples.at(-1)}”`);
      list.append(li);
    });
    aside.append(list);
    left.append(aside);
  }
  const grid = el("div", "evidence-grid");
  grid.append(left, r.headlines.length ? heads : el("p", "muted", "No matching headlines to show."));
  body.append(head, grid);
  dialog.replaceChildren(body);
  dialog.showModal();
}

/** A card for a scored startup (curated, case study or visitor). */
export function card(r) {
  const p = r.profile;
  const li = el("li", "tile");
  const top = el("div", "tile-top");
  top.append(el("h3", "name", p.name));
  if (p.outcome) top.append(el("span", `pill ${p.outcome}`, p.outcome === "closed" ? `closed ${p.outcomeDate.slice(0, 4)}` : "still going"));
  else if (r.maturity && r.maturity.level !== "young") top.append(el("span", `pill ${r.maturity.level}`, r.maturity.level));
  li.append(top, el("p", "sub", `${p.categories.join(", ") || "Startup"}, founded ${p.foundedAt.slice(0, 4)}`));
  if (r.error) {
    li.append(el("p", "muted", r.error));
    return li;
  }

  const t = r.threshold ?? 0.5;
  const scoreRow = el("div", "tile-score");
  const big = el("span", "pct", score(r.financialOnly));
  if (r.maturity?.level === "established") big.classList.add("faded");
  const verdict = el("span", "verdict-word");
  if (p.outcome) {
    const hit = (r.financialOnly >= t) === (p.outcome === "operating");
    verdict.textContent = hit ? "Called it" : "Missed";
    verdict.classList.add(hit ? "hit" : "miss");
  } else verdict.textContent = word(r.financialOnly, t);
  scoreRow.append(big, verdict);
  li.append(scoreRow);

  const meter = el("div", "meter");
  const dot = el("i");
  dot.style.left = pct(r.financialOnly);
  meter.append(dot);
  if (r.financialSentiment != null) {
    const news = el("i", "news");
    news.style.left = pct(r.financialSentiment);
    news.title = `With news: ${score(r.financialSentiment)}`;
    meter.append(news);
  }
  li.append(meter);
  const spark = sparkline(r.history);
  if (spark) li.append(spark);

  const foot = el("div", "tile-foot");
  foot.append(el("span", "muted", p.outcome ? `as of ${r.asOf}` : r.checkedAt ? `checked ${ago(r.checkedAt)}` : ""));
  const more = el("button", "link", "Evidence");
  more.type = "button";
  more.addEventListener("click", () => openEvidence(r));
  foot.append(more);
  li.append(foot);
  return li;
}

/** A card for a suggestion still waiting in the queue. */
export function queuedCard(q) {
  const p = q.profile;
  const li = el("li", "tile queued");
  const top = el("div", "tile-top");
  top.append(el("h3", "name", p.name), el("span", "pill queued", "queued"));
  li.append(top, el("p", "sub", `${p.categories.join(", ") || "Startup"}, founded ${p.foundedAt.slice(0, 4)}`));
  li.append(el("p", "muted", `Suggested ${ago(q.queuedAt)}. It's scored at the next check, within ten minutes while the server is awake.`));
  return li;
}
