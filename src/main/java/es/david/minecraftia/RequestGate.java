package es.david.minecraftia;

import java.util.*;

/** Accessed only from the server main thread. Clock supplied for deterministic tests. */
final class RequestGate {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();
    private final Deque<Long> recent = new ArrayDeque<>();

    String acquire(UUID player, long now, int cooldownSeconds, int concurrency, int perMinute) {
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        while (!recent.isEmpty() && now - recent.peekFirst() >= 60_000) recent.removeFirst();
        if (inFlight.contains(player)) return "Ya tienes una consulta en curso.";
        long remaining = cooldowns.getOrDefault(player, now) - now;
        if (remaining > 0) return "Espera " + ((remaining + 999) / 1000) + " segundos antes de otra consulta.";
        if (inFlight.size() >= concurrency) return "La IA está atendiendo otras consultas. Prueba en unos segundos.";
        if (recent.size() >= perMinute) return "Se alcanzó el límite de consultas por minuto del servidor.";
        inFlight.add(player); cooldowns.put(player, now + cooldownSeconds * 1000L); recent.addLast(now);
        return null;
    }
    void finish(UUID player) { inFlight.remove(player); }
    int active() { return inFlight.size(); }
}
