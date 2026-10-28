import { api, onSubmit } from "./api.js";

// Highlight the step being read in the sticky index.
const links = new Map([...document.querySelectorAll(".toc a")].map((a) => [a.hash.slice(1), a]));
const observer = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) {
    links.forEach((a) => a.classList.remove("on"));
    links.get(e.target.id)?.classList.add("on");
  }
}, { rootMargin: "-30% 0px -60% 0px" });
document.querySelectorAll(".step").forEach((s) => observer.observe(s));

// ---------- try-it widgets, backed by the real Scala code ----------

const matchForm = document.getElementById("match-try");
onSubmit("match-try", async (fd) => {
  const r = await api("/api/match", Object.fromEntries(fd));
  const out = matchForm.querySelector(".result");
  out.replaceChildren();
  const b = document.createElement("b");
  b.textContent = `${r.matched && r.confidence >= 0.6 ? "Used" : "Set aside"}, confidence ${r.confidence.toFixed(2)}.`;
  out.append(b, ` ${r.reason[0].toUpperCase()}${r.reason.slice(1)}.`);
}).requestSubmit();

const toneForm = document.getElementById("tone-try");
onSubmit("tone-try", async (fd) => {
  const headline = fd.get("headline");
  const r = await api("/api/sentiment", { headline });
  const pos = new Set(r.positiveTerms);
  const neg = new Set(r.negativeTerms);
  const out = toneForm.querySelector(".result");
  // Same split as SentimentScorer.tokenize, so highlights line up with the scored words.
  out.replaceChildren(...headline.split(/([A-Za-z]+)/).filter(Boolean).map((part) => {
    const span = document.createElement("span");
    span.textContent = part;
    const w = part.toLowerCase();
    if (pos.has(w)) span.className = "pos";
    else if (neg.has(w)) span.className = "neg";
    return span;
  }));
  const b = document.createElement("b");
  b.textContent = `  ${r.score > 0 ? "+" : ""}${r.score}, ${r.class.toLowerCase()}`;
  out.append(b);
}).requestSubmit();
