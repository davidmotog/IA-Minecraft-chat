package es.david.minecraftia;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RequestGateTest {
    @Test void blocksDuplicateRequestsAndKeepsCooldownAfterFinishing() {
        RequestGate gate = new RequestGate(); UUID player = UUID.randomUUID();
        assertNull(gate.acquire(player, 1000, 8, 4, 20));
        assertTrue(gate.acquire(player, 1100, 8, 4, 20).contains("en curso"));
        gate.finish(player);
        assertTrue(gate.acquire(player, 2000, 8, 4, 20).contains("7 segundos"));
        assertNull(gate.acquire(player, 9000, 8, 4, 20));
    }
    @Test void enforcesGlobalConcurrencyAndReleasesCapacityOnFailure() {
        RequestGate gate = new RequestGate(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertNull(gate.acquire(a, 0, 1, 1, 20));
        assertNotNull(gate.acquire(b, 0, 1, 1, 20));
        gate.finish(a);
        assertNull(gate.acquire(b, 0, 1, 1, 20));
        assertEquals(1, gate.active());
    }
    @Test void rateLimitSurvivesCompletionButExpiresAfterOneMinute() {
        RequestGate gate = new RequestGate(); UUID a = UUID.randomUUID();
        assertNull(gate.acquire(a, 0, 1, 4, 1)); gate.finish(a);
        assertTrue(gate.acquire(UUID.randomUUID(), 59_999, 1, 4, 1).contains("por minuto"));
        assertNull(gate.acquire(UUID.randomUUID(), 60_000, 1, 4, 1));
    }
}
