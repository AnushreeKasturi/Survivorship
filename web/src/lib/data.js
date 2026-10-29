// Build-time readers for the pipeline's own output - never hand-edited.
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(process.cwd(), "..");
const read = (...p) => fs.readFileSync(path.join(root, ...p), "utf8");

export function csv(file) {
  const [header, ...lines] = read("results", file).trim().split("\n");
  const cols = header.split(",");
  return lines.map((line) => Object.fromEntries(line.split(",").map((v, i) => [cols[i], isNaN(+v) ? v : +v])));
}

export const json = (...p) => JSON.parse(read(...p));

export const cv = csv("cv_summary.csv");
export const significance = csv("significance.csv");
export const weights = csv("cohort_lr_coefficients.csv");
export const model = json("results", "model_coefficients_leakfree.json");
export const caseStudies = json("results", "case_studies.json");
export const cohortSize = cv.find((r) => r.population === "cohort").n;
export const population = cv[0].n;
export const closedCount = cv[0].n_closed;

export const signed = (x, digits = 3) => (x >= 0 ? "+" : "−") + Math.abs(x).toFixed(digits);
export const pct = (x) => `${(x * 100).toFixed(1)}%`;
export const shortModel = { "Logistic Regression": "LR", "Linear SVM": "SVM" };
export const benchmark = json("results", "benchmark.json");
export const structural = (() => {
  const live = benchmark.candidates.find((c) => c.name === "rf-100");
  const base = benchmark.candidates.find((c) => c.name === benchmark.baseline);
  return { live, base };
})();

// The population funnel, read from the Phase 2 report so the site can't drift from it.
export const funnel = (() => {
  const r = read("results", "phase2_report.txt");
  const num = (re) => +r.match(re)[1].replace(/,/g, "");
  return {
    raw: num(/Rows loaded:\s*([\d,]+)/),
    excluded: num(/Excluded \(acquired \/ ipo \/ null \/ other\):\s*([\d,]+)/),
    retained: num(/Retained \(closed=0 or operating=1\)\s*:\s*([\d,]+)/),
    foundedBefore: num(/with founded_at < T\s*:\s*([\d,]+)/),
    fundedBefore: num(/with first_funding_at < T\s*:\s*([\d,]+)/),
    eligible: num(/satisfying BOTH \(eligible\)\s*:\s*([\d,]+)/),
    closed: num(/closed\s*\(0\)\s*:\s*([\d,]+)/),
    lastFundingAfter: num(/last_funding_at nulled as post-cutoff[^:]*:\s*([\d,]+)/),
  };
})();
