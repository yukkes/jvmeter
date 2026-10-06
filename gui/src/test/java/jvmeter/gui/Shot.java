package jvmeter.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import jvmeter.core.Snapshot;

/**
 * Renders the GUI in every state to PNGs, for comparison with screenshots of demo/prototype.html:
 * java ... jvmeter.gui.Shot outdir WxH,WxH... [snapshot.json.gz] writes
 * outdir/WxH/gui-<state>-<theme>.png and the strings it drew (.txt). Runs headless: lays the
 * components out without a window and paints them into an image.
 */
public final class Shot {
  static void layout(Component c) {
    if (c instanceof Container k) {
      k.doLayout();
      for (Component ch : k.getComponents()) {
        layout(ch);
      }
    }
  }

  static void dump(Component c, String ind) {
    System.out.println(
        ind
            + c.getClass().getSimpleName()
            + " "
            + c.getBounds().x
            + ","
            + c.getBounds().y
            + " "
            + c.getWidth()
            + "x"
            + c.getHeight()
            + (c instanceof Ui.Txt t ? " \"" + t.text + "\"" : ""));
    if (c instanceof Container k) {
      for (Component ch : k.getComponents()) {
        dump(ch, ind + "  ");
      }
    }
  }

  static final String[] STATES = {
    "overview", "cpu", "cpusel", "tree", "heap", "gc", "threads", "empty"
  };

  public static void main(String[] a) throws Exception {
    for (String size : a[1].split(",")) {
      File dir = new File(a[0], size);
      dir.mkdirs();
      String[] wh = size.split("x");
      for (String theme : new String[] {"light", "dark"}) {
        for (String state : STATES) {
          shot(
              new File(dir, "gui-" + state + "-" + theme + ".png"),
              Integer.parseInt(wh[0]),
              Integer.parseInt(wh[1]),
              theme,
              state,
              a.length > 2 ? a[2] : null);
        }
      }
    }
    System.exit(0);
  }

  static void shot(File png, int W, int H, String theme, String state, String snapshot)
      throws Exception {
    Theme.dark = theme.equals("dark");
    App[] app = new App[1];
    SwingUtilities.invokeAndWait(
        () -> {
          App.applyLaf();
          try {
            // empty: no JVM yet, the window behind the Start Center
            app[0] =
                new App(
                    state.equals("empty")
                        ? null
                        : snapshot != null
                            ? Snapshot.read(java.nio.file.Path.of(snapshot))
                            : Snapshot.loadSample());
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          App.State st = app[0].state;
          switch (state) {
            case "cpu" -> st.view = "cpu";
            case "cpusel" -> {
              st.view = "cpu";
              st.sel = "java.sql.PreparedStatement.executeUpdate";
            }
            case "tree" -> {
              st.view = "cpu";
              st.cpuTab = "tree";
            }
            case "heap" -> {
              st.view = "memory";
              st.memTab = "heap";
            }
            case "gc" -> {
              st.view = "memory";
              st.memTab = "gc";
            }
            case "threads" -> st.view = "threads";
            default -> {}
          }
          app[0].render();
          app[0].setSize(W, H);
          layout(app[0]);
          layout(app[0]); // second pass: viewports size their views after the first layout
          if (Boolean.getBoolean("dump")) {
            dump(app[0], "");
          }
        });
    BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    StringBuilder texts = new StringBuilder();
    Ui.trace =
        (s, x, y, w, size) ->
            texts.append(
                String.format(
                    java.util.Locale.ROOT, "%.2f %.2f %.2f %.2f\t%s%n", x, y, w, size, s));
    SwingUtilities.invokeAndWait(
        () -> {
          Graphics2D g = img.createGraphics();
          app[0].paint(g);
          g.dispose();
        });
    ImageIO.write(img, "png", png);
    java.nio.file.Files.writeString(
        new File(png.getPath().replaceAll("\\.png$", ".txt")).toPath(), texts);
  }
}
