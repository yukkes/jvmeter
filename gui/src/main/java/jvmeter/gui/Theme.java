package jvmeter.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Colors and fonts of demo/prototype.html (its CSS variables), light and dark. */
final class Theme {

  static boolean dark;

  // CSS: --bg --surface --sunken --fg --muted --line --accent --accent-soft --rec --run --wait
  // --block --io --bar --bar-self --good --limit --event --bad --on-bad
  private static final int[] LIGHT = {
    0xf4f6f8, 0xffffff, 0xeef1f5, 0x18212c, 0x5d6978, 0xd9dee5, 0x2563c9, 0xe3ecfa, 0xd93b30,
    0x2f9e55, 0xd9a420, 0xd93b30, 0x3b82d6, 0xe8775f, 0xb9361f, 0x1f7a3f, 0x0e7490, 0xd97706,
    0xc9362b, 0xffffff
  };
  private static final int[] DARK = {
    0x0f1318, 0x171c23, 0x1e252e, 0xe4e9ef, 0x96a2b0, 0x2a323d, 0x78a6f2, 0x1f2e45, 0xf0645a,
    0x4cc074, 0xe6b93a, 0xf0645a, 0x64a2ec, 0xd9715b, 0xf09a85, 0x4cc074, 0x22d3ee, 0xf59e0b,
    0xf0645a, 0x0f1318
  };

  private static Color c(int i) {
    return new Color(dark ? DARK[i] : LIGHT[i]);
  }

  static Color bg() {
    return c(0);
  }

  static Color surface() {
    return c(1);
  }

  static Color sunken() {
    return c(2);
  }

  static Color fg() {
    return c(3);
  }

  static Color muted() {
    return c(4);
  }

  static Color line() {
    return c(5);
  }

  static Color accent() {
    return c(6);
  }

  static Color accentSoft() {
    return c(7);
  }

  static Color rec() {
    return c(8);
  }

  static Color run() {
    return c(9);
  }

  static Color waitC() {
    return c(10);
  }

  static Color block() {
    return c(11);
  }

  static Color io() {
    return c(12);
  }

  static Color bar() {
    return c(13);
  }

  static Color barSelf() {
    return c(14);
  }

  static Color good() {
    return c(15);
  }

  static Color limit() {
    return c(16);
  }

  static Color event() {
    return c(17);
  }

  static Color bad() {
    return c(18);
  }

  /** text on a --bad fill */
  static Color onBad() {
    return c(19);
  }

  /** CSS color-mix(in srgb, c pct%, transparent) */
  static Color alpha(Color c, double pct) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * pct / 100));
  }

  // ---------- font: Inter, bundled (subset, OFL; see fonts/README.md), as in demo/prototype.html
  // ----------

  private static final Font[] SANS = {
    load("Inter-Regular.otf"), load("Inter-Medium.otf"), load("Inter-Bold.otf")
  };
  private static final Map<String, Font> CACHE = new HashMap<>();

  private static Font load(String file) {
    try (InputStream in =
        Objects.requireNonNull(Theme.class.getResourceAsStream("fonts/" + file))) {
      return Font.createFont(Font.TRUETYPE_FONT, in);
    } catch (IOException | FontFormatException | NullPointerException e) {
      return new Font(Font.SANS_SERIF, file.contains("Bold") ? Font.BOLD : Font.PLAIN, 1);
    }
  }

  /** weight 400 | 500 | 700 */
  static Font sans(double size, int weight) {
    Font face = SANS[weight >= 700 ? 2 : weight >= 500 ? 1 : 0];
    // kerning on, as the browser applies it by default (the subset has no other layout features)
    return CACHE.computeIfAbsent(
        face.getFontName() + size,
        k ->
            face.deriveFont((float) size)
                .deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON)));
  }

  private static final java.util.Set<Font> NUM =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

  /**
   * f with tabular figures, for numbers (the prototype's .num, font-variant-numeric: tabular-nums)
   */
  static Font num(Font f) {
    return CACHE.computeIfAbsent(
        "num" + f.getFontName() + f.getSize2D(),
        k -> {
          Font n = f.deriveFont(f.getSize2D());
          NUM.add(n);
          return n;
        });
  }

  /**
   * s as f draws it: for a num font, the characters with a tabular form moved to it (fonts.py puts
   * them at U+F000 + c)
   */
  static String shown(Font f, String s) {
    if (!NUM.contains(f)) {
      return s;
    }
    char[] cs = s.toCharArray();
    for (int i = 0; i < cs.length; i++) {
      int c = cs[i], p = 0xF000 + (c < 0x100 ? c : c - 0x1F00);
      if ((c < 0x100 || c >= 0x2000 && c < 0x2400) && f.canDisplay(p)) {
        cs[i] = (char) p;
      }
    }
    return new String(cs);
  }

  /**
   * the font that shows s: the bundled subset, or the system font for characters outside it (like
   * the browser's fallback)
   */
  static Font forText(Font f, String s) {
    if (f.canDisplayUpTo(s) < 0) {
      return f;
    }
    return CACHE.computeIfAbsent(
        "fallback" + f.getFontName() + f.getSize2D(),
        k ->
            new Font(Font.SANS_SERIF, f.getFontName().contains("Bold") ? Font.BOLD : Font.PLAIN, 1)
                .deriveFont(f.getSize2D()));
  }

  /** measuring context: antialiased, fractional advances (what painting uses, see hints) */
  static final FontRenderContext FRC = new FontRenderContext(null, true, true);

  /**
   * the desktop's text antialiasing (ClearType on Windows and so on); grayscale when the desktop
   * does not say
   */
  private static final Object TEXT_AA = textAa();

  private static Object textAa() {
    Object d =
        GraphicsEnvironment.isHeadless()
            ? null
            : java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
    return d instanceof Map<?, ?> m && m.get(RenderingHints.KEY_TEXT_ANTIALIASING) != null
        ? m.get(RenderingHints.KEY_TEXT_ANTIALIASING)
        : RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
  }

  static Graphics2D hints(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, TEXT_AA);
    g.setRenderingHint(
        RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    return g;
  }

  private Theme() {}
}
