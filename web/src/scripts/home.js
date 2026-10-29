import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { api, onSubmit } from "./api.js";
import { sky } from "./sky.js";
import { score, word, maturityNote } from "./verdict.js";

gsap.registerPlugin(ScrollTrigger);

sky(document.querySelector(".sky"));

// The ASCII pipeline draws itself, box by box, while pinned.
gsap.matchMedia().add("(min-width: 901px) and (prefers-reduced-motion: no-preference)", () => {
  gsap.timeline({ scrollTrigger: { trigger: "#flow", start: "center center", end: "+=1200", pin: true, scrub: 0.8 } })
    .from("#flow .box, #flow .arrow", { opacity: 0, y: 24, stagger: 0.5, ease: "power2.out" })
    .from("#flow .flow-notes p", { opacity: 0, stagger: 0.3 }, "<0.6");
});

// ScrollTrigger measures the page once; opening "See the numbers" or a form
// section makes it taller and would leave the pin in its old place.
let pending = 0;
new ResizeObserver(() => {
  cancelAnimationFrame(pending);
  pending = requestAnimationFrame(() => ScrollTrigger.refresh());
}).observe(document.querySelector("main"));

// ---------- predictor ----------

const parseHeadlines = (text) => (text || "").split("\n")
  .map((line) => line.match(/^\s*(\d{4}-\d{2}-\d{2})\s*[:\-]\s*(.+)$/))
  .filter(Boolean)
  .map(([, date, text]) => ({ date, text: text.trim() }));

const verdict = document.getElementById("verdict");
const ring = verdict.querySelector(".fg");

onSubmit("predict-form", async (f) => {
  const r = await api("/api/predict", {
    foundedAt: f.get("foundedAt"),
    firstFundingAt: f.get("firstFundingAt"),
    country: f.get("country") || undefined,
    categories: f.getAll("categories"),
    headlines: parseHeadlines(f.get("headlines")),
  });
  // The news model is a logistic regression with its own 0.5 threshold; the structural score uses the forest's.
  const withNews = r.financialSentiment != null;
  const p = withNews ? r.financialSentiment : r.financialOnly;
  ring.style.strokeDashoffset = 565 * (1 - p);
  verdict.querySelector(".pct span").textContent = score(p);
  verdict.querySelector(".word").textContent = word(p, withNews ? 0.5 : r.threshold);
  verdict.querySelector(".basis").textContent = withNews
    ? `With your headlines. Without them: ${score(r.financialOnly)}.`
    : "From age, funding timing, category and country.";
  const note = maturityNote(r.maturity);
  verdict.classList.toggle("faded", r.maturity?.level === "established");
  const box = verdict.querySelector(".notice");
  box.hidden = !note;
  box.querySelector("span").textContent = note || "";
}).requestSubmit();
