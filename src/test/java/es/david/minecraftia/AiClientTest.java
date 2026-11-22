package es.david.minecraftia;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AiClientTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    private DailyBudget budget() { return new DailyBudget(dir, java.time.Clock.systemUTC()); }
    private static String completed(String text, String item) {
        JsonObject answer = new JsonObject(); answer.addProperty("answer", text); answer.addProperty("item_id", item);
        JsonObject part = new JsonObject(); part.addProperty("type", "output_text"); part.addProperty("text", answer.toString());
        JsonArray content = new JsonArray(); content.add(part);
        JsonObject message = new JsonObject(); message.addProperty("type", "message"); message.add("content", content);
        JsonObject reasoning = new JsonObject(); reasoning.addProperty("type", "reasoning");
        JsonArray output = new JsonArray(); output.add(reasoning); output.add(message);
        JsonObject response = new JsonObject(); response.addProperty("status", "completed"); response.add("output", output);
        JsonObject usage = new JsonObject(); usage.addProperty("total_tokens", 123);
        response.add("usage", usage);
        return response.toString();
    }
    @Test void roundTripSendsVisionAndStructuredOutputWithoutStoring() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = completed("Parece un pistón.", "minecraft:piston").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try (AiClient client = new AiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), budget())) {
            AiClient.Answer answer = client.ask(new AiClient.Settings("test-key", "gpt-5.4-mini", 5, 1200),
                    "¿Qué es esto?", "https://example.com/piston.png", "26.3", List.of());
            assertEquals("Parece un pistón.", answer.text());
            assertEquals("minecraft:piston", answer.itemId());
            assertEquals("Bearer test-key", authorization.get());
            JsonObject request = JsonParser.parseString(received.get()).getAsJsonObject();
            assertFalse(request.get("store").getAsBoolean());
            assertEquals("gpt-5.4-mini", request.get("model").getAsString());
            assertEquals(123, budget().today());
            JsonArray content = request.getAsJsonArray("input").get(0).getAsJsonObject().getAsJsonArray("content");
            assertEquals("input_image", content.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("https://example.com/piston.png", content.get(1).getAsJsonObject().get("image_url").getAsString());
            assertTrue(request.getAsJsonObject("text").getAsJsonObject("format").get("strict").getAsBoolean());
            assertFalse(received.get().contains("test-key"));
        } finally { server.stop(0); }
    }
    @Test void serverErrorsDoNotExposeResponseBodiesOrSecrets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "secret-sensitive-detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try (AiClient client = new AiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), budget())) {
            AiClient.AiException failure = assertThrows(AiClient.AiException.class,
                    () -> client.ask(new AiClient.Settings("test", "test", 5, 1000), "hola", null, "26.3", List.of()));
            assertTrue(failure.getMessage().contains("clave"));
            assertFalse(failure.getMessage().contains("secret"));
            assertEquals(0, budget().today());
        } finally { server.stop(0); }
    }
    @Test void incompleteAndMalformedResponsesAreHandled() {
        assertThrows(AiClient.AiException.class, () -> AiClient.parse("{\"status\":\"incomplete\",\"output\":[]}"));
        assertThrows(AiClient.AiException.class, () -> AiClient.parse("not json"));
        assertThrows(AiClient.AiException.class, () -> AiClient.parse("{}"));
    }
    @Test void followUpIncludesTextHistoryAndIdentifiedItem() {
        var settings = new AiClient.Settings("test", "gpt-5.4-mini", 5, 1200);
        var previous = new ConversationMemory.Exchange("¿Qué ves?", "Un pistón.", "minecraft:piston");
        JsonObject body = AiClient.payload(settings, "¿Cómo lo fabrico?", null, "26.3", List.of(previous));
        JsonArray messages = body.getAsJsonArray("input");
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("¿Qué ves?", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("assistant", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(1).getAsJsonObject().get("content").getAsString().contains("minecraft:piston"));
        assertEquals(1, messages.get(2).getAsJsonObject().getAsJsonArray("content").size());
        assertFalse(body.get("store").getAsBoolean());
    }
    @Test void refusesInvalidItemIdsFromTheModel() throws Exception {
        assertEquals("", AiClient.parse(completed("Texto", "/op someone")).itemId());
        assertEquals("", AiClient.parse(completed("Texto", "minecraft:piston\n/stop")).itemId());
    }
    @Test void refusalIsReportedClearly() {
        String body = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}";
        assertTrue(assertThrows(AiClient.AiException.class, () -> AiClient.parse(body)).getMessage().contains("no puede responder"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a.png", "file:///C:/secret.png", "https://localhost/a.png",
            "https://127.0.0.1/a.png", "https://user:password@example.com/a.png", "https://example.com:8080/a.png",
            "https://example.local/a.png", "https://[::1]/a.png", "data:image/png;base64,AAAA", "https://example.com/a#fragment"})
    void rejectsInvalidImageLinks(String url) { assertThrows(IllegalArgumentException.class, () -> AiClient.validateImageUrl(url)); }
    @Test void acceptsPublicHttpsLinksIncludingSignedQueryStrings() {
        String url = "https://cdn.example.com/image.png?signature=abc&expires=123";
        assertEquals(url, AiClient.validateImageUrl(url));
    }
}
