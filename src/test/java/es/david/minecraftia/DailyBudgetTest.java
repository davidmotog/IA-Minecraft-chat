package es.david.minecraftia;

import java.nio.file.*;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DailyBudgetTest {
    @TempDir Path dir;
    Clock day = Clock.fixed(Instant.parse("2026-11-22T23:59:59Z"), ZoneOffset.UTC);

    @Test void concurrentReservationsCannotOverspendAndSurviveRestart() throws Exception {
        var budget = new DailyBudget(dir, day);
        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Boolean> reserve = () -> {
                try { budget.reserve(1_100_000); return true; }
                catch (AiClient.AiException denied) { return false; }
            };
            var attempts = pool.invokeAll(java.util.Collections.nCopies(4, reserve));
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            assertEquals(1, accepted);
        }
        var restarted = new DailyBudget(dir, day);
        assertEquals(1_100_000, restarted.today());
        restarted.reserve(900_000);
        assertThrows(AiClient.AiException.class, () -> restarted.reserve(1));
    }
    @Test void lateSettlementDoesNotRefundTheFollowingDay() throws Exception {
        var yesterday = new DailyBudget(dir, day);
        var ticket = yesterday.reserve(8500);
        var tomorrow = new DailyBudget(dir, Clock.offset(day, Duration.ofSeconds(1)));
        tomorrow.reserve(2500);
        tomorrow.settle(ticket, 700);
        assertEquals(700, yesterday.today());
        assertEquals(2500, tomorrow.today());
    }
    @Test void corruptLedgerBlocksRequests() throws Exception {
        Files.writeString(dir.resolve("2026-11-22.tokens"), "invalid");
        assertThrows(IOException.class, () -> new DailyBudget(dir, day).reserve(1));
    }
    @Test void readsUsersDotenvFormatAndStandardFormat() throws Exception {
        Path file = dir.resolve(".env");
        for (String line : new String[]{"openai_apikey=sk-test-key", "export OPENAI_API_KEY='sk-test-key'", "sk-test-key"}) {
            Files.writeString(file, "\uFEFF"+line);
            assertEquals("sk-test-key", KeyFile.read(file));
        }
    }
}
