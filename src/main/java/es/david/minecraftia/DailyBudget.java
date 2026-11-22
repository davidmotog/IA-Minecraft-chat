package es.david.minecraftia;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import static java.nio.file.StandardOpenOption.*;

/** The journal counts both settled usage and outstanding worst-case reservations. */
final class DailyBudget {
    static final long LIMIT = 2_000_000;
    record Reservation(LocalDate day, long maximum) {}
    private final Path dir;
    private final Clock clock;
    DailyBudget(Path dir, Clock clock) { this.dir = dir; this.clock = clock; }

    synchronized Reservation reserve(long amount) throws IOException, AiClient.AiException {
        LocalDate day = LocalDate.now(clock);
        update(day, amount, true);
        return new Reservation(day, amount);
    }
    synchronized void settle(Reservation reservation, long actual) throws IOException, AiClient.AiException {
        if (actual >= 0) update(reservation.day(), Math.subtractExact(actual, reservation.maximum()), false);
    }
    synchronized long today() throws IOException {
        return read(file(LocalDate.now(clock)));
    }
    private Path file(LocalDate day) { return dir.resolve(day + ".tokens"); }
    private long read(Path path) throws IOException {
        try {
            long n = Long.parseLong(Files.readString(path).strip());
            if (n < 0) throw new IOException("Contador negativo");
            return n;
        } catch (NoSuchFileException missing) { return 0; }
        catch (NumberFormatException invalid) { throw new IOException("Contador ilegible"); }
    }
    private void update(LocalDate day, long delta, boolean reserving) throws IOException, AiClient.AiException {
        Files.createDirectories(dir);
        try (var channel = FileChannel.open(dir.resolve("quota.lock"), CREATE, WRITE);
             var lock = channel.lock()) {
            Path path = file(day);
            long previous = read(path);
            if (reserving && (delta <= 0 || delta > LIMIT - previous))
                throw new AiClient.AiException("No queda margen suficiente para esta consulta en el límite diario de 2.000.000 de tokens. Se renueva a las 00:00 UTC.");
            long next = Math.addExact(previous, delta);
            if (next < 0) throw new IOException("Contador incoherente");
            Path temp = Files.createTempFile(dir, "quota-", ".tmp");
            try {
                try (var out = FileChannel.open(temp, WRITE, TRUNCATE_EXISTING)) {
                    var data = ByteBuffer.wrap(Long.toString(next).getBytes(StandardCharsets.US_ASCII));
                    while (data.hasRemaining()) out.write(data);
                    out.force(true);
                }
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
    }
}
