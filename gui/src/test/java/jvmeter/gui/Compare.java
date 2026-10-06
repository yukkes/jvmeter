package jvmeter.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Pixel comparison of a reference screenshot of demo/prototype.html and a GUI render of the same
 * size. A pixel matches when every channel differs by at most TOL.
 *
 * <p>Font rendering differs between the browser and Java (hinting, subpixel antialiasing), so
 * differences inside a text box of the reference are excused, but only when it is the same text at
 * the same place: the strings the GUI drew in the box (by the middle of their x-height; Shot's
 * trace) spell the reference text, and the ink of the box has the same bounding box in both images
 * (within 1px vertically and 2px horizontally). Everything else counts.
 *
 * <p>java ... jvmeter.gui.Compare dir... compares every ref-<state>-<theme>.png in each dir with
 * gui-<state>-<theme>.png, in parallel, prints one line per pair and exits with 1 when one matches
 * less than 99 %. ref-*.txt: "x0 y0 x1 y1 lines bottom\ttext" per rendered line (bottom: before
 * clipping to scroll boxes) of each text node; gui-*.txt: "x baseline width size\ttext" per drawn
 * string. diff-*.png shows matching pixels faded, excused pixels yellow and mismatches red;
 * diff-*.txt lists the text boxes that differ.
 */
public final class Compare {
  static final int TOL = 32;

  record Drawn(double x, double y, double w, double size, String text) {}

  static boolean near(int p, int q) {
    return Math.abs((p >> 16 & 255) - (q >> 16 & 255)) <= TOL
        && Math.abs((p >> 8 & 255) - (q >> 8 & 255)) <= TOL
        && Math.abs((p & 255) - (q & 255)) <= TOL;
  }

  /**
   * bounding box [x0, y0, x1, y1] of the pixels in r (except skipped ones) that differ from the
   * box's most common color, or null
   */
  static int[] ink(BufferedImage im, int[] r, boolean[] skip) {
    Map<Integer, Integer> n = new HashMap<>();
    for (int y = r[1]; y < r[3]; y++) {
      for (int x = r[0]; x < r[2]; x++) {
        n.merge(im.getRGB(x, y), 1, Integer::sum);
      }
    }
    int bg = n.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
    for (int y = r[1]; y < r[3]; y++) {
      for (int x = r[0]; x < r[2]; x++) {
        if (!skip[y * im.getWidth() + x] && !near(im.getRGB(x, y), bg)) {
          b[0] = Math.min(b[0], x);
          b[1] = Math.min(b[1], y);
          b[2] = Math.max(b[2], x);
          b[3] = Math.max(b[3], y);
        }
      }
    }
    return b[2] < 0 ? null : b;
  }

  static String norm(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  /**
   * The GUI's text in the box is the reference text: equal, or (wrapped or ellipsized) every part
   * between ellipses appears in the reference text.
   */
  static boolean sameText(String ref, String gui, boolean oneLine) {
    if (gui.isEmpty()) {
      return false;
    }
    if (gui.equals(ref)) {
      return true;
    }
    if (oneLine && !gui.contains("…")) {
      return false;
    }
    for (String part : gui.split("…")) {
      if (!ref.contains(part.trim())) {
        return false;
      }
    }
    return true;
  }

  public static void main(String[] dirs) throws Exception {
    List<File[]> pairs = new ArrayList<>();
    for (String dir : dirs) {
      File[] refs = new File(dir).listFiles((d, n) -> n.startsWith("ref-") && n.endsWith(".png"));
      Arrays.sort(refs);
      for (File r : refs) {
        pairs.add(new File[] {r, new File(dir, r.getName().replace("ref-", "gui-"))});
      }
    }
    double[] pct = new double[pairs.size()];
    String[] out = new String[pairs.size()];
    java.util.stream.IntStream.range(0, pairs.size())
        .parallel()
        .forEach(
            i -> {
              File r = pairs.get(i)[0], g = pairs.get(i)[1];
              String base = r.getPath().replaceAll("ref-(.*)\\.png$", "");
              String name = r.getName().replaceAll("^ref-|\\.png$", "");
              try {
                out[i] =
                    compare(
                        r,
                        g,
                        new File(base + "diff-" + name + ".png"),
                        new File(base + "diff-" + name + ".txt"));
              } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
              pct[i] = Double.parseDouble(out[i].substring(0, out[i].indexOf('%')));
              out[i] = String.format("%-9s %-14s %s", r.getParentFile().getName(), name, out[i]);
            });
    boolean ok = true;
    for (int i = 0; i < out.length; i++) {
      System.out.println(out[i]);
      ok &= pct[i] >= 99;
    }
    System.exit(ok && out.length > 0 ? 0 : 1);
  }

  /**
   * compares ref.png with gui.png (and their .txt traces), writes the diff image and log, returns
   * the summary line
   */
  static String compare(File refPng, File guiPng, File diffPng, File diffTxt)
      throws java.io.IOException {
    StringBuilder log = new StringBuilder();
    File refTxt = new File(refPng.getPath().replaceAll("\\.png$", ".txt")),
        guiTxt = new File(guiPng.getPath().replaceAll("\\.png$", ".txt"));
    BufferedImage x = ImageIO.read(refPng), y = ImageIO.read(guiPng);
    int w = Math.min(x.getWidth(), y.getWidth()), h = Math.min(x.getHeight(), y.getHeight());
    boolean[] excused = new boolean[w * h];
    int boxes = 0, bad = 0;
    if (refTxt.exists() && guiTxt.exists()) {
      List<Drawn> drawn = new ArrayList<>();
      for (String line : Files.readAllLines(guiTxt.toPath())) {
        int tab = line.indexOf('\t');
        if (tab > 0) {
          String[] f = line.substring(0, tab).split(" ");
          drawn.add(
              new Drawn(
                  Double.parseDouble(f[0]),
                  Double.parseDouble(f[1]),
                  Double.parseDouble(f[2]),
                  Double.parseDouble(f[3]),
                  line.substring(tab + 1)));
        }
      }
      // the reference boxes; where boxes of different texts overlap, neither box's position check
      // looks
      List<int[]> rects = new ArrayList<>();
      List<String> texts = new ArrayList<>(), lines = new ArrayList<>();
      for (String line : Files.readAllLines(refTxt.toPath())) {
        int tab = line.indexOf('\t');
        if (tab < 0) {
          continue;
        }
        String[] f = line.substring(0, tab).split(" ");
        // [x0, y0, x1, y1 (clipped to the image), lines, y1 before clipping (the text may go on
        // below the screen)]
        int[] r = {
          Math.max(0, Integer.parseInt(f[0])),
          Math.max(0, Integer.parseInt(f[1])),
          Math.min(w, Integer.parseInt(f[2])),
          Math.min(h, Integer.parseInt(f[3])),
          Integer.parseInt(f[4]),
          Integer.parseInt(f[5])
        };
        if (r[2] > r[0] && r[3] > r[1]) {
          rects.add(r);
          texts.add(norm(line.substring(tab + 1)));
          lines.add(line.substring(0, tab));
        }
      }
      boolean[] shared = new boolean[w * h];
      String[] owner = new String[w * h];
      for (int i = 0; i < rects.size(); i++) {
        int[] r = rects.get(i);
        for (int j = r[1]; j < r[3]; j++) {
          for (int k = r[0]; k < r[2]; k++) {
            String o = owner[j * w + k];
            if (o == null) {
              owner[j * w + k] = texts.get(i);
            } else if (!o.equals(texts.get(i))) {
              shared[j * w + k] = true;
            }
          }
        }
      }
      for (int i = 0; i < rects.size(); i++) {
        int[] r = rects.get(i);
        String ref = texts.get(i);
        String[] f = lines.get(i).split(" ");
        boxes++;
        StringBuilder gui = new StringBuilder();
        drawn.stream()
            .filter(
                d ->
                    d.x + d.w / 2 >= r[0] - 1
                        && d.x + d.w / 2 <= r[2] + 1
                        && d.y - 0.35 * d.size >= r[1]
                        && d.y - 0.35 * d.size <= r[5] + 1)
            .sorted((p, q) -> Double.compare(p.x, q.x))
            .forEach(d -> gui.append(d.text));
        String g = norm(gui.toString());
        // the outer columns may hold the antialiasing of a neighbour's first or last glyph
        int[] in = r[2] - r[0] > 4 ? new int[] {r[0] + 1, r[1], r[2] - 1, r[3]} : r;
        int[] p = ink(x, in, shared), q = ink(y, in, shared);
        boolean placed =
            p == null
                ? q == null
                : q != null
                    && Math.abs(p[0] - q[0]) <= 2
                    && Math.abs(p[2] - q[2]) <= 2
                    && Math.abs(p[1] - q[1]) <= 1
                    && Math.abs(p[3] - q[3]) <= 1;
        boolean text = sameText(ref, g, r[4] == 1);
        if (placed && text) {
          for (int j = r[1]; j < r[3]; j++) {
            Arrays.fill(excused, j * w + r[0], j * w + r[2], true);
          }
        } else {
          bad++;
          log.append(
              String.format(
                  "%s at %s %s %s: \"%s\" vs gui \"%s\"%s%n",
                  text ? "moved" : "TEXT",
                  f[0],
                  f[1],
                  f[2],
                  ref,
                  g,
                  placed ? "" : " ink " + Arrays.toString(p) + " vs " + Arrays.toString(q)));
        }
      }
    }
    BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    long same = 0, font = 0;
    for (int j = 0; j < h; j++) {
      for (int i = 0; i < w; i++) {
        int p = x.getRGB(i, j), q = y.getRGB(i, j);
        if (near(p, q)) {
          same++;
          int l = (((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3) / 4 + 191;
          d.setRGB(i, j, l << 16 | l << 8 | l);
        } else if (excused[j * w + i]) {
          font++;
          d.setRGB(i, j, 0xf0c000);
        } else {
          d.setRGB(i, j, 0xff0000);
        }
      }
    }
    ImageIO.write(d, "png", diffPng);
    Files.writeString(diffTxt.toPath(), log);
    long all = (long) w * h;
    return String.format(
        java.util.Locale.ROOT,
        "%.2f%% match excusing font rendering (%d px differ; %d text boxes of %d same, %d not) |"
            + " raw %.2f%%",
        (same + font) * 100.0 / all,
        all - same - font,
        boxes - bad,
        boxes,
        bad,
        same * 100.0 / all);
  }
}
