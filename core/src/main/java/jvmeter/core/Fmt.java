package jvmeter.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Number and time formatting, identical to the HTML prototype. */
public final class Fmt {

  private Fmt() {}

  /** JavaScript's {@code Number.prototype.toFixed}: half-up on the exact binary value. */
  public static String fixed(double v, int digits) {
    if (Double.isNaN(v)) {
      return "NaN";
    }
    String r = new BigDecimal(Math.abs(v)).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    return v < 0 ? "-" + r : r;
  }

  /** {@code +v.toFixed(digits)} */
  public static double round(double v, int digits) {
    return Double.parseDouble(fixed(v, digits));
  }

  /** JavaScript's default number to string for the values used here. */
  public static String num(double v) {
    if (v == Math.rint(v) && Math.abs(v) < 1e15) {
      return Long.toString((long) v);
    }
    return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
  }

  private static final ThreadLocal<DecimalFormat> INT =
      ThreadLocal.withInitial(
          () -> {
            DecimalFormat f =
                new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.US));
            f.setRoundingMode(RoundingMode.HALF_UP);
            return f;
          });

  /** {@code n.toLocaleString("en-US")} */
  public static String fmtInt(double n) {
    return INT.get().format(n);
  }

  /** 1 s or more is shown in seconds (2 decimals below 10 s, 1 decimal above) */
  public static String fmtSec(double ms) {
    return fixed(ms / 1000, ms < 10000 ? 2 : 1) + " s";
  }

  public static String fmtMs(double ms) {
    return ms >= 1000 ? fmtSec(ms) : Math.round(ms) + " ms";
  }

  public static String fmtBytes(double b) {
    return b >= 1048576 ? fixed(b / 1048576, 1) + " MB" : fixed(b / 1024, 0) + " kB";
  }

  public static String fmtPause(double ms) {
    return ms >= 1000
        ? fmtSec(ms)
        : (ms >= 100 ? Long.toString(Math.round(ms)) : fixed(ms, 1)) + " ms";
  }

  /** a method seen only in samples (not in the counted packages) has no call count: "—" */
  public static String calls(double n) {
    return n > 0 ? fmtInt(n) : "—";
  }

  /** time per call, "—" without a call count */
  public static String perCall(double ms, double n) {
    return n > 0 ? fmtAvg(ms / n) : "—";
  }

  public static String fmtAvg(double ms) {
    if (ms >= 1000) {
      return fmtSec(ms);
    }
    if (ms >= 1) {
      return fixed(ms, 1) + " ms";
    }
    return fixed(ms * 1000, ms * 1000 >= 10 ? 0 : 1) + " µs";
  }

  /** mm:ss */
  public static String fmtClock(long s) {
    return String.format("%02d:%02d", s / 60, s % 60);
  }

  public static String signedInt(double v) {
    return (v > 0 ? "+" : v < 0 ? "−" : "") + fmtInt(Math.abs(v));
  }

  public static String signedBytes(double v) {
    return (v > 0 ? "+" : v < 0 ? "−" : "") + fmtBytes(Math.abs(v));
  }

  /** t (seconds since the recording started) as the time of day, HH:MM:SS */
  public static String tod(long startMs, double t) {
    LocalTime lt =
        Instant.ofEpochMilli(startMs + Math.round(t) * 1000)
            .atZone(ZoneId.systemDefault())
            .toLocalTime();
    return String.format("%02d:%02d:%02d", lt.getHour(), lt.getMinute(), lt.getSecond());
  }

  /** ticks at round times of day: each t in t0..t1 whose clock time is a multiple of step */
  public static List<Double> todTicks(long startMs, double t0, double t1, int step) {
    long off = Math.floorMod(Math.floorDiv(startMs, 1000), step);
    List<Double> a = new ArrayList<>();
    for (double t = Math.ceil((t0 + off) / step) * step - off; t <= t1 + 1e-9; t += step) {
      a.add(t);
    }
    return a;
  }

  /** the smallest round step that gives at most 6 ticks */
  public static int tickStep(double dur) {
    for (int s :
        new int[] {
          5, 10, 15, 20, 30, 60, 120, 300, 600, 1200, 1800, 3600, 7200, 10800, 21600, 43200
        }) {
      if (dur / s <= 6) {
        return s;
      }
    }
    return 43200;
  }

  /** "pkg.Class.method" -> "Class.method" */
  public static String shortName(String name) {
    String[] p = name.split("\\.");
    return p.length < 2 ? name : p[p.length - 2] + "." + p[p.length - 1];
  }

  /** index where Class.method starts (after "pkg."), or 0 */
  public static int packageEnd(String name) {
    int last = name.lastIndexOf('.');
    int i = last < 1 ? -1 : name.lastIndexOf('.', last - 1);
    return i > 0 ? i + 1 : 0;
  }
}
