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
