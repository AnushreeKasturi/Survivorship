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

// The cookie card is just for fun: the site sets no cookies. Its ASCII cookie
// is drawn live - the chips slowly turn - and it gets eaten when accepted.
const cookie = document.querySelector("[data-cookie]");
const art = cookie.querySelector("[data-cookie-art]");
const W = 15, H = 7, cx = (W - 1) / 2, cy = (H - 1) / 2;
const chips = [[0.2, 0.5], [0.55, 1.9], [0.6, 3.4], [0.5, 4.8], [0.65, 0.2]]; // [radius, angle]
const bites = [];

function drawCookie(t) {
  let out = "";
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const dx = (x - cx) / (W / 2), dy = (y - cy) / (H / 2), d = Math.hypot(dx, dy);
      const bitten = bites.some(([bx, by]) => Math.hypot((x - bx) / 2.4, (y - by) / 1.2) < 1);
      let c = " ";
      if (d <= 1 && !bitten) {
        const chip = chips.some(([r, a]) => Math.hypot(x - (cx + r * cx * Math.cos(a + t)), (y - (cy + r * cy * Math.sin(a + t))) * 2) < 1.1);
        c = chip ? "#" : d > 0.8 ? ":" : ".";
      } else if (bitten && d <= 1.15 && (x * 7 + y * 3) % 5 === 0) c = "'"; // crumbs
      out += c;
    }
    out += "\n";
  }
  art.textContent = out;
}

let dismissed = false;
try { dismissed = localStorage.getItem("cookie-card") === "dismissed"; } catch {}
if (!dismissed) {
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let spin = 0;
  drawCookie(0);
  const timer = still ? null : setInterval(() => drawCookie((spin += 0.12)), 140);
  setTimeout(() => (cookie.hidden = false), 1200);
  cookie.querySelector("[data-cookie-ok]").addEventListener("click", () => {
    try { localStorage.setItem("cookie-card", "dismissed"); } catch {}
    // three bites from the right edge, then gone
    [[13, 1], [14, 4], [11, 6]].forEach((b, i) => setTimeout(() => { bites.push(b); drawCookie(spin); }, i * 180));
    setTimeout(() => { clearInterval(timer); cookie.hidden = true; }, 3 * 180 + 250);
  }, { once: true });
}
