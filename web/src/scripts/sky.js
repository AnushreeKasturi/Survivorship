// The hero's ASCII sky: glyphs on a monospace grid, brightness from slow
// drifting waves, coloured by the theme's gradient. One glyph in eight is a
// startup that closed, drawn as a faint "x" (13% of the training data closed).

const RAMP = " .,:;~+*";

export function sky(canvas) {
  const ctx = canvas.getContext("2d");
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let w, h, cols, rows, cw, ch, gradient, seeds;

  function layout() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    w = canvas.clientWidth;
    h = canvas.clientHeight;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const size = w < 600 ? 11 : 14;
    ctx.font = `${size}px "Geist Mono", monospace`;
    ctx.textBaseline = "top";
    cw = size * 0.62;
    ch = size * 1.25;
    cols = Math.ceil(w / cw);
    rows = Math.ceil(h / ch);
    const css = getComputedStyle(document.documentElement);
    gradient = ctx.createLinearGradient(0, 0, w, h);
    ["--g1", "--g2", "--g3"].forEach((v, i) => gradient.addColorStop(i / 2, css.getPropertyValue(v).trim()));
    let s = 2013;
    seeds = Float32Array.from({ length: cols * rows }, () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32));
  }

  function draw(t) {
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = gradient;
    for (let y = 0; y < rows; y++) {
      // brighter towards a wavy horizon two-thirds of the way down
      const horizon = rows * 0.66 + Math.sin(y * 0.3 + t * 0.2) * 0.5;
      for (let x = 0; x < cols; x++) {
        const v = 0.5 + 0.5 * Math.sin(x * 0.11 + t * 0.35) * Math.cos(y * 0.17 - t * 0.25)
          + 0.35 * Math.sin((x + y) * 0.05 + t * 0.15);
        const fade = Math.exp(-Math.abs(y - horizon + Math.sin(x * 0.06 + t * 0.3) * 4) / (rows * 0.28));
        const level = Math.max(0, Math.min(0.999, v * fade));
        const seed = seeds[y * cols + x];
        const glyph = seed < 0.13 && level > 0.35 ? "x" : RAMP[Math.floor(level * RAMP.length)];
        if (glyph === " ") continue;
        ctx.globalAlpha = 0.15 + level * 0.65;
        ctx.fillText(glyph, x * cw, y * ch);
      }
    }
    ctx.globalAlpha = 1;
  }

  layout();
  let visible = true;
  new IntersectionObserver(([e]) => (visible = e.isIntersecting)).observe(canvas);
  for (const event of ["resize", "themechange"]) addEventListener(event, () => { layout(); draw(performance.now() / 1000); });
  if (reduced) return draw(0);
  let last = 0;
  (function frame(now) {
    if (visible && !document.hidden && now - last > 70) { // ~14 fps is plenty for a slow drift
      draw(now / 1000);
      last = now;
    }
    requestAnimationFrame(frame);
  })(0);
}
