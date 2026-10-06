# Bundled font

The GUI uses Inter so it looks the same on every OS (demo/prototype.html loads the same font from Google Fonts).
These files are written by [`gui/fonts.py`](../../../../../../fonts.py) from the Inter 4.1 release on GitHub
(https://github.com/rsms/inter/releases/tag/v4.1): subsets (Latin, Latin-1, Latin Extended-A, Greek, punctuation, arrows)
where Inter's tabular forms (`tnum`) also have private-use codepoints, since Java cannot turn on OpenType features.
Characters outside the subset fall back to the system font.

Inter, copyright 2016 The Inter Project Authors, SIL Open Font License 1.1 ([OFL.txt](OFL.txt), the release's LICENSE.txt).
