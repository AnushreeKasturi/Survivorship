// Plain-language verdicts shared by the predictor and the startup lists.

export const pct = (p) => `${Math.round(p * 100)}%`;

export const word = (p) => (p >= 0.65 ? "Likely to keep going" : p >= 0.5 ? "Leaning towards surviving" : p >= 0.35 ? "Uncertain" : "At risk");

const years = (m) => Math.round(m.yearsSinceFirstFunding);

/** A gentle warning when a company is older than the startups the model learned from, or null. */
export function maturityNote(m) {
  if (!m || m.level === "young") return null;
  if (m.level === "older") {
    return `${years(m)} years since its first funding round, older than most startups the model learned from. Read the score with care.`;
  }
  return `An established company, ${years(m)} years past its first round. The model is built for young startups and its score keeps falling with age, so here it says more about age than about survival.`;
}
