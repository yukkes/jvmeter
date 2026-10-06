package jvmeter.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Registry of method name -> method ID. */
public final class MethodRegistry {

  private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
  private static final ConcurrentHashMap<String, Integer> NAME_TO_ID = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, String> ID_TO_NAME = new ConcurrentHashMap<>();

  public static int register(String name) {
    return NAME_TO_ID.computeIfAbsent(
        name,
        k -> {
          int id = NEXT_ID.getAndIncrement();
          ID_TO_NAME.put(id, k);
          return id;
        });
  }

  public static String name(int id) {
    return ID_TO_NAME.getOrDefault(id, "<unknown:" + id + ">");
  }
}
