package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentFileTest {

  @Test
  void ownerMatchesPlainAndWindowsDomainUsers() {
    assertTrue(AgentFile.ownerMatches("alice", "alice"));
    assertTrue(AgentFile.ownerMatches("CORP\\alice", "alice"));
    assertFalse(AgentFile.ownerMatches("CORP\\malice", "alice"));
    assertFalse(AgentFile.ownerMatches("bob", "alice"));
    assertFalse(AgentFile.ownerMatches(null, "alice"));
  }
}
