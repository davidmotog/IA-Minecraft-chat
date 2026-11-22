package es.david.minecraftia;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {
    @Test void playersHaveTheirOwnRecentExchanges() {
        var memory = new ConversationMemory();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            memory.remember(memory.begin(first, i), "pregunta " + i, "respuesta " + i, "", i);
        }
        var questions = memory.begin(first, 6).history().stream().map(ConversationMemory.Exchange::question).toList();
        assertEquals(List.of("pregunta 2", "pregunta 3", "pregunta 4", "pregunta 5"), questions);
        assertTrue(memory.begin(second, 6).history().isEmpty());
    }

    @Test void aReplyInFlightCannotRestoreForgottenHistory() {
        var memory = new ConversationMemory();
        var player = UUID.randomUUID();
        var first = memory.begin(player, 0);
        memory.remember(first, "pregunta anterior", "respuesta", "", 1);
        var pending = memory.begin(player, 2);
        assertEquals(1, pending.history().size());
        memory.forget(player);
        var next = memory.begin(player, 3);
        memory.remember(pending, "pregunta en curso", "respuesta tardía", "", 4);
        assertTrue(next.history().isEmpty());
        assertTrue(memory.begin(player, 5).history().isEmpty());
    }

    @Test void expiresThirtyMinutesAfterTheLastExchange() {
        var memory = new ConversationMemory();
        var player = UUID.randomUUID();
        memory.remember(memory.begin(player, 0), "pregunta", "respuesta", "", 1);
        long expiration = 1 + Duration.ofMinutes(30).toNanos();
        assertEquals(1, memory.begin(player, expiration - 1).history().size());
        assertTrue(memory.begin(player, expiration).history().isEmpty());
    }
}
