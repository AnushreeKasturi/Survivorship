import { api, ago } from "./api.js";

// The nav badge shows when the scheduled watchlist last ran; it stays hidden without the server.
const badge = document.querySelector("[data-live]");
api("/api/watchlist")
  .then((w) => {
    badge.querySelector("[data-live-text]").textContent =
      w.lastRun ? `Watchlist checked ${ago(w.lastRun)}` : "Watchlist checking now";
    badge.hidden = false;
  })
  .catch(() => {});
