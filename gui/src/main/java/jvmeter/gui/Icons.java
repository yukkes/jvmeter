package jvmeter.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Material Symbols Rounded, drawn from their SVG path data (icons.properties) so no icon font is
 * needed.
 */
final class Icons {

  private static final Properties PATHS = new Properties();
  private static final Map<String, Path2D> SHAPES = new HashMap<>();

  static {
    try (InputStream in = Icons.class.getResourceAsStream("icons.properties")) {
      PATHS.load(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The glyph's 960-unit box scaled to size × size, at (x, y). */
  static void paint(
      Graphics2D g, String name, boolean fill, double x, double y, double size, Color color) {
    Path2D p = shape(fill && PATHS.containsKey(name + ".fill") ? name + ".fill" : name);
    AffineTransform t = new AffineTransform();
    t.translate(x, y + size);
    t.scale(size / 960, size / 960);
    g.setColor(color);
    g.fill(t.createTransformedShape(p));
  }

  private static Path2D shape(String key) {
    return SHAPES.computeIfAbsent(key, k -> parse(PATHS.getProperty(k, "")));
  }

  /**
   * SVG path data with the commands Material Symbols use: M L H V Q T C S Z, absolute and relative.
   */
  static Path2D parse(String d) {
    Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO);
    String[] tok =
        d.replaceAll("([A-Za-z])", " $1 ").replaceAll("(?<=[0-9.])-", " -").trim().split("[\\s,]+");
    double x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0;
    char cmd = 'M', prev = ' ';
    int i = 0;
    while (i < tok.length) {
      if (Character.isLetter(tok[i].charAt(0))) {
        cmd = tok[i++].charAt(0);
        if (cmd == 'Z' || cmd == 'z') {
          p.closePath();
          x = sx;
          y = sy;
          prev = cmd;
          continue;
        }
      }
      boolean rel = Character.isLowerCase(cmd);
      double ox = rel ? x : 0, oy = rel ? y : 0;
      switch (Character.toUpperCase(cmd)) {
        case 'M' -> {
          x = ox + num(tok, i++);
          y = oy + num(tok, i++);
          p.moveTo(x, y);
          sx = x;
          sy = y;
          cmd = rel ? 'l' : 'L'; // further pairs are line-tos
        }
        case 'L' -> {
          x = ox + num(tok, i++);
          y = oy + num(tok, i++);
          p.lineTo(x, y);
        }
        case 'H' -> {
          x = ox + num(tok, i++);
          p.lineTo(x, y);
        }
        case 'V' -> {
          y = oy + num(tok, i++);
          p.lineTo(x, y);
        }
        case 'Q' -> {
          cx = ox + num(tok, i++);
          cy = oy + num(tok, i++);
          x = ox + num(tok, i++);
          y = oy + num(tok, i++);
          p.quadTo(cx, cy, x, y);
        }
        case 'T' -> {
          boolean q = "QqTt".indexOf(prev) >= 0;
          cx = q ? 2 * x - cx : x;
          cy = q ? 2 * y - cy : y;
          x = ox + num(tok, i++);
          y = oy + num(tok, i++);
          p.quadTo(cx, cy, x, y);
        }
        case 'C' -> {
          double x1 = ox + num(tok, i++), y1 = oy + num(tok, i++);
          cx = ox + num(tok, i++);
          cy = oy + num(tok, i++);
          x = ox + num(tok, i++);
          y = oy + num(tok, i++);
          p.curveTo(x1, y1, cx, cy, x, y);
        }
        case 'S' -> {
          boolean c = "CcSs".indexOf(prev) >= 0;
          double x1 = c ? 2 * x - cx : x, y1 = c ? 2 * y - cy : y;
          cx = ox + num(tok, i++);
          cy = oy + num(tok, i++);
          x = ox + num(tok, i++);
          y = oy + num(tok, i++);
          p.curveTo(x1, y1, cx, cy, x, y);
        }
        default -> i++;
      }
      prev = cmd;
    }
    return p;
  }

  private static double num(String[] tok, int i) {
    return Double.parseDouble(tok[i]);
  }

  private Icons() {}
}
