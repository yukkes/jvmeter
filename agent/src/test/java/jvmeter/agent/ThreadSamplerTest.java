package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThreadSamplerTest {

  @Test
  void samplesLiveThreadsAndTracksCount() {
    ThreadSampler s = new ThreadSampler("jvmeter.agent");
    s.sample(0.1);
    assertTrue(s.count() >= 1);
    assertFalse(s.second(1).isEmpty());
    assertFalse(s.threads().isEmpty());
  }

  @Test
  void classifiesIoAndIdleFrames() {
    StackTraceElement socketRead =
        new StackTraceElement("sun.nio.ch.SocketDispatcher", "read0", null, -2);
    StackTraceElement accept = new StackTraceElement("sun.nio.ch.Net", "accept", null, -2);
    assertTrue(ThreadSampler.io(socketRead.getClassName()));
    assertFalse(ThreadSampler.idle(socketRead));
    assertTrue(ThreadSampler.idle(accept));
  }
}
