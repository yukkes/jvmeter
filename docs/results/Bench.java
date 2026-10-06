import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.avaje.jsonb.Jsonb;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON parse/serialize micro-benchmark: Gson vs Jackson-databind vs Avaje Jsonb.
 *
 * Case A: a list of small flat objects (like `agent.jar list` output) — deserialized often in the
 *     Start Center.
 * Case B: one deep nested object (like a cpu call tree) — the bulk of a snapshot file.
 */
public class Bench {

  static final int WARM = 300;
  static final int ITER = 3000;

  public static void main(String[] a) throws Exception {
    String listJson = buildListJson();
    String treeJson = buildTreeJson();

    System.out.printf("payloads: list=%dB tree=%dB%n", listJson.length(), treeJson.length());
    System.out.printf("%-10s %12s %12s %12s%n", "lib", "listRead ms", "treeRead ms", "treeWrite ms");

    bench("Gson", new GsonRunner(listJson, treeJson));
    bench("Jackson", new JacksonRunner(listJson, treeJson));
    bench("Avaje", new AvajeRunner(listJson, treeJson));
    bench("Jr-ann", new JrAnnRunner(listJson, treeJson));
    bench("Afterburner", new AbRunner(listJson, treeJson));
  }

  // ---------- payloads ----------

  static String buildListJson() {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < 60; i++) {
      if (i > 0) b.append(',');
      b.append(
          "{\"pid\":"
              + (30000 + i)
              + ",\"name\":\"com.example.orders.OrderServiceApp"
              + i
              + "\",\"jvm\":\"OpenJDK 21.0.8\",\"args\":\"-Xmx512m -XX:+DebugNonSafepoints -Dfile.encoding=UTF-8\",\"port\":0}");
    }
    return b.append(']').toString();
  }

  static String buildTreeJson() {
    NodeModel root = new NodeModel("All threads", 0, 0);
    for (int i = 0; i < 12; i++) {
      NodeModel t = new NodeModel("thread-" + i, 0, 0);
      for (int j = 0; j < 60; j++) {
        NodeModel m = new NodeModel("com.example.orders.Service" + (j % 8) + ".op" + j, j * 1.5, j);
        m.children.add(new NodeModel("leaf.a" + j, j * 0.5, j * 3));
        t.children.add(m);
      }
      root.children.add(t);
    }
    return new Gson().toJson(root);
  }

  // ---------- runners ----------

  interface Runner {
    Object readList() throws Exception;
    Object readTree() throws Exception;
    String writeTree() throws Exception;
  }

  static class GsonRunner implements Runner {
    final Gson g = new Gson();
    final String listJson, treeJson;
    final java.lang.reflect.Type jvmList = new TypeToken<List<JvmModel>>() {}.getType();

    GsonRunner(String listJson, String treeJson) {
      this.listJson = listJson;
      this.treeJson = treeJson;
    }

    public Object readList() { return g.fromJson(listJson, jvmList); }
    public Object readTree() { return g.fromJson(treeJson, NodeModel.class); }
    public String writeTree() { return g.toJson(readTree()); }
  }

  static class JacksonRunner implements Runner {
    final ObjectMapper om = new ObjectMapper();
    final String listJson, treeJson;
    final com.fasterxml.jackson.databind.JavaType jvmList;

    JacksonRunner(String listJson, String treeJson) {
      this.listJson = listJson;
      this.treeJson = treeJson;
      this.jvmList = TypeFactory.defaultInstance().constructCollectionType(List.class, JvmModel.class);
    }

    public Object readList() throws Exception { return om.readValue(listJson, jvmList); }
    public Object readTree() throws Exception { return om.readValue(treeJson, NodeModel.class); }
    public String writeTree() throws Exception { return om.writeValueAsString(readTree()); }
  }

  static class JrAnnRunner implements Runner {
    final com.fasterxml.jackson.jr.ob.JSON json =
        com.fasterxml.jackson.jr.ob.JSON.builder()
            .register(com.fasterxml.jackson.jr.annotationsupport.JacksonAnnotationExtension.std)
            .build();
    final String listJson, treeJson;

    JrAnnRunner(String listJson, String treeJson) {
      this.listJson = listJson;
      this.treeJson = treeJson;
    }

    public Object readList() throws Exception {
      List<JvmModel> out = new ArrayList<>();
      var it = json.beanSequenceFrom(JvmModel.class, listJson);
      while (it.hasNextValue()) out.add(it.nextValue());
      return out;
    }
    public Object readTree() throws Exception {
      return json.beanFrom(NodeModel.class, treeJson);
    }
    public String writeTree() throws Exception {
      return json.asString(readTree());
    }
  }

  static class AbRunner implements Runner {
    final ObjectMapper om =
        new ObjectMapper().registerModule(new com.fasterxml.jackson.module.afterburner.AfterburnerModule());
    final String listJson, treeJson;
    final com.fasterxml.jackson.databind.JavaType jvmList;

    AbRunner(String listJson, String treeJson) {
      this.listJson = listJson;
      this.treeJson = treeJson;
      this.jvmList =
          TypeFactory.defaultInstance().constructCollectionType(List.class, JvmModel.class);
    }

    public Object readList() throws Exception { return om.readValue(listJson, jvmList); }
    public Object readTree() throws Exception { return om.readValue(treeJson, NodeModel.class); }
    public String writeTree() throws Exception { return om.writeValueAsString(readTree()); }
  }

  static class AvajeRunner implements Runner {
    final Jsonb b = Jsonb.builder().build();
    final io.avaje.jsonb.JsonType<List<JvmModel>> listType;
    final io.avaje.jsonb.JsonType<NodeModel> nodeType;
    final String listJson, treeJson;

    AvajeRunner(String listJson, String treeJson) {
      this.listJson = listJson;
      this.treeJson = treeJson;
      this.listType = b.type(JvmModel.class).list();
      this.nodeType = b.type(NodeModel.class);
    }

    public Object readList() { return listType.fromJson(listJson); }
    public Object readTree() { return nodeType.fromJson(treeJson); }
    public String writeTree() { return nodeType.toJson((NodeModel) readTree()); }
  }

  // ---------- loop ----------

  static void bench(String name, Runner r) throws Exception {
    for (int i = 0; i < WARM; i++) {
      r.readList();
      r.readTree();
      r.writeTree();
    }
    long t0 = System.nanoTime();
    for (int i = 0; i < ITER; i++) r.readList();
    long listRead = System.nanoTime() - t0;

    t0 = System.nanoTime();
    for (int i = 0; i < ITER; i++) r.readTree();
    long treeRead = System.nanoTime() - t0;

    t0 = System.nanoTime();
    for (int i = 0; i < ITER; i++) r.writeTree();
    long treeWrite = System.nanoTime() - t0;

    System.out.printf("%-10s %12.1f %12.1f %12.1f%n",
        name, listRead / 1e6, treeRead / 1e6, treeWrite / 1e6);
  }
}
