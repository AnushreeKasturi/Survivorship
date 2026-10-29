// Plain-language verdicts shared by the predictor and the startup lists.

// Scores are shown out of 100, not as percentages: training weighted closures as much as
// survivals, so a score is a ranking, not a probability. pct is for CSS positions only.
export const pct = (p) => `${Math.round(p * 100)}%`;
export const score = (p) => String(Math.round(p * 100));

/** Bands around the model's decision threshold; the forest's scores sit close to it, so the bands are narrow. */
export const word = (p, t = 0.5) =>
  p >= t + 0.05 ? "Likely to keep going" : p >= t ? "Leaning towards surviving" : p >= t - 0.05 ? "Leaning towards closing" : "At risk";

const years = (m) => Math.round(m.yearsSinceFirstFunding);

/** A gentle warning when a company is older than the startups the model learned from, or null. */
export function maturityNote(m) {
  if (!m || m.level === "young") return null;
  if (m.level === "older") {
    return `${years(m)} years since its first funding round, older than most startups the model learned from. Read the score with care.`;
  }
  return `An established company, ${years(m)} years past its first round. The model is built for young startups and its score keeps falling with age, so here it says more about age than about survival.`;
}
