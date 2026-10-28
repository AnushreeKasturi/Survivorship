import { api, ago } from "./api.js";

const nav = document.querySelector(".nav");
addEventListener("scroll", () => nav.classList.toggle("scrolled", scrollY > 8), { passive: true });

// The nav badge shows when the scheduled watchlist last ran; it stays hidden without the server.
const badge = document.querySelector("[data-live]");
api("/api/watchlist")
  .then((w) => {
    badge.querySelector("[data-live-text]").textContent = w.lastRun ? `Checked ${ago(w.lastRun)}` : "Checking now";
    badge.hidden = false;
  })
  .catch(() => {});
