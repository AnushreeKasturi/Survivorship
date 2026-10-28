import { api, ago } from "./api.js";

const nav = document.querySelector(".nav");

// Light by default; the toggle flips to dark and remembers the choice.
const toggle = document.querySelector("[data-theme-toggle]");
function setTheme(dark) {
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  toggle.setAttribute("aria-pressed", String(dark));
  toggle.setAttribute("aria-label", dark ? "Light theme" : "Dark theme");
  document.querySelector('meta[name="theme-color"]').content = getComputedStyle(document.documentElement).getPropertyValue("--bg").trim();
  dispatchEvent(new Event("themechange"));
}
setTheme(document.documentElement.dataset.theme === "dark");
toggle.addEventListener("click", () => {
  const dark = document.documentElement.dataset.theme !== "dark";
  setTheme(dark);
  try { localStorage.setItem("theme", dark ? "dark" : "light"); } catch {}
});
addEventListener("scroll", () => nav.classList.toggle("scrolled", scrollY > 8), { passive: true });

// The nav badge shows when the scheduled watchlist last ran; it stays hidden without the server.
const badge = document.querySelector("[data-live]");
api("/api/watchlist")
  .then((w) => {
    badge.querySelector("[data-live-text]").textContent = w.lastRun ? `Checked ${ago(w.lastRun)}` : "Checking now";
    badge.hidden = false;
  })
  .catch(() => {});
