"""Writes the README's explanatory figures, each in Japanese and English: NAME.ja.svg and NAME.en.svg.

    python3 docs/figures/explain.py

One layout per figure, with the text of both languages side by side (L("日本語", "English")), so the two stay the same.
The numbers come from docs/benchmarks.md and docs/design.md (fib(35), JDK 25); change them there and here together.
"""

import math
from html import escape
from pathlib import Path

W = 960
FONT = "Inter, 'Hiragino Sans', 'Noto Sans JP', 'Yu Gothic UI', Meiryo, system-ui, sans-serif"
STYLE = """
  .bg { fill: #fcfcfb } .box { fill: #ffffff; stroke: #e1e0d9 } .inner { fill: #f5f5f3; stroke: #e1e0d9 }
  .hl { fill: #e6f0fb } .chip { fill: #ffffff; stroke: #c3c2b7 } .term { fill: #24292f }
  .lead { fill: #0b0b0b; font-size: 20px; font-weight: 700 } .h { fill: #0b0b0b; font-size: 15px; font-weight: 700 }
  .label { fill: #0b0b0b; font-size: 13px; font-weight: 600 } .sub { fill: #52514e; font-size: 13px }
  .what { fill: #6b6a65; font-size: 12px } .value { fill: #0b0b0b; font-size: 13px; font-weight: 700 }
  .code { fill: #0b0b0b; font-size: 12px; font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace }
  .dim { fill: #9a9890 } .termtext { fill: #e6edf3; font-size: 11.5px; font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace }
  .badge { fill: #2a78d6 } .num { fill: #ffffff; font-size: 12px; font-weight: 700 }
  .arrow { stroke: #8a8983; stroke-width: 2; fill: none } .head { fill: #8a8983 } .tick { stroke: #2a78d6; stroke-width: 2 }
  .sample { stroke: #eb6834; stroke-width: 1.5; stroke-dasharray: 4 3 } .stack { fill: #eb6834 }
  .jvmeter { fill: #2a78d6 } .jprofiler { fill: #eb6834 } .other { fill: #b5b4ab } .axis { stroke: #c3c2b7 }
  .bad { fill: #d4382c } .warn { fill: #e0a100 }
  @media (prefers-color-scheme: dark) {
    .bg { fill: #1a1a19 } .box { fill: #222220; stroke: #383835 } .inner { fill: #2a2a28; stroke: #383835 }
    .hl { fill: #1d3350 } .chip { fill: #222220; stroke: #4a4945 } .term { fill: #0d1117 }
    .lead, .h, .label, .code, .value { fill: #ffffff } .sub { fill: #c3c2b7 } .what { fill: #9a9890 } .dim { fill: #6b6a65 }
    .badge, .jvmeter { fill: #3987e5 } .tick { stroke: #3987e5 } .jprofiler, .stack { fill: #d95926 } .sample { stroke: #d95926 }
    .other { fill: #5c5b55 } .axis { stroke: #4a4945 }
  }
"""


class Fig:
    def __init__(self, lang):
        self.lang, self.out = lang, []

    def L(self, ja, en):
        return ja if self.lang == "ja" else en

    def add(self, s):
        self.out.append(s)

    def text(self, x, y, s, cls, anchor=None):
        a = f' text-anchor="{anchor}"' if anchor else ""
        self.add(f'<text class="{cls}" x="{x}" y="{y}"{a}>{escape(s)}</text>')

    def rect(self, x, y, w, h, cls, rx=6):
        self.add(f'<rect class="{cls}" x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}"/>')

    def badge(self, x, y, n):
        self.add(f'<circle class="badge" cx="{x}" cy="{y}" r="10"/>')
        self.text(x, y + 4, str(n), "num", "middle")

    def arrow(self, x1, y1, x2, y2):
        self.add(f'<line class="arrow" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" marker-end="url(#a)"/>')

    def chip(self, x, y, w, s):
        self.rect(x, y, w, 26, "chip", 13)
        self.text(x + w / 2, y + 17, s, "what", "middle")

    def head(self, title, sub):
        self.text(16, 36, title, "lead")
        for i, s in enumerate(sub):
            self.text(16, 60 + 18 * i, s, "sub")

    def svg(self, h, title, desc):
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" viewBox="0 0 {W} {h}" role="img"'
            f' aria-labelledby="t d" font-family="{FONT}">\n'
            f'<title id="t">{escape(title)}</title>\n<desc id="d">{escape(desc)}</desc>\n<style>{STYLE}</style>\n'
            '<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8"'
            ' orient="auto"><path class="head" d="M0,0 L10,5 L0,10 z"/></marker></defs>\n'
            f'<rect class="bg" width="{W}" height="{h}" rx="8"/>\n' + "\n".join(self.out) + "\n</svg>\n"
        )


def bars(f, y, rows, label_x=208, x0=220, x1=860, top=500):
    """horizontal bars on a log scale: rows of (label, class, x slower[, "about"])"""
    f.add(f'<line class="axis" x1="{x0}" y1="{y - 6}" x2="{x0}" y2="{y + 32 * len(rows) - 10}"/>')
    for i, (label, cls, v, *about) in enumerate(rows):
        w = max(4, (x1 - x0) * math.log10(v) / math.log10(top))
        yy = y + 32 * i
        f.text(label_x, yy + 12, label, "label", "end")
        f.rect(x0, yy, round(w), 16, cls, 3)
        f.text(x0 + round(w) + 8, yy + 13, f.L(f"{'約 ' if about else ''}{v:g} 倍", f"{'~' if about else ''}{v:g}×"), "value")


def overview(f):
    L = f.L
    f.head(L("jvmeter のしくみと特長", "How jvmeter works"),
           [L("動いている JVM にあとから接続し、正確な呼び出し回数と CPU 時間を低い負荷で集めて、人も LLM も読める形で保存します。",
              "Attach to a running JVM, collect exact call counts and CPU time at low cost, and keep them in a file people and LLMs can read.")])
    # the JVM
    f.rect(16, 84, 300, 300, "box", 8)
    f.text(32, 112, L("プロファイル対象の JVM", "The JVM you profile"), "h")
    f.badge(42, 134, 1)
    f.text(60, 139, L("再起動不要：動いたままアタッチ", "No restart: attach while it runs"), "sub")
    f.rect(32, 156, 268, 148, "inner")
    f.text(48, 180, L("jvmeter エージェント", "jvmeter agent"), "label")
    f.badge(58, 206, 2)
    f.text(76, 211, L("呼び出し回数", "Call counts"), "label")
    f.text(76, 229, L("メソッドの入口に置いたカウンタで", "a counter at each method entry:"), "what")
    f.text(76, 245, L("すべての呼び出しを正確に数える", "every call, exactly"), "what")
    f.text(76, 271, L("CPU 時間・GC・スレッド", "CPU time, GC, threads"), "label")
    f.text(76, 289, L("JFR で 1 ms ごとにサンプリング", "JFR, sampled every 1 ms"), "what")
    f.badge(42, 330, 3)
    f.text(60, 335, L("動いている場所を問わず同じ手順", "The same steps wherever it runs"), "sub")
    f.chip(32, 346, 68, L("ローカル", "Local"))
    f.chip(108, 346, 68, L("SSH 先", "SSH"))
    f.chip(184, 346, 116, "Kubernetes Pod")
    # JVM -> GUI
    f.text(356, 210, L("手元の ssh /", "your ssh /"), "what", "middle")
    f.text(356, 226, L("kubectl 経由", "kubectl"), "what", "middle")
    f.arrow(320, 240, 390, 240)
    f.text(356, 262, "127.0.0.1", "what", "middle")
    f.text(356, 278, L("のみ", "only,"), "what", "middle")
    f.text(356, 294, L("トークン認証", "token auth"), "what", "middle")
    # the GUI
    f.rect(396, 84, 240, 300, "box", 8)
    f.text(412, 112, "jvmeter GUI", "h")
    f.text(412, 132, L("約 2 MB の jar 1 つ・Java 17 以上", "One 2 MB jar, Java 17+"), "what")
    views = [(4, L("見るべき場所", "Where to look"), L("ボトルネックを深刻度順に", "bottlenecks, worst first")),
             (None, "CPU", L("遅いメソッドと呼び出し元", "hot spots, call tree")),
             (5, L("GC 解析", "GC analysis"), L("GC ログ不要（JFR から計算）", "no GC log needed (from JFR)")),
             (None, L("スレッド", "Threads"), L("ロック待ちと I/O 待ちを区別", "lock waits apart from I/O"))]
    for i, (n, name, what) in enumerate(views):
        y = 148 + 58 * i
        f.rect(412, y, 208, 50, "inner")
        if n:
            f.badge(432, y + 25, n)
        f.text(450, y + 20, name, "label")
        f.text(450, y + 38, what, "what")
    # GUI -> snapshot
    f.text(676, 226, L("保存", "save"), "what", "middle")
    f.arrow(640, 240, 710, 240)
    # the snapshot
    f.rect(716, 84, 228, 300, "box", 8)
    f.text(732, 112, L("スナップショット", "Snapshot"), "h")
    f.text(732, 132, "jvmeter-PID.json.gz", "code")
    f.rect(732, 148, 196, 118, "inner")
    f.rect(736, 168, 188, 40, "hl", 3)
    for y, s, x in [(164, "{", 744), (184, '"about": [ … ],', 752), (202, '"summary": { … },', 752),
                    (224, '"cpu": { … },', 752), (242, '"gc": { … }, …', 752), (260, "}", 744)]:
        f.text(x, y, s, "code")
    f.badge(742, 290, 6)
    f.text(760, 288, L("先頭の説明と要約だけで", "the meaning and a summary"), "what")
    f.text(760, 304, L("ボトルネックがわかる", "come first"), "what")
    f.text(732, 334, L("読み手", "Read by"), "what")
    for i, s in enumerate([L("人", "people"), "GUI", "LLM"]):
        f.chip(732 + 70 * i, 346, 56, s)
    return f.svg(400, L("jvmeter のしくみと特長", "How jvmeter works"),
                 L("動いている JVM（ローカル、SSH 先、Kubernetes の Pod）に再起動せずにエージェントをアタッチする。"
                   "エージェントは呼び出し回数をメソッドの入口に置いたカウンタで正確に数え、CPU 時間・GC・スレッドを JFR で 1 ms ごとにサンプリングする。"
                   "GUI は手元の ssh / kubectl 経由でつながり、見るべき場所、CPU、GC 解析、スレッドを表示する。"
                   "結果は about と summary を先頭に置いたスナップショットに保存され、人・GUI・LLM が読める。",
                   "The agent is attached without a restart to a running JVM, local, over SSH or in a Kubernetes pod. "
                   "It counts calls exactly with a counter at each method entry and samples CPU time, GC and threads with JFR every 1 ms. "
                   "The GUI connects through your ssh or kubectl and shows Where to look, CPU, GC analysis and Threads. "
                   "The result is saved as a snapshot with about and summary first, for people, the GUI and LLMs."))


def counting(f):
    L = f.L
    f.head(L("呼び出しは数えるだけ、時間はサンプリングで", "Count every call, sample the time"),
           [L("呼び出しのたびに時刻を取得する方式では、小さなメソッドほど速度が大きく落ちます。jvmeter は呼び出しごとにカウンタを 1 増やすだけで、",
              "Reading the clock on every call makes small methods slow. jvmeter only adds one to a counter on each call,"),
            L("時間は JFR が 1 ms ごとに記録するスタックから見積もります。",
              "and estimates time from the stacks JFR records every 1 ms.")])
    f.rect(16, 100, 928, 226, "box", 8)
    f.text(32, 126, L("あるスレッドの 5 ms 間", "5 ms of one thread"), "h")
    x0, x1, y = 200, 920, 196
    f.add(f'<line class="axis" x1="{x0}" y1="{y}" x2="{x1}" y2="{y}"/>')
    for i in range(6):
        x = x0 + (x1 - x0) * i / 5
        f.text(x, y + 82, f"{i} ms", "what", "middle")
    for i in range(72):  # calls: one short tick each
        x = x0 + 5 + i * 10
        f.add(f'<line class="tick" x1="{x}" y1="{y - 22}" x2="{x}" y2="{y - 6}"/>')
    f.text(x0, y - 30, "+1 +1 +1 +1 …", "what")
    for i in range(1, 6):  # samples
        x = x0 + (x1 - x0) * i / 5
        f.add(f'<line class="sample" x1="{x}" y1="{y + 4}" x2="{x}" y2="{y + 64}"/>')
        for k in range(3):
            f.rect(x - 22, y + 46 - 12 * k, 18, 9, "stack", 2)
    f.text(184, y - 14, L("呼び出しごと", "On every call"), "label", "end")
    f.text(184, y + 2, "count += 1", "code", "end")
    f.text(184, y + 44, L("1 ms ごと", "Every 1 ms"), "label", "end")
    f.text(184, y + 60, L("JFR がスタックを記録", "JFR records stacks"), "what", "end")
    f.text(32, 310, L("呼び出し回数 = カウンタの値（正確）", "Calls = the counter (exact)"), "label")
    f.text(300, 310, L("時間 ≈ サンプルに現れた回数 × 1 ms（推定値。数秒以上の記録を推奨）",
                       "Time ≈ samples the method is in × 1 ms (an estimate: record a few seconds or more)"), "label")
    f.rect(16, 342, 928, 164, "box", 8)
    f.text(32, 368, L("fib(35) の実行時間（プロファイラなし = 1、対数目盛）", "fib(35) run time against no profiler (log scale)"), "h")
    f.text(32, 388, L("ほとんど処理のないメソッドを約 3,000 万回呼ぶ、呼び出しを数える方式にとって最悪のケース",
                      "30 million calls of a method that does almost nothing: the worst case for anything that runs on every call"), "what")
    bars(f, 404, [(L("カウンタ + JFR（jvmeter）", "Count + JFR (jvmeter)"), "jvmeter", 1.8),
                  (L("呼び出しごとに時刻を取得", "Clock on every call"), "other", 39, "about"),
                  ("JProfiler Instrumentation", "jprofiler", 292)])
    return f.svg(522, L("呼び出しは数えるだけ、時間はサンプリングで", "Count every call, sample the time"),
                 L("jvmeter は呼び出しごとにカウンタを 1 増やすだけで時刻は取得せず、時間は JFR が 1 ms ごとに記録するスタックから見積もる。"
                   "fib(35) の実行時間は、この方式で 1.8 倍、呼び出しごとに時刻を取得する方式で約 39 倍、JProfiler の Instrumentation で 292 倍。",
                   "jvmeter adds one to a counter on each call without reading the clock, and estimates time from the stacks JFR records every 1 ms. "
                   "On fib(35) this runs 1.8 times slower, reading the clock on every call about 39 times, JProfiler's Instrumentation 292 times."))


def attach(f):
    L = f.L
    f.head(L("動いている JVM に、どこからでもアタッチ", "Attach to a running JVM, wherever it runs"),
           [L("手元の ssh と kubectl をそのまま使うので、~/.ssh/config・ProxyJump・kubeconfig・SSO の設定をそのまま使えます。",
              "jvmeter runs your own ssh and kubectl, so ~/.ssh/config, ProxyJump, kubeconfig and SSO work unchanged."),
            L("実行するコマンドは画面に表示され、コピーして手で実行することもできます。",
              "The commands it runs are shown, and can be copied and run by hand.")])
    f.rect(16, 100, 220, 282, "box", 8)
    f.text(32, 128, "jvmeter GUI", "h")
    f.text(32, 148, L("手元の PC", "Your computer"), "what")
    f.text(32, 186, L("接続先は常に", "It always talks to"), "what")
    f.text(32, 204, "127.0.0.1:PORT", "code")
    f.text(32, 242, L("接続先ごとの違いは 2 点だけ：", "Only two things differ:"), "what")
    f.text(32, 260, L("矢印の上：コマンドの実行方法", "above: how a command runs"), "what")
    f.text(32, 278, L("矢印の下：ポートへの接続方法", "below: how the port is reached"), "what")
    targets = [(L("ローカルの JVM", "A JVM on this computer"), L("同じ PC", "same machine"), "ProcessBuilder", "127.0.0.1:PORT"),
               (L("SSH 先の JVM", "A JVM over SSH"), L("Linux ホスト", "Linux host"), "ssh HOST -- …", "ssh -L 127.0.0.1:…"),
               (L("Kubernetes Pod の JVM", "A JVM in a Kubernetes pod"), L("コンテナ内", "inside the container"), "kubectl exec …", "kubectl port-forward")]
    for i, (name, where, run, reach) in enumerate(targets):
        y = 100 + 98 * i
        f.rect(600, y, 344, 86, "box", 8)
        f.text(616, y + 32, name, "label")
        f.text(616, y + 52, where, "what")
        f.rect(856, y + 22, 72, 42, "hl", 6)
        f.text(892, y + 39, "JVM", "label", "middle")
        f.text(892, y + 56, "+ agent", "what", "middle")
        f.arrow(240, y + 43, 594, y + 43)
        f.text(580, y + 34, run, "code", "end")
        f.text(580, y + 62, reach, "code", "end")
    f.rect(16, 398, 928, 152, "box", 8)
    f.text(32, 424, L("Attach and connect で行うこと", "What Attach and connect does"), "h")
    steps = [(L("JVM の一覧", "List JVMs"), L("jps と同じ情報を、", "What jps shows,"), L("JVM に触れずに取得", "without touching them")),
             (L("jar のコピー", "Copy the agent jar"), L("変更があったときだけ、", "only when changed, to"), L("本人だけが書き込める場所へ", "a directory only you write")),
             (L("アタッチ", "Attach"), L("再起動せずに読み込み、", "No restart. Counting"), L("計測を開始", "and sampling start")),
             (L("接続", "Connect"), L("127.0.0.1 のみで待ち受け、", "Listens on 127.0.0.1 only,"), L("トークンで認証", "checks a token"))]
    for i, (name, a, b) in enumerate(steps):
        x = 32 + 228 * i
        f.rect(x, 440, 212, 72, "inner")
        f.text(x + 14, 464, name, "label")
        f.text(x + 14, 488, a, "what")
        f.text(x + 14, 504, b, "what")
        if i < 3:
            f.arrow(x + 214, 476, x + 226, 476)
    f.text(32, 536, L("切断するとカウンタを取り除くので、元の速度に戻ります。エージェントは読み込まれたまま残るため、次回はすぐに接続できます。",
                      "Disconnecting removes the counters, and the JVM runs as fast as before. The agent stays loaded, so the next connect is quick."),
           "what")
    return f.svg(566, L("動いている JVM に、どこからでもアタッチ", "Attach to a running JVM, wherever it runs"),
                 L("GUI はローカルでは ProcessBuilder、SSH 先では ssh、Kubernetes の Pod では kubectl exec でコマンドを実行し、"
                   "ポートフォワードで 127.0.0.1 につなぐ。手順は JVM の一覧、エージェント jar のコピー、再起動なしのアタッチ、トークンで認証した接続。"
                   "切断するとカウンタを取り除き、元の速度に戻る。",
                   "The GUI runs commands with ProcessBuilder locally, ssh on a remote host and kubectl exec in a Kubernetes pod, "
                   "and reaches the agent at 127.0.0.1 through a port forward. The steps: list JVMs, copy the agent jar, attach without a restart, "
                   "connect with a token. Disconnecting removes the counters, and the JVM runs as fast as before."))


def snapshot(f):
    L = f.L
    f.head(L("LLM がそのまま読めるスナップショット", "A snapshot an LLM can read"),
           [L("ファイルの先頭に、項目と単位の説明（about）と要約（summary）を置いています。",
              "The file starts with what its fields and units mean (about) and a summary (summary)."),
            L("LLM に先頭の 80 行を渡すだけで、生データを読ませなくてもボトルネックを特定できます。",
              "The first 80 lines are enough to find the bottleneck, without the raw data.")])
    f.rect(16, 100, 600, 380, "box", 8)
    f.text(32, 128, "jvmeter-PID.json.gz", "code")
    f.rect(32, 140, 568, 324, "inner")
    f.rect(36, 191, 400, 22, "hl", 3)
    f.rect(36, 215, 400, 152, "hl", 3)
    lines = [("{", 0, ""), ('"format": "jvmeter-snapshot/1",', 1, ""),
             ('"about": [ "what the fields and units mean", … ],', 1, "about"),
             ('"summary": {', 1, "summary"), ('"cpu": { "hotSpotsBySelfTime": [ … ] },', 2, ""),
             ('"gc": { … }, "threads": { … }, "memory": { … },', 2, ""), ('"findings": [', 2, ""),
             ('"Threads waited 38 s for OrderCache@3c4d …",', 3, ""), ('"Heap after GC keeps growing …", …', 3, ""),
             ("] },", 2, ""), ('"cpu": { "tree": { … } },', 1, "raw"), ('"gc": { "events": [ … ] },', 1, "raw"),
             ('"threads": [ … ], …', 1, "raw"), ("}", 0, "")]
    for i, (s, ind, kind) in enumerate(lines):
        y = 162 + 22 * i
        f.text(44 + 14 * ind, y, s, "code dim" if kind == "raw" else "code")
    f.text(456, 207, L("項目と単位の説明", "what it means"), "label")
    f.text(456, 241, L("要約", "summary"), "label")
    for i, s in enumerate([L("ホットスポット、", "hot spots, GC,"), L("GC、スレッド、", "threads, memory"),
                           L("メモリ、", "and what was"), L("検出した問題", "found")]):
        f.text(456, 260 + 16 * i, s, "what")
    f.text(456, 405, L("生データ", "raw data"), "label")
    f.text(456, 424, L("GUI で表示できる", "opens in the GUI"), "what")
    f.arrow(620, 290, 650, 290)
    f.rect(656, 100, 288, 380, "box", 8)
    f.text(672, 128, L("LLM に渡す", "Hand it to an LLM"), "h")
    f.rect(672, 140, 256, 30, "term", 4)
    f.text(682, 160, "zcat jvmeter-*.json.gz | head -80", "termtext")
    f.text(672, 196, L("先頭の 80 行でわかること", "What the first 80 lines tell"), "label")
    found = [("bad", "Threads waited 38 s for", "OrderCache@3c4d in OrderCache.put"),
             ("bad", "Heap after GC keeps growing", "+22.1 MB/min. Possible leak"),
             ("warn", "OrderRepository.save waited", "47.5 s for I/O"),
             ("badge", "32 % of CPU time is in", "PriceCalculator.calculate")]
    for i, (sev, a, b) in enumerate(found):
        y = 210 + 58 * i
        f.rect(672, y, 256, 50, "inner")
        f.rect(672, y, 4, 50, sev, 2)
        f.text(686, y + 21, a, "code")
        f.text(686, y + 39, b, "code")
    f.text(672, 458, L("GUI の「見るべき場所」と同じ内容", "The same lines as Where to look"), "what")
    return f.svg(496, L("LLM がそのまま読めるスナップショット", "A snapshot an LLM can read"),
                 L("スナップショットは about（フィールドと単位の意味）と summary（ホットスポット、GC、スレッド、メモリ、検出した問題）で始まり、"
                   "生データが後に続く。zcat で先頭 80 行を LLM に渡すと、ロック待ち、ヒープの増加、I/O 待ち、CPU を使うメソッドがわかる。",
                   "A snapshot starts with about (what fields and units mean) and summary (hot spots, GC, threads, memory, findings), "
                   "followed by the raw data. The first 80 lines, from zcat, tell an LLM about lock waits, a growing heap, I/O waits "
                   "and the method using the most CPU."))


if __name__ == "__main__":
    here = Path(__file__).parent
    for name, draw in [("overview", overview), ("counting", counting), ("attach", attach), ("snapshot", snapshot)]:
        for lang in ("ja", "en"):
            (here / f"{name}.{lang}.svg").write_text(draw(Fig(lang)), encoding="utf-8")
