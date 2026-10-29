import { defineConfig } from "astro/config";

// In dev, /api goes to the Scala server (sbt "runMain com.startupsurvival.web.Server").
export default defineConfig({
  // The Archive was merged into Startups; keep old links working.
  redirects: { "/archive": "/startups" },
  vite: { server: { proxy: { "/api": "http://localhost:8080" } } },
});
