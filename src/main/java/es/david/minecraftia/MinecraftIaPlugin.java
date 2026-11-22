package es.david.minecraftia;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Clock;

public final class MinecraftIaPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final RequestGate gate = new RequestGate();
    private final ConversationMemory memory = new ConversationMemory();
    private AiClient ai;
    private DailyBudget budget;
    private RecipeBook recipes;
    private ExecutorService workers;
    private volatile boolean stopping;
    private volatile String chatPrefix = "@ia";
    private volatile boolean chatEnabled;
    private AiClient.Settings settings;
    private int cooldown, concurrency, perMinute, maxQuestion, maxAnswer;
    private boolean imagesEnabled;
    private boolean memoryEnabled;

    @Override public void onEnable() {
        saveDefaultConfig();
        budget = new DailyBudget(getDataFolder().toPath().resolve("cuota"), Clock.systemUTC());
        ai = new AiClient(budget);
        workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("MinecraftIA-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
        recipes = new RecipeBook(this);
        loadSettings();
        PluginCommand command = Objects.requireNonNull(getCommand("ia"));
        command.setExecutor(this); command.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(recipes, this);
        getServer().getScheduler().runTaskTimer(this, () -> memory.expire(System.nanoTime()), 1200, 1200);
        getLogger().info("MinecraftIA activo. Recetas disponibles; IA " + (settings.key().isBlank() ? "pendiente de clave API." : "configurada (sin comprobar conexión)."));
    }
    private void loadSettings() {
        String key = loadKey();
        settings = new AiClient.Settings(key, getConfig().getString("openai.model", "gpt-5.4-mini"),
                bounded("openai.timeout-seconds", 40, 5, 120), bounded("openai.max-output-tokens", 1200, 256, 4096));
        cooldown = bounded("limits.cooldown-seconds", 8, 1, 3600);
        concurrency = bounded("limits.concurrent-requests", 4, 1, 4);
        perMinute = bounded("limits.requests-per-minute", 20, 1, 1000);
        maxQuestion = bounded("limits.max-question-characters", 1000, 50, 4000);
        maxAnswer = bounded("limits.max-answer-characters", 1800, 200, 4000);
        imagesEnabled = getConfig().getBoolean("images.enabled", true);
        memoryEnabled = getConfig().getBoolean("memory.enabled", true);
        if (!memoryEnabled) memory.clear();
        chatPrefix = getConfig().getString("chat.prefix", "@ia").strip();
        if (chatPrefix.isEmpty()) chatPrefix = "@ia";
        chatEnabled = getConfig().getBoolean("chat.enabled", true);
        recipes.loadAliases(getConfig().getConfigurationSection("aliases"));
    }
    private String loadKey() {
        String configured = getConfig().getString("openai.env-file", ".env");
        try {
            Path path = Path.of(configured);
            if (!path.isAbsolute()) path = getDataFolder().toPath().resolve(configured);
            if (!Files.notExists(path)) return KeyFile.read(path);
            Path local = getDataFolder().toPath().resolve(".env");
            if (!Files.notExists(local)) return KeyFile.read(local);
        } catch (IOException | InvalidPathException e) {
            getLogger().warning("No se ha podido leer el archivo .env de MinecraftIA.");
            return "";
        }
        String env = System.getenv("OPENAI_API_KEY");
        return env != null && !env.isBlank() ? env.strip() : getConfig().getString("openai.api-key", "").strip();
    }
    private String quotaStatus() {
        try { return budget.today() + " / 2.000.000 tokens usados o reservados hoy (UTC)."; }
        catch (IOException e) { return "No se puede leer la cuota."; }
    }
    private int bounded(String path, int fallback, int min, int max) {
        return Math.clamp(getConfig().getInt(path, fallback), min, max);
    }
    @Override public void onDisable() {
        stopping = true;
        memory.clear();
        if (recipes != null) recipes.closeViewers();
        if (workers != null) workers.shutdownNow();
        if (ai != null) ai.close();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        handle(sender, args);
        return true;
    }
    private void handle(CommandSender sender, String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("estado") || args[0].equalsIgnoreCase("recargar"))) {
            if (!sender.hasPermission("minecraftia.admin")) { say(sender, "No tienes permiso de administración."); return; }
            if (args[0].equalsIgnoreCase("recargar")) { reloadConfig(); loadSettings(); say(sender, "Configuración recargada."); }
            else say(sender, "Modelo: " + settings.model() + ". Clave: " + (settings.key().isBlank() ? "sin configurar" : "configurada, no verificada")
                    + ". Consultas activas: " + gate.active() + ". " + quotaStatus() + " Imágenes: " + (imagesEnabled ? "sí" : "no")
                    + ". Memoria: " + (memoryEnabled ? "activada (4 intercambios, 30 min)" : "desactivada") + ".");
            return;
        }
        if (!sender.hasPermission("minecraftia.use")) { say(sender, "No tienes permiso para usar MinecraftIA."); return; }
        if (args.length == 0 || args[0].equalsIgnoreCase("ayuda")) { help(sender); return; }
        if (!(sender instanceof Player player)) { say(sender, "Usa este comando desde Minecraft con un jugador."); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("olvidar")) {
            memory.forget(player.getUniqueId());
            say(player, "He borrado tu conversación.");
        } else if (sub.equals("receta")) {
            if (args.length < 2) { say(player, "Ejemplo: /ia receta pistón"); return; }
            String name = join(args, 1);
            if (name.length() > maxQuestion) { say(player, "El nombre del objeto es demasiado largo."); return; }
            Material material = recipes.resolve(name);
            if (material != null) recipes.show(player, material);
            else ask(player, "Identifica este objeto para consultar su receta: " + name, null, true);
        } else if (sub.equals("foto")) {
            if (!imagesEnabled || !player.hasPermission("minecraftia.image")) { say(player, "Las consultas con imágenes no están habilitadas para ti."); return; }
            if (args.length < 2) { say(player, "Uso: /ia foto https://ejemplo.com/imagen.png ¿Cómo se fabrica esto?"); return; }
            try {
                String url = AiClient.validateImageUrl(args[1]);
                String question = args.length > 2 ? join(args, 2) : "¿Qué objeto de Minecraft aparece y cómo se fabrica?";
                ask(player, question, url, false);
            } catch (IllegalArgumentException e) { say(player, e.getMessage()); }
        } else ask(player, join(args, 0), null, false);
    }
    private static String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)); }

    private void ask(Player player, String question, String imageUrl, boolean recipeOnly) {
        if (question.isBlank() || question.length() > maxQuestion) { say(player, "La pregunta debe tener entre 1 y " + maxQuestion + " caracteres."); return; }
        if (settings.key().isBlank()) {
            say(player, "La IA aún no tiene clave API. El administrador debe configurarla. Puedes usar /ia receta piston o un identificador del juego."); return;
        }
        UUID id = player.getUniqueId();
        String rejected = gate.acquire(id, System.nanoTime() / 1_000_000L, cooldown, concurrency, perMinute);
        if (rejected != null) { say(player, rejected); return; }
        AiClient.Settings currentSettings = settings;
        ConversationMemory.Turn turn = memoryEnabled ? memory.begin(id, System.nanoTime()) : null;
        List<ConversationMemory.Exchange> history = turn == null ? List.of() : turn.history();
        String version = Bukkit.getMinecraftVersion();
        int responseLimit = maxAnswer;
        say(player, imageUrl == null ? "Consultando a la IA…" : "Enviando el enlace a la IA para analizar la imagen…");
        try {
            workers.execute(() -> {
                AiClient.Answer answer = null;
                String failure = null;
                try { answer = ai.ask(currentSettings, question, imageUrl, version, history); }
                catch (AiClient.AiException e) { failure = e.getMessage(); }
                catch (RuntimeException e) {
                    failure = "No se ha podido completar la consulta. Revisa la configuración.";
                    getLogger().warning("Consulta fallida: " + e.getClass().getSimpleName());
                }
                AiClient.Answer result = answer;
                String error = failure;
                if (stopping) return;
                try {
                    Bukkit.getScheduler().runTask(this, () -> {
                        gate.finish(id);
                        // Avoid delivering a previous session's response after a player reconnects.
                        if (stopping || !player.isOnline() || Bukkit.getPlayer(id) != player) return;
                        if (error != null) { say(player, error); return; }
                        if (result == null) { say(player, "La IA no devolvió una respuesta."); return; }
                        String visibleAnswer = cleanAnswer(result.text(), responseLimit);
                        if (turn != null && memoryEnabled) {
                            memory.remember(turn, question, visibleAnswer, result.itemId(), System.nanoTime());
                        }
                        say(player, visibleAnswer);
                        if (!result.itemId().isEmpty()) {
                            Material material = recipes.resolve(result.itemId());
                            if (material != null) {
                                if (recipeOnly) recipes.show(player, material);
                                else player.sendMessage(Component.text("[Ver receta de " + material.getKey().getKey() + "]", NamedTextColor.GREEN)
                                        .clickEvent(ClickEvent.runCommand("/ia receta " + material.getKey()))
                                        .hoverEvent(Component.text("Consultar los ingredientes reales del servidor")));
                            } else say(player, "El objeto identificado no existe en este servidor.");
                        } else if (recipeOnly) say(player, "Prueba con el identificador del objeto, por ejemplo: /ia receta minecraft:piston");
                    });
                } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Server is stopping. */ }
            });
        } catch (RejectedExecutionException e) { gate.finish(id); say(player, "La IA está ocupada. Vuelve a intentarlo en unos segundos."); }
    }
    static String cleanAnswer(String text, int limit) {
        String clean = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").replace('\u00a7', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }
    void say(CommandSender recipient, String text) {
        recipient.sendMessage(Component.text("[IA] ", NamedTextColor.AQUA).append(Component.text(text, NamedTextColor.WHITE)));
    }
    private void help(CommandSender sender) {
        say(sender, "/ia <pregunta> — responde dudas de Minecraft.");
        say(sender, "/ia receta <objeto> — muestra su receta; ejemplo: pistón.");
        say(sender, "/ia foto <enlace HTTPS> [pregunta] — reconoce una imagen.");
        if (chatEnabled) say(sender, "También puedes escribir " + chatPrefix + " seguido de tu pregunta en el chat.");
        say(sender, "Las respuestas son privadas. Las preguntas y enlaces se envían a OpenAI.");
        if (memoryEnabled) say(sender, "La consulta incluye hasta 4 intercambios recientes tuyos. Usa /ia olvidar para borrarlos.");
        if (sender.hasPermission("minecraftia.admin")) say(sender, "/ia estado · /ia recargar");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!chatEnabled || stopping) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        String prefix = chatPrefix;
        if (!text.equalsIgnoreCase(prefix) && !text.regionMatches(true, 0, prefix + " ", 0, prefix.length() + 1)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String query = text.substring(prefix.length()).strip();
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline() && !stopping) handle(player, query.isEmpty() ? new String[0] : query.split("\\s+"));
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("minecraftia.use")) return List.of();
        if (args.length == 1) return (sender.hasPermission("minecraftia.admin")
                ? List.of("ayuda", "receta", "foto", "olvidar", "estado", "recargar") : List.of("ayuda", "receta", "foto", "olvidar"))
                .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length >= 2 && args[0].equalsIgnoreCase("receta")) {
            String whole = join(args, 1);
            int used = whole.length() - args[args.length - 1].length();
            return recipes.suggestions(whole).stream().map(s -> s.substring(Math.min(used, s.length()))).toList();
        }
        return List.of();
    }
}
