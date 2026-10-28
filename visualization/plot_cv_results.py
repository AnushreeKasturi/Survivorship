"""
PHASE 19b - Visualizations for the cross-validated experiments

Reads the files written by Phase 18b's ExperimentRunner.scala:
    results/cv_summary.csv            mean/sd of each metric over 5 folds
    results/cv_folds.csv              one row per fold
    results/significance.csv          paired-bootstrap Delta AUC with 95% CI
    results/cohort_lr_coefficients.csv standardised LR coefficients

Like plot_results.py, this script contains no numbers of its own and fails
loudly if the result files are missing.

Run from the project root:
    python visualization/plot_cv_results.py

Produces (under visualization/):
    leakage_effect.png       full population: original vs leak-free financial set
    sentiment_ablation.png   news cohort: Leak-free / +Coverage / +Sentiment
    significance_forest.png  Delta AUC with 95% bootstrap CI per comparison
    lr_coefficients.png      largest standardised LR coefficients
"""

import os
import sys
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

ROOT = os.path.join(os.path.dirname(__file__), "..")
RESULTS = os.path.join(ROOT, "results")
OUTPUT_DIR = os.path.dirname(__file__)

MODEL_COLORS = {"Logistic Regression": "#3D6A8F", "Linear SVM": "#5B4B8A"}
GROUP_COLORS = {"financial": "#3D6A8F", "coverage": "#F2B33D", "tone": "#1F9E74"}


def load(name: str) -> pd.DataFrame:
    path = os.path.join(RESULTS, name)
    if not os.path.exists(path):
        sys.exit(
            f"ERROR: {path} not found.\n"
            "Run Phase 18b first:\n"
            "  cd ~/startup-survival-prediction/scala\n"
            '  sbt "runMain com.startupsurvival.ml.ExperimentRunner"'
        )
    return pd.read_csv(path)


def grouped_bars(ax, sub: pd.DataFrame, sets: list, metric: str, label: str) -> None:
    models = list(MODEL_COLORS)
    x = np.arange(len(sets))
    width = 0.38
    for i, m in enumerate(models):
        rows = sub[sub["model"] == m].set_index("feature_set").loc[sets]
        means, sds = rows[f"{metric}_mean"], rows[f"{metric}_sd"]
        bars = ax.bar(x + (i - 0.5) * width, means, width, yerr=sds, capsize=4,
                      color=MODEL_COLORS[m], label=m)
        for b, v in zip(bars, means):
            ax.text(b.get_x() + b.get_width() / 2, v + 0.02, f"{v:.3f}", ha="center", fontsize=8)
    ax.set_xticks(x)
    ax.set_xticklabels(sets, fontsize=9)
    ax.set_ylabel(label + " (mean ± sd, 5 folds)")
    ax.spines[["top", "right"]].set_visible(False)


def plot_leakage(summary: pd.DataFrame) -> None:
    sub = summary[summary["population"] == "full"]
    sets = ["Financial (original)", "Financial (leak-free)"]
    fig, ax = plt.subplots(figsize=(7, 4.5))
    grouped_bars(ax, sub, sets, "auc", "AUC-ROC")
    ax.axhline(0.5, color="grey", lw=1, ls="--")
    ax.text(1.45, 0.51, "chance", color="grey", fontsize=8, ha="right")
    ax.set_ylim(0.4, 0.85)
    ax.set_title(f"Removing post-cutoff information (all {int(sub['n'].iloc[0]):,} startups)")
    ax.legend(frameon=False)
    fig.tight_layout()
    fig.savefig(os.path.join(OUTPUT_DIR, "leakage_effect.png"), dpi=150)
    plt.close(fig)


def plot_ablation(summary: pd.DataFrame) -> None:
    sub = summary[summary["population"] == "cohort"]
    sets = ["Leak-free", "Leak-free + Coverage", "Leak-free + Sentiment"]
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    grouped_bars(axes[0], sub, sets, "auc", "AUC-ROC")
    axes[0].axhline(0.5, color="grey", lw=1, ls="--")
    axes[0].set_ylim(0.4, 0.9)
    axes[0].set_title("Ranking quality (AUC-ROC)")
    grouped_bars(axes[1], sub, sets, "pr_auc_closed", "PR-AUC, closed class")
    prevalence = sub["n_closed"].iloc[0] / sub["n"].iloc[0]
    axes[1].axhline(prevalence, color="grey", lw=1, ls="--")
    axes[1].set_ylim(0, 1)
    axes[1].set_title(f"Finding closures (PR-AUC; chance = {prevalence:.2f})")
    axes[0].legend(frameon=False, loc="upper left")
    fig.suptitle(f"News cohort ({int(sub['n'].iloc[0]):,} startups): does news volume or tone add signal?")
    fig.tight_layout()
    fig.savefig(os.path.join(OUTPUT_DIR, "sentiment_ablation.png"), dpi=150)
    plt.close(fig)


def plot_forest(sig: pd.DataFrame) -> None:
    sig = sig.copy()
    sig["label"] = sig["feature_set_a"] + "  →  " + sig["feature_set_b"] + "\n(" + sig["model"] + ")"
    sig = sig.iloc[::-1].reset_index(drop=True)
    fig, ax = plt.subplots(figsize=(9, 0.6 * len(sig) + 1.2))
    for i, r in sig.iterrows():
        significant = r["ci_low"] > 0 or r["ci_high"] < 0
        color = MODEL_COLORS[r["model"]] if significant else "#8A97A8"
        ax.plot([r["ci_low"], r["ci_high"]], [i, i], color=color, lw=2)
        ax.plot(r["delta_auc"], i, "o", color=color)
        ax.text(max(r["ci_high"], 0) + 0.004, i, f"{r['delta_auc']:+.3f}  (" + ("p<0.001" if r['p_value'] < 0.001 else f"p={r['p_value']:.3f}") + ")",
                va="center", fontsize=8)
    ax.axvline(0, color="black", lw=1)
    ax.set_yticks(range(len(sig)))
    ax.set_yticklabels(sig["label"], fontsize=8)
    ax.set_xlabel("Δ AUC-ROC (B − A), paired bootstrap 95% CI")
    ax.set_title("Which feature groups change predictive performance?\n(grey = interval includes 0)")
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(os.path.join(OUTPUT_DIR, "significance_forest.png"), dpi=150)
    plt.close(fig)


def plot_coefficients(coef: pd.DataFrame, top: int = 15) -> None:
    coef = coef.reindex(coef["standardised_coefficient"].abs().sort_values(ascending=False).index).head(top)
    coef = coef.iloc[::-1]
    coverage = {"log1pNewsCount", "hasAnyCoverage"}
    tone = {"positiveNewsCount", "negativeNewsCount", "neutralNewsCount", "positiveRatio", "negativeRatio",
            "averageSentiment", "recentSentiment", "sentimentTrend", "sentimentVolatility", "newsVolumeTrend"}
    colors = [GROUP_COLORS["coverage"] if f in coverage else GROUP_COLORS["tone"] if f in tone
              else GROUP_COLORS["financial"] for f in coef["feature"]]
    fig, ax = plt.subplots(figsize=(8, 0.35 * len(coef) + 1.2))
    ax.barh(coef["feature"], coef["standardised_coefficient"], color=colors)
    ax.axvline(0, color="black", lw=1)
    ax.set_xlabel("Standardised LR coefficient (+ = more likely operating)")
    ax.set_title("Largest effects, Logistic Regression on the news cohort")
    handles = [plt.Rectangle((0, 0), 1, 1, color=c) for c in GROUP_COLORS.values()]
    ax.legend(handles, ["financial / structured", "news coverage", "news tone"], frameon=False, fontsize=8)
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(os.path.join(OUTPUT_DIR, "lr_coefficients.png"), dpi=150)
    plt.close(fig)


def main() -> None:
    summary = load("cv_summary.csv")
    sig = load("significance.csv")
    coef = load("cohort_lr_coefficients.csv")
    print(summary.to_string(index=False))
    print()
    print(sig.to_string(index=False))

    plot_leakage(summary)
    plot_ablation(summary)
    plot_forest(sig)
    plot_coefficients(coef)
    print(f"\nWrote 4 PNG charts to {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
