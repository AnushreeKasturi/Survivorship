import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

// ---------- motion ----------

const mm = gsap.matchMedia();

mm.add("(prefers-reduced-motion: no-preference)", () => {
  gsap.from(".hero .eyebrow, .hero h1, .hero .lede, .hero .stats > div", {
    y: 40, opacity: 0, duration: 1, ease: "power3.out", stagger: 0.08,
  });

  document.querySelectorAll("[data-count]").forEach((el) => {
    const counter = { n: 0 };
    gsap.to(counter, {
      n: +el.dataset.count, duration: 1.6, ease: "power2.out", delay: 0.4,
      onUpdate: () => (el.textContent = Math.round(counter.n).toLocaleString("en-US")),
    });
  });

  gsap.utils.toArray(".section-head, .panel, .exp, .answer").forEach((el) => {
    gsap.from(el, {
      y: 60, opacity: 0, duration: 0.9, ease: "power3.out",
      scrollTrigger: { trigger: el, start: "top 85%" },
    });
  });

  gsap.utils.toArray(".bar > span").forEach((bar) => {
    gsap.from(bar, {
      scaleX: 0, duration: 1.2, ease: "power3.out",
      scrollTrigger: { trigger: bar, start: "top 90%" },
    });
  });
});

// Horizontal pinned pipeline on wide screens; a plain vertical list otherwise.
mm.add("(min-width: 900px) and (prefers-reduced-motion: no-preference)", () => {
  const track = document.querySelector(".track");
  const distance = () => track.scrollWidth - window.innerWidth + 64;
  gsap.to(track, {
    x: () => -distance(),
    ease: "none",
    scrollTrigger: {
      trigger: ".pipeline",
      start: "top top",
      end: () => "+=" + distance(),
      pin: true,
      scrub: 0.6,
      invalidateOnRefresh: true,
    },
  });
});

// ---------- live lab ----------

async function api(path, body) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  // A static host answers /api with HTML or a 404 page: treat that like no server at all.
  if (!res.headers.get("content-type")?.includes("json")) throw new TypeError("API unavailable");
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function markOffline() {
  document.querySelector(".offline").hidden = false;
}

function onSubmit(id, handler) {
  const form = document.getElementById(id);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    form.classList.add("busy");
    try {
      await handler(new FormData(form));
    } catch (err) {
      if (err instanceof TypeError) markOffline(); // fetch itself failed: no server
      else throw err;
    } finally {
      form.classList.remove("busy");
    }
  });
  return form;
}

function pop(el) {
  gsap.fromTo(el, { y: 12, opacity: 0 }, { y: 0, opacity: 1, duration: 0.5, ease: "power3.out" });
}

const sentimentForm = onSubmit("sentiment-form", async (fd) => {
  const headline = fd.get("headline");
  const r = await api("/api/sentiment", { headline });
  const pos = new Set(r.positiveTerms);
  const neg = new Set(r.negativeTerms);
  const tokens = document.getElementById("s-tokens");
  // Same split as SentimentScorer.tokenize, so highlights line up with the scored terms.
  tokens.replaceChildren(...headline.split(/([A-Za-z]+)/).filter(Boolean).map((part) => {
    const span = document.createElement("span");
    span.textContent = part;
    const word = part.toLowerCase();
    if (pos.has(word)) span.className = "pos";
    else if (neg.has(word)) span.className = "neg";
    return span;
  }));
  document.getElementById("s-score").textContent = (r.score > 0 ? "+" : "") + r.score;
  document.getElementById("s-class").textContent = r.class.toLowerCase();
  pop(tokens.parentElement);
});

const matchForm = onSubmit("match-form", async (fd) => {
  const r = await api("/api/match", Object.fromEntries(fd));
  document.getElementById("m-confidence").textContent = r.confidence.toFixed(2);
  document.getElementById("m-reason").textContent = r.matched ? "accepted · " + r.reason : "rejected · " + r.reason;
  gsap.to("#m-bar", { scaleX: r.confidence, duration: 0.8, ease: "power3.out" });
  document.getElementById("m-bar").classList.toggle("rejected", !r.matched);
});

function parseHeadlines(text) {
  return text.split("\n").map((line) => line.match(/^\s*(\d{4}-\d{2}-\d{2})\s*[:\-]\s*(.+)$/))
    .filter(Boolean)
    .map(([, date, text]) => ({ date, text: text.trim() }));
}

const predictForm = onSubmit("predict-form", async (f) => {
  const error = document.getElementById("p-error");
  error.textContent = "";
  try {
    const r = await api("/api/predict", {
      foundedAt: f.get("foundedAt"),
      firstFundingAt: f.get("firstFundingAt"),
      country: f.get("country") || undefined,
      categories: f.getAll("categories"),
      headlines: parseHeadlines(f.get("headlines")),
    });
    showProbability("p-fin", r.financialOnly);
    showProbability("p-sent", r.financialSentiment);
  } catch (err) {
    if (err instanceof TypeError) throw err;
    error.textContent = err.message.replace(/^requirement failed: /, "");
  }
});

function showProbability(id, p) {
  const el = document.getElementById(id);
  if (p == null) {
    el.textContent = "add news";
    return;
  }
  const counter = { n: 0 };
  el.classList.toggle("risk", p < 0.5);
  gsap.to(counter, {
    n: p * 100, duration: 0.9, ease: "power2.out",
    onUpdate: () => (el.textContent = counter.n.toFixed(1) + "%"),
  });
}

// Fill every panel once on load so the page shows real output straight away.
for (const form of [sentimentForm, matchForm, predictForm]) form.requestSubmit();
