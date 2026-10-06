# Java GUI (Swing + FlatLaf)

`gui/` is the desktop GUI. It shows the same four views as [`demo/prototype.html`](../demo/prototype.html),
which stays as the HTML prototype and as the reference for every screen.

## Why Swing + FlatLaf

| Option | Verdict |
|---|---|
| Agent serves the HTML prototype (`jdk.httpserver`) | Smallest effort, but the GUI is not Java and needs a browser |
| JavaFX + WebView | Still HTML inside; WebView may lag behind CSS features used by the prototype |
| JavaFX controls | Adds OpenJFX (30–40 MB) to the distribution |
| **Swing + FlatLaf** | **Chosen.** Only the JDK plus FlatLaf (~1 MB). Light and dark themes, and Swing ships with every JDK |

## How it is built

The screens are drawn like the prototype's CSS, not with stock Swing widgets, so they can match it pixel for pixel.
A few small components in `Ui.java` cover what the page needs:

| Component | Stands for |
|---|---|
| `Box` | a flex row / column or a grid (`fr` columns, `auto-fit` minimum width), with background, border, radius, padding and one-sided rules. Horizontal positions are fractional and snapped to pixels like the browser's |
| `Txt`, `Rich` | text in a CSS line box (Blink's baseline rule), wrapping at spaces and hyphens, ellipsis; `Rich` mixes fonts in one line |
| `Ic` | Material Symbols icons: the SVG paths in `icons.properties`, the same ones the prototype inlines |
| `Btn` | a box that acts as a button: hover, Enter / Space, accessible name and role |
| `Table` | the prototype's tables: sticky header with sort buttons, numeric columns fitted to their content, method names that shorten the package first |
| `AxisChart` | the heap and GC charts: time-of-day axis, -Xmx / -Xms lines, Full GC markers, Before / After picking |

FlatLaf provides the look of what remains Swing (scroll bars, the search field, dialogs, tooltips) and the dark theme.
Colors are the prototype's CSS tokens (`Theme.java`). Both use Inter: the prototype from Google Fonts, the GUI bundled as a subset
(`gui/src/main/resources/jvmeter/gui/fonts`, SIL OFL, written by `gui/fonts.py` from the GitHub release),
so text looks the same on every OS; other characters fall back to the system font.
Numbers use Inter's tabular forms where the prototype's `.num` does (tables, KPIs, times): Java cannot turn on OpenType
features, so the subset gives those forms codepoints of their own and `Theme.num` fonts draw with them.
The layout targets 1280 px; on wide windows the content stops at 1600 px and is centered.
On Linux the XRender pipeline is turned off by default, as it misplaces glyphs drawn at fractional positions.
All analysis comes from `core`, which is a port of the prototype's JavaScript checked by `GoldenTest`.

## Checked against the prototype

[`gui/compare`](../gui/compare) renders every view headless (`Shot`), and the empty window behind the Start Center, in light and dark, at 1280x800 and 1920x1080, and compares it with Chromium
screenshots of `demo/prototype.html` (`Compare`). The only differences allowed are in glyph rasterization:
inside a text box of the page, pixels are excused only if the GUI drew the same string at the same place.
Every view matches at least 99.8 % with every text box in place (raw pixels, without excusing anything: 96.2–99.7 %).
The prototype opens with `#sample` there, so it shows the sample instead of the Start Center, as `Shot` does.
The whole run (Chromium and the GUI render at the same time, one JVM compares the 32 pairs in parallel) takes about 5 s.

`AppTest` drives the interactions headless: sorting, selecting, search, the call tree, Before / After picking,
Run GC, recording, the theme switch and the Start Center (list, attach, resuming where it left off).
The Start Center dialog itself is not in the pixel comparison: it is built from the same components, and its JVM lists depend on the machine.
`LiveTest` starts a real JVM, lists it, attaches, and checks the live call tree, thread states, Run GC and the class histogram;
with `JVMETER_SSH` set to a Host that reaches this computer, it does the same over ssh, and with
`JVMETER_KUBE=context/namespace` in a pod (for example a kind cluster).

## Distribution and native image

The GUI is one runnable jar of about 2.2 MB (core, avaje-jsonb, FlatLaf without its native libraries, and the Inter subset) and runs on JDK 17+.

A GraalVM native image is **not worth it** now:

- The agent cannot be one: it runs inside the profiled JVM (`-javaagent`).
- For the GUI it would trade a 2.2 MB jar for a 40 MB+ executable per OS, built on each OS, with reachability metadata
  to maintain for the reflection that FlatLaf uses (avaje-jsonb uses none). AWT / Swing support in native image has been
  limited, especially on macOS.
- What it would buy is faster startup, which matters little for a GUI opened once per investigation.

For users without a JDK, `jlink` / `jpackage` can bundle a trimmed runtime (`java.desktop`, `java.prefs`, …) with the jar
into an installer per OS, keeping plain Java.
