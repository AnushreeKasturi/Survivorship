// Shared by every page that talks to web/Server.scala.

export async function api(path, body) {
  const res = await fetch(path, body === undefined ? {} : {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  // A static host answers /api with HTML or a 404 page: treat that like no server at all.
  if (!res.headers.get("content-type")?.includes("json")) throw new TypeError("API unavailable");
  const data = await res.json();
  if (!res.ok) throw new Error((data.error || `HTTP ${res.status}`).replace(/^requirement failed: /, ""));
  return data;
}

export function markOffline() {
  document.querySelectorAll(".offline").forEach((el) => (el.hidden = false));
}

export function onSubmit(id, handler) {
  const form = document.getElementById(id);
  const error = form.querySelector(".error");
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    form.classList.add("busy");
    if (error) error.textContent = "";
    try {
      await handler(new FormData(form));
    } catch (err) {
      if (err instanceof TypeError) markOffline(); // fetch itself failed: no server
      else if (error) error.textContent = err.message;
      else throw err;
    } finally {
      form.classList.remove("busy");
    }
  });
  return form;
}

export const pct = (p) => `${(p * 100).toFixed(1)}%`;

/** Relative time in plain words, e.g. "3 hours ago". */
export function ago(iso) {
  const s = (Date.now() - Date.parse(iso)) / 1000;
  const fmt = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  if (Math.abs(s) < 3600) return fmt.format(-Math.round(s / 60), "minute");
  if (Math.abs(s) < 86400) return fmt.format(-Math.round(s / 3600), "hour");
  return fmt.format(-Math.round(s / 86400), "day");
}
