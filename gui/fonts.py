#!/usr/bin/env python3
"""Fetches Inter from its GitHub release and writes the subsets bundled in the GUI (src/main/resources/jvmeter/gui/fonts).

Java cannot turn on OpenType features, so the tabular forms (tnum: digits, space, punctuation) also get codepoints of
their own: U+F000 + c below U+0100, U+F000 + c - U+1F00 for U+2000..23FF (Theme.tnum maps text to them).
Needs fontTools (pip install fonttools, or apt install python3-fonttools). Usage: python3 gui/fonts.py
"""
import hashlib, io, os, urllib.request, zipfile
from fontTools import subset
from fontTools.ttLib import TTFont

URL = "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip"
SHA256 = "9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e"
UNICODES = "U+0020-007E,U+00A0-017F,U+0370-03FF,U+2000-206F,U+2190-21FF,U+2212,U+2264,U+2265,U+F000-F3FF"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src/main/resources/jvmeter/gui/fonts")

data = urllib.request.urlopen(URL).read()
assert hashlib.sha256(data).hexdigest() == SHA256, "unexpected download"
z = zipfile.ZipFile(io.BytesIO(data))
with open(os.path.join(OUT, "OFL.txt"), "wb") as f:
    f.write(z.read("LICENSE.txt"))

for w in ["Regular", "Medium", "Bold"]:
    font = TTFont(io.BytesIO(z.read(f"extras/otf/Inter-{w}.otf")))
    gsub = font["GSUB"].table
    tnum = {}
    for fr in gsub.FeatureList.FeatureRecord:
        if fr.FeatureTag == "tnum":
            for i in fr.Feature.LookupListIndex:
                for st in gsub.LookupList.Lookup[i].SubTable:
                    tnum.update(getattr(st, "mapping", None) or st.ExtSubTable.mapping)
    for t in font["cmap"].tables:
        if t.isUnicode():
            for cp, g in list(t.cmap.items()):
                if g in tnum and (cp < 0x100 or 0x2000 <= cp < 0x2400):
                    t.cmap[0xF000 + (cp if cp < 0x100 else cp - 0x1F00)] = tnum[g]
    opts = subset.Options()
    opts.layout_features = ["kern"]
    s = subset.Subsetter(opts)
    s.populate(unicodes=subset.parse_unicodes(UNICODES))
    s.subset(font)
    font.save(os.path.join(OUT, f"Inter-{w}.otf"))
    print(f"Inter-{w}.otf", os.path.getsize(os.path.join(OUT, f"Inter-{w}.otf")), "bytes")
