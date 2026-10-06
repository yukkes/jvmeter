# GUI vs prototype comparison

`compare.sh` renders every view of the Swing GUI, and the empty window behind the Start Center, (light and dark, 1280x800 and 1920x1080; `SIZES=...` to change) headless
and compares it pixel by pixel with screenshots of `demo/prototype.html` taken in Chromium. It fails when a view matches less than 99 %.

```bash
./mvnw package
cd gui/compare && npm install && npx playwright install chromium
./compare.sh            # images in gui/target/compare/<size>: ref-*, gui-*, diff-* (red = differs, yellow = glyphs only; diff-*.txt: text boxes that differ)
```

How a pixel counts (`Compare.java`): it matches when no channel differs by more than 32.
Glyph rasterization differs between Chromium and Java2D (hinting, subpixel antialiasing), so pixels inside a text box
of the reference are excused, but only if the GUI drew the same string there (traced by `Shot`) and the text's ink sits
at the same place (within 1px vertically, 2px horizontally). Everything else, including icons and charts, counts.
Both sides use Inter (the page from Google Fonts, so the comparison needs network); Chromium runs with `--font-render-hinting=none` (fractional glyph positions, as on
Windows and macOS and in the GUI). The time of day comes from the machine's time zone, the same for both.
