package jvmeter.gui;

import java.awt.Dimension;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.prefs.Preferences;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import jvmeter.core.Snapshot;

/**
 * java -jar jvmeter-gui.jar [snapshot.json.gz] — without a file, the Start Center opens first (like
 * JProfiler)
 */
public final class Main {

  public static void main(String[] args) {
    // Linux: the XRender pipeline misplaces glyphs drawn at fractional positions ("pr ocess"); the
    // default X11 pipeline does not
    if (System.getProperty("sun.java2d.xrender") == null) {
      System.setProperty("sun.java2d.xrender", "false");
    }
    // while the look and feel loads (startup is mostly class loading): the fonts with the text
    // shaper they need for kerning (HarfBuzz, about 400 classes on JDK 25), and the snapshot
    CompletableFuture<?> fonts =
        CompletableFuture.runAsync(
            () ->
                new TextLayout(
                    "jvmeter", Theme.sans(13, 400), new FontRenderContext(null, true, true)));
    CompletableFuture<Snapshot> read =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return args.length > 0 ? Snapshot.read(Path.of(args[0])) : null;
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    String saved = Preferences.userNodeForPackage(App.class).get("theme", null);
    Theme.dark = "dark".equals(saved);
    SwingUtilities.invokeLater(
        () -> {
          App.applyLaf();
          fonts.join();
          Snapshot snap = null;
          try {
            snap = read.join();
          } catch (CompletionException ce) {
            Throwable e =
                ce.getCause() instanceof UncheckedIOException u ? u.getCause() : ce.getCause();
            JOptionPane.showMessageDialog(
                null,
                "Could not open the snapshot: " + e.getMessage(),
                "jvmeter",
                JOptionPane.ERROR_MESSAGE);
          }
          JFrame f = new JFrame("jvmeter");
          App app = new App(snap);
          app.setPreferredSize(new Dimension(1280, 800));
          f.setContentPane(app);
          f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
          f.pack();
          f.setLocationRelativeTo(null);
          f.setVisible(true);
          if (snap == null) {
            SwingUtilities.invokeLater(app::openStart);
          }
        });
  }

  private Main() {}
}
