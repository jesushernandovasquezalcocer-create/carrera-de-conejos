package rabbitrace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * the test task proves which JDK compiled it — not which JDK is on PATH.
 */
class ToolchainTest {

    @Test
    void runsOnJava25() {
        assertEquals(25, Runtime.version().feature());
    }
}
