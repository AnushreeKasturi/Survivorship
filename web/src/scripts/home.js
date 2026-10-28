import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { api, onSubmit, pct } from "./api.js";

gsap.registerPlugin(ScrollTrigger);
const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

// ---------- the population field: one dot per startup, red for the ones that closed ----------

function populationField(canvas) {
  const total = +canvas.dataset.total;
  const closed = +canvas.dataset.closed;
  // Seeded shuffle so the same dots are red on every visit.
  let seed = 2013;
  const rand = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32);
  const order = Uint32Array.from({ length: total }, (_, i) => i);
  for (let i = total - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  const rank = new Uint32Array(total);
  order.forEach((dot, r) => (rank[dot] = r));

  const ctx = canvas.getContext("2d");
  let cols, cell, size, w, h;
  function layout() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    w = canvas.clientWidth;
    h = canvas.clientHeight;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    cell = Math.sqrt((w * h) / total);
    cols = Math.ceil(w / cell);
    size = Math.max(1, cell * 0.42);
  }
  const state = { shown: 0, red: 0 };
  function draw() {
    ctx.clearRect(0, 0, w, h);
    for (let dot = 0; dot < total; dot++) {
      if (rank[dot] / total > state.shown) continue;
      // The first `closed` dots of the shuffled order are the red ones.
      const red = rank[dot] < closed * state.red;
      ctx.fillStyle = red ? "#ff3b2f" : "rgba(238,231,220,0.28)";
      ctx.fillRect((dot % cols) * cell, Math.floor(dot / cols) * cell, red ? size * 1.5 : size, red ? size * 1.5 : size);
    }
  }
  layout();
  if (reduced) {
    Object.assign(state, { shown: 1, red: 1 });
    draw();
  } else {
    gsap.timeline({ onUpdate: draw })
      .to(state, { shown: 1, duration: 1.6, ease: "power2.out" })
      .to(state, { red: 1, duration: 1.4, ease: "power1.inOut" }, "-=0.2");
  }
  addEventListener("resize", () => { layout(); draw(); });
}

populationField(document.querySelector(".field"));

// ---------- pinned pipeline on wide screens; a plain vertical list otherwise ----------

gsap.matchMedia().add("(min-width: 900px) and (prefers-reduced-motion: no-preference)", () => {
  const track = document.querySelector(".track");
  const distance = () => track.scrollWidth - innerWidth;
  gsap.to(track, {
    x: () => -distance(),
    ease: "none",
    scrollTrigger: { trigger: ".pipeline", start: "top top", end: () => "+=" + distance(), pin: true, scrub: 0.6, invalidateOnRefresh: true },
  });
});

// ---------- live lab ----------

function gauge(id, p) {
  const el = document.getElementById(id);
  const value = el.querySelector(".value");
  const out = el.querySelector(".big");
  if (p == null) {
    value.style.strokeDashoffset = 289;
    out.textContent = "–";
    return;
  }
  el.classList.toggle("risk", p < 0.5);
  value.style.strokeDashoffset = 289 * (1 - p);
  const counter = { n: 0 };
  gsap.to(counter, { n: p, duration: reduced ? 0 : 0.9, ease: "power2.out", onUpdate: () => (out.textContent = pct(counter.n)) });
}

const sentimentForm = onSubmit("sentiment-form", async (fd) => {
  const headline = fd.get("headline");
  const r = await api("/api/sentiment", { headline });
  const pos = new Set(r.positiveTerms);
  const neg = new Set(r.negativeTerms);
  // Same split as SentimentScorer.tokenize, so highlights line up with the scored terms.
  document.getElementById("s-tokens").replaceChildren(...headline.split(/([A-Za-z]+)/).filter(Boolean).map((part) => {
    const span = document.createElement("span");
    span.textContent = part;
    const word = part.toLowerCase();
    if (pos.has(word)) span.className = "pos";
    else if (neg.has(word)) span.className = "neg";
    return span;
  }));
  document.getElementById("s-score").textContent = (r.score > 0 ? "+" : "") + r.score;
  document.getElementById("s-class").textContent = r.class.toLowerCase();
});

const matchForm = onSubmit("match-form", async (fd) => {
  const r = await api("/api/match", Object.fromEntries(fd));
  document.getElementById("m-confidence").textContent = r.confidence.toFixed(2);
  document.getElementById("m-reason").textContent = (r.matched ? "accepted, " : "rejected, ") + r.reason;
  gsap.to("#m-bar", { scaleX: r.confidence, duration: 0.8, ease: "power3.out" });
  document.getElementById("m-bar").classList.toggle("rejected", !r.matched);
});

const parseHeadlines = (text) => text.split("\n")
  .map((line) => line.match(/^\s*(\d{4}-\d{2}-\d{2})\s*[:\-]\s*(.+)$/))
  .filter(Boolean)
  .map(([, date, text]) => ({ date, text: text.trim() }));

const predictForm = onSubmit("predict-form", async (f) => {
  const r = await api("/api/predict", {
    foundedAt: f.get("foundedAt"),
    firstFundingAt: f.get("firstFundingAt"),
    country: f.get("country") || undefined,
    categories: f.getAll("categories"),
    headlines: parseHeadlines(f.get("headlines")),
  });
  gauge("p-fin", r.financialOnly);
  gauge("p-sent", r.financialSentiment);
});

// Fill every panel once on load so the page shows real output straight away.
for (const form of [sentimentForm, matchForm, predictForm]) form.requestSubmit();
