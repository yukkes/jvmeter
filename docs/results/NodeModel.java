import java.util.ArrayList;
import java.util.List;

@io.avaje.jsonb.Json
public class NodeModel {
  public String name;
  public double self;
  public long calls;
  public List<NodeModel> children = new ArrayList<>();

  public NodeModel() {}

  public NodeModel(String name, double self, long calls) {
    this.name = name;
    this.self = self;
    this.calls = calls;
  }
}
