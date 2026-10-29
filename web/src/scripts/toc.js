// Highlight the step being read in a page's sticky index.
const links = new Map([...document.querySelectorAll(".toc a")].map((a) => [a.hash.slice(1), a]));
const observer = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) {
    links.forEach((a) => a.classList.remove("on"));
    links.get(e.target.id)?.classList.add("on");
  }
}, { rootMargin: "-30% 0px -60% 0px" });
document.querySelectorAll(".step").forEach((s) => observer.observe(s));
