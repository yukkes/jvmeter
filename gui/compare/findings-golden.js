// Writes core/src/test/resources/findings-golden.json: "Where to look" and the GC rules of demo/prototype.html,
// on the sample and on made-up snapshots that set off every rule, for FindingsTest (the Java port must agree).
// Run: node findings-golden.js (in this directory, after npm install && npx playwright install chromium)
const { chromium } = require("playwright");
const path = require("path");

const events = [];
for (let i = 0; i <= 10; i++) {
  events.push({ t: i * 10, name: "Young", cause: "G1 Evacuation Pause", pauseMs: 5 + i, beforeMB: 950, afterMB: 850, oldAfterMB: 100 + 30 * i, metaspaceMB: 50 + 5 * i });
  if (i === 5) events.push({ t: 55, name: "Full", cause: "Allocation Failure", pauseMs: 240, beforeMB: 990, afterMB: 880, oldAfterMB: 860, metaspaceMB: 75 });
}
const tel = { t: [], n: [], cpu: [], heap: [], threads: [] };
for (let s = 1; s <= 120; s++) { tel.t.push(s); tel.n.push(1); tel.cpu.push(95); tel.heap.push(500); tel.threads.push(20 + Math.floor(s / 3)); }
const hist = { t: [], classes: ["app.Session", "byte[]"], count: [], bytes: [] };
for (let k = 1; k <= 10; k++) { hist.t.push(k * 30); hist.count.push([1000 + 500 * k, k % 2 ? 9000 : 7000]); hist.bytes.push([480000 + 240000 * k, k % 2 ? 900000 : 700000]); }
const node = (name, self, children = []) => ({ name, self, calls: 0, children });
const full = {
  target: { name: "app.Main", pid: 1, jvm: "OpenJDK 21", include: "app" },
  startedAt: "2026-10-04T10:00:00+09:00", durationSec: 120,
  cpu: { tree: node("All threads", 0, [
    node("java.lang.Thread.run", 0, [node("app.Server.handle", 100, [
      node("app.Json.write", 500, [node("com.fasterxml.jackson.Gen.writeString", 3000)]), node("java.util.HashMap.get", 800)])]),
    node("app.Batch.run", 2000)]),
    methods: [{ name: "app.Json.write", calls: 1234 }, { name: "app.Server.handle", calls: 99 }] },
  memory: { heapMaxMB: 1000, classes: [{ name: "app.Session", count: 6000, bytes: 2880000 }, { name: "byte[]", count: 7000, bytes: 700000 }], history: hist,
    allocations: [{ site: "app.Json.write", cls: "byte[]", mb: 2400.5 }, { site: "app.Server.handle", cls: "java.util.HashMap$Node", mb: 1800 }, { site: "app.Json.write", cls: "char[]", mb: 700.2 }] },
  telemetry: tel,
  threads: [{ name: "worker-1", segs: [[0, 120, "run"]], stack: ["app.Server.handle"], sec: { run: 60, wait: 0, block: 30, io: 30 } },
            { name: "worker-2", segs: [[0, 120, "run"]], stack: ["app.Server.handle"], sec: { run: 110, wait: 0, block: 10, io: 0 } }],
  waits: [
    { state: "block", site: "app.Cache.get", lock: "java.util.concurrent.locks.ReentrantLock$NonfairSync@1f", owner: "worker-2", top: "jdk.internal.misc.Unsafe.park", sec: 12.3, threads: ["worker-1", "worker-3"] },
    { state: "block", site: "app.Cache.get", lock: "java.util.concurrent.locks.ReentrantLock$NonfairSync@1f", owner: "worker-3", top: "jdk.internal.misc.Unsafe.park", sec: 4.1, threads: ["worker-2"] },
    { state: "io", site: "app.Db.query", top: "sun.nio.ch.NioSocketImpl.read", sec: 20, threads: ["worker-1"] },
    { state: "io", site: "java.lang.Thread.run", top: "sun.nio.ch.NioSocketImpl.accept", sec: 118, threads: ["acceptor"] }],
  gc: { collector: "G1", heapMaxMB: 1000, events },
};
// an older snapshot: no include, no waits, one class histogram, a short and healthy GC log
const old = { ...full, target: { name: "app.Main", pid: 1, jvm: "OpenJDK 21" }, waits: undefined, telemetry: undefined,
  memory: { heapMaxMB: 1000, classes: full.memory.classes },
  gc: { collector: "G1", heapMaxMB: 1000, events: [{ t: 5, name: "Young", cause: "G1 Evacuation Pause", pauseMs: 3, beforeMB: 300, afterMB: 100, oldAfterMB: 50, metaspaceMB: 40 }] } };

(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  p.on("pageerror", e => { console.error(e.message); process.exit(1); });
  await p.goto("file://" + path.resolve(__dirname, "../../demo/prototype.html") + "#sample");
  const out = await p.evaluate(cases => {
    const pick = f => ({ sev: f.sev, icon: f.icon, line: f.line, small: f.small, link: f.link.label });
    const r = { sample: findings().map(pick), cases: [] };
    for (const s of cases) {
      load(JSON.parse(JSON.stringify(s)));
      r.cases.push({ snapshot: s, findings: findings().map(pick), problems: analyzeGc(gc, state.elapsed).problems.map(x => ({ sev: x.sev, title: x.title, detail: x.detail })) });
    }
    return r;
  }, [full, old]);
  require("fs").writeFileSync(path.resolve(__dirname, "../../core/src/test/resources/findings-golden.json"), JSON.stringify(out, null, 1) + "\n");
  await b.close();
})();
