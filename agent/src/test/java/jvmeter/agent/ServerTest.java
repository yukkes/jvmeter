package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class ServerTest {

  @Test
  void readLineReadsCommandsAndRejectsOversizedLines() throws IOException {
    BufferedReader r =
        new BufferedReader(new StringReader("{\"cmd\":\"stop\"}\r\n{\"cmd\":\"gc\"}\n"));
    assertEquals("{\"cmd\":\"stop\"}", Server.readLine(r));
    assertEquals("{\"cmd\":\"gc\"}", Server.readLine(r));
    assertNull(Server.readLine(r));

    BufferedReader big =
        new BufferedReader(new StringReader("x".repeat(Server.MAX_COMMAND_LEN + 1) + "\n"));
    assertThrows(IOException.class, () -> Server.readLine(big));
  }
}
