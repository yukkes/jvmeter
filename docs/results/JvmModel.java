@io.avaje.jsonb.Json
public class JvmModel {
  public long pid;
  public String name;
  public String jvm;
  public String args;
  public int port;
  public String token;

  public JvmModel() {}

  public JvmModel(long pid, String name, String jvm, String args, int port, String token) {
    this.pid = pid;
    this.name = name;
    this.jvm = jvm;
    this.args = args;
    this.port = port;
    this.token = token;
  }
}
