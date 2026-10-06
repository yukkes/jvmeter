"""Writes overhead.svg: what profiling costs, jvmeter next to JProfiler, for a reader who sees it first.

    python3 docs/figures/overhead.py

One message: exact call counts cost jvmeter 1.8x and JProfiler 292x; JProfiler's sampling is cheap but counts no calls.
The numbers are the JDK 25 column of docs/benchmarks.md (jvmeter attached, JProfiler's Full sampling, its default);
change them there and here together. The other rows (-javaagent, 3 and 5 ms, Async sampling) stay in the tables.
"""

import math
from pathlib import Path

# (panel title, what the workload is, rows of (name, what you get, profiler, x slower))
PANELS = [
    ("Worst case", "30 million calls of a tiny method (fib(35))", [
        ("jvmeter", "exact call counts + times", "jvmeter", 1.80),
        ("JProfiler Instrumentation", "exact call counts + times", "jprofiler", 292),
        ("JProfiler Sampling", "times only, no call counts", "jprofiler", 1.00),
    ]),
    ("Busy service", "4 threads that allocate and share a lock (app.Load)", [
        ("jvmeter", "exact call counts + times", "jvmeter", 1.24),
        ("JProfiler Instrumentation", "exact call counts + times", "jprofiler", 22),
        ("JProfiler Sampling", "times only, no call counts", "jprofiler", 1.14),
    ]),
]
TICKS = [1, 2, 5, 10, 20, 50, 100, 200, 500]

W, LEFT, RIGHT = 760, 216, 96  # the plot runs from LEFT to W - RIGHT
ROW, BAR, PANEL_GAP = 42, 16, 34
X0, X1 = LEFT, W - RIGHT


def x(v):
    return X0 + (X1 - X0) * math.log10(v) / math.log10(TICKS[-1])


def fmt(v):
    return "no slowdown" if v < 1.05 else (f"{v:.0f}× slower" if v >= 10 else f"{v:.1f}× slower")


out = []
y = 104
for title, workload, rows in PANELS:
    out.append(f'<text class="h" x="16" y="{y}">{title}<tspan class="sub" dx="8">{workload}</tspan></text>')
    top = y + 12
    bottom = top + ROW * len(rows)
    for t in TICKS:
        out.append(f'<line class="grid" x1="{x(t):.1f}" y1="{top}" x2="{x(t):.1f}" y2="{bottom}"/>')
    out.append(f'<line class="axis" x1="{X0}" y1="{top}" x2="{X0}" y2="{bottom}"/>')
    for i, (name, what, who, v) in enumerate(rows):
        cy = top + ROW * i + ROW / 2
        w = max(x(v) - X0, 3)  # no slowdown: the shortest bar that still shows
        r = min(4, w / 2)
        out.append(f'<text class="label" x="{LEFT - 12}" y="{cy - 2:.1f}" text-anchor="end">{name}</text>')
        out.append(f'<text class="what" x="{LEFT - 12}" y="{cy + 13:.1f}" text-anchor="end">{what}</text>')
        # flat at the baseline, 4px round at the data end
        out.append(
            f'<path class="{who}" d="M{X0},{cy - BAR / 2:.1f} h{w - r:.1f} a{r},{r} 0 0 1 {r},{r} '
            f'v{BAR - 2 * r:.1f} a{r},{r} 0 0 1 -{r},{r} h-{w - r:.1f} z">'
            f"<title>{name}, {title.lower()}: {v:g}× the run time without a profiler ({what})</title></path>"
        )
        out.append(f'<text class="value" x="{X0 + w + 8:.1f}" y="{cy + 5:.1f}">{fmt(v)}</text>')
    for t in TICKS:
        out.append(f'<text class="tick" x="{x(t):.1f}" y="{bottom + 16}" text-anchor="middle">{t}×</text>')
    y = bottom + 16 + PANEL_GAP

H = y - PANEL_GAP + 44
svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img"
  aria-labelledby="t d" font-family="Inter, system-ui, -apple-system, 'Segoe UI', sans-serif">
<title id="t">Counting every call: jvmeter makes the application 1.8 times slower, JProfiler 292 times</title>
<desc id="d">How many times slower an application runs while it is profiled, lower is better, log scale, JDK 25.
Worst case, 30 million calls of a tiny method: jvmeter 1.8 times slower with exact call counts and times; JProfiler's
Instrumentation 292 times slower with exact call counts and times; JProfiler's Sampling no slowdown, but times only and no
call counts. Busy service with 4 threads: jvmeter 1.24 times, JProfiler Instrumentation 22 times, JProfiler Sampling
1.14 times. Details in docs/benchmarks.md.</desc>
<style>
  .bg {{ fill: #fcfcfb }} .h {{ fill: #0b0b0b; font-size: 15px; font-weight: 600 }} .sub {{ fill: #52514e; font-size: 13px; font-weight: 400 }}
  .lead {{ fill: #0b0b0b; font-size: 18px; font-weight: 700 }} .label {{ fill: #0b0b0b; font-size: 14px; font-weight: 600 }}
  .what, .note, .tick {{ fill: #6b6a65; font-size: 12px }} .value {{ fill: #0b0b0b; font-size: 13px; font-weight: 600 }}
  .grid {{ stroke: #e1e0d9; stroke-width: 1 }} .axis {{ stroke: #c3c2b7; stroke-width: 1 }}
  .jvmeter {{ fill: #2a78d6 }} .jprofiler {{ fill: #eb6834 }}
  @media (prefers-color-scheme: dark) {{
    .bg {{ fill: #1a1a19 }} .h, .lead, .label, .value {{ fill: #ffffff }} .sub {{ fill: #c3c2b7 }} .what, .note, .tick {{ fill: #9a9890 }}
    .grid {{ stroke: #2c2c2a }} .axis {{ stroke: #383835 }} .jvmeter {{ fill: #3987e5 }} .jprofiler {{ fill: #d95926 }}
  }}
</style>
<rect class="bg" width="{W}" height="{H}" rx="8"/>
<text class="lead" x="16" y="32">Counting every call: jvmeter 1.8× slower, JProfiler 292× slower</text>
<text class="sub" x="16" y="54">How many times slower the application runs while it is profiled. Lower is better; the scale is logarithmic.</text>
<text class="sub" x="16" y="72">JProfiler's Sampling costs little, but it cannot count calls.</text>
{chr(10).join(out)}
<text class="note" x="16" y="{H - 14}">JDK 25 on an AMD Ryzen AI 7 350; JProfiler 15.0.4. Every number, JDK 17 and 21: docs/benchmarks.md.</text>
</svg>
"""
Path(__file__).with_name("overhead.svg").write_text(svg)
