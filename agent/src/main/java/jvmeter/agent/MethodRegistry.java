package jvmeter.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Registry of method name -> method ID. */
public final class MethodRegistry {

  private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
  private static final ConcurrentHashMap<String, Integer> NAME_TO_ID = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, String> ID_TO_NAME = new ConcurrentHashMap<>();

  public static int register(String name) {
    Integer existing = NAME_TO_ID.get(name);
    if (existing != null) {
      return existing;
    }
    if (NEXT_ID.get() >= CallCounter.MAX_METHODS) {
      return CallCounter.MAX_METHODS;
    }
    Integer assigned =
        NAME_TO_ID.computeIfAbsent(
            name,
            k -> {
              int id = NEXT_ID.getAndIncrement();
              if (id >= CallCounter.MAX_METHODS) {
                return null;
              }
              ID_TO_NAME.put(id, k);
              return id;
            });
    return assigned != null ? assigned : CallCounter.MAX_METHODS;
  }

  public static String name(int id) {
    return ID_TO_NAME.getOrDefault(id, "<unknown:" + id + ">");
  }
}
