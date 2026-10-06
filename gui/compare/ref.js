// Reference screenshots of demo/prototype.html, one per state and theme, plus the text boxes of each
// (<outdir>/<WxH>/ref-<state>-<theme>.png / .txt), all pages at once. Usage: node ref.js <prototype.html> <outdir> WxH...
// CHROME=/path/to/chrome overrides the browser Playwright installed.
const { chromium } = require("playwright");
(async () => {
  const [file, outdir, ...sizes] = process.argv.slice(2);
  // --font-render-hinting=none: fractional glyph positions (as on Windows / macOS and in the GUI); by default Linux
  // Chromium rounds the advances of web fonts to whole pixels
  const browser = await chromium.launch({ args: ["--font-render-hinting=none"], ...(process.env.CHROME ? { executablePath: process.env.CHROME } : {}) });
  const jobs = [];
  for (const size of sizes) for (const scheme of ["light", "dark"]) for (const st of ["overview", "cpu", "cpusel", "tree", "heap", "gc", "threads", "empty"]) jobs.push(async () => {
      const [W, H] = size.split("x").map(Number), out = `${outdir}/${size}`;
      require("fs").mkdirSync(out, { recursive: true });
      const page = await browser.newPage({ viewport: { width: W, height: H }, colorScheme: scheme });
      // #sample: start with the sample snapshot instead of the Start Center, as Shot does; empty: the window behind the Start Center
      await page.goto("file://" + require("path").resolve(file) + (st === "empty" ? "" : "#sample"));
      if (st === "empty") await page.evaluate(() => document.getElementById("connect").close());
      // the page loads Inter's stylesheet without blocking: wait for its faces, then for the three weights
      await page.waitForFunction(() => [...document.fonts].some(f => f.family === "Inter"), null, { timeout: 15000 });
      await page.evaluate(() => Promise.all([400, 500, 700].map(w => document.fonts.load(`${w} 12px Inter`))).then(() => document.fonts.ready));
      await page.waitForTimeout(300);
      const view = { cpu: "cpu", cpusel: "cpu", tree: "cpu", heap: "memory", gc: "memory", threads: "threads" }[st];
      if (view) await page.click(`.rail button[data-view="${view}"]`);
      if (st === "tree") await page.click('button[data-cputab="tree"]');
      if (st === "heap") await page.click('button[data-memtab="heap"]');
      if (st === "cpusel") await page.click("tbody tr");
      await page.mouse.move(0, H - 1);
      await page.evaluate(() => document.activeElement && document.activeElement.blur());
      await page.waitForTimeout(150);
      await page.screenshot({ path: `${out}/ref-${st}-${scheme}.png` });
      // text boxes (one per rendered line of each text node): the comparison excuses glyph differences inside them
      const rects = await page.evaluate(() => {
        const out = [], r = document.createRange();
        // clip to ancestors that hide overflow (ellipsized names), as the box is the visible part of the text
        const clipOf = e => { let l = -1e9, t = -1e9, r = 1e9, b = 1e9; for (let a = e; a && a !== document.body; a = a.parentElement) { const cs = getComputedStyle(a); if (cs.overflowX !== "visible" || cs.overflowY !== "visible") { const q = a.getBoundingClientRect(); l = Math.max(l, q.left); t = Math.max(t, q.top); r = Math.min(r, q.right); b = Math.min(b, q.bottom); } } return { l, t, r, b }; };
        let clip = { l: -1e9, t: -1e9, r: 1e9, b: 1e9 };
        const add = (b0, t, lines = 1) => { const b = { left: Math.max(b0.left, clip.l), top: Math.max(b0.top, clip.t), right: Math.min(b0.right, clip.r), bottom: Math.min(b0.bottom, clip.b) }; b.width = b.right - b.left; b.height = b.bottom - b.top; if (b.width > 0 && b.height > 0 && b.right > 0 && b.bottom > 0 && b.left < innerWidth && b.top < innerHeight) out.push([Math.floor(b.left), Math.floor(b.top), Math.ceil(b.right), Math.ceil(b.bottom)].join(" ") + " " + lines + " " + Math.ceil(b0.bottom) + "\t" + t.replace(/\s+/g, " ").trim()); };
        const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let n; (n = w.nextNode());) {
          if (!n.textContent.trim()) continue;
          const e = n.parentElement; clip = clipOf(e);
          if (e.closest("svg")) { if (e.tagName === "text" || e.tagName === "tspan") add(e.getBoundingClientRect(), n.textContent); continue; }
          if (getComputedStyle(e).visibility === "hidden" || e.closest(".sr")) continue;
          r.selectNodeContents(n);
          // one box per line: a clipped ellipsis can split a line into several rects
          const lines = new Map();
          for (const b of r.getClientRects()) { const k = Math.round(b.top), u = lines.get(k); lines.set(k, u ? { left: Math.min(u.left, b.left), top: u.top, right: Math.max(u.right, b.right), bottom: Math.max(u.bottom, b.bottom) } : { left: b.left, top: b.top, right: b.right, bottom: b.bottom }); }
          for (const b of lines.values()) add(b, n.textContent, lines.size);
        }
        // placeholders are text too (not in the DOM tree): the input's box, clipped to the placeholder's width
        for (const i of document.querySelectorAll("input[placeholder]")) {
          if (i.value) continue;
          const q = i.getBoundingClientRect(), c = document.createElement("canvas").getContext("2d"), cs = getComputedStyle(i);
          c.font = `${cs.fontSize} ${cs.fontFamily}`;
          clip = { l: -1e9, t: -1e9, r: 1e9, b: 1e9 };
          add({ left: q.left, top: q.top, right: q.left + c.measureText(i.placeholder).width, bottom: q.bottom, width: 1, height: 1 }, i.placeholder);
        }
        return out.join("\n");
      });
      require("fs").writeFileSync(`${out}/ref-${st}-${scheme}.txt`, rects + "\n");
      await page.close();
  });
  await Promise.all(jobs.map(j => j()));
  await browser.close();
})();
