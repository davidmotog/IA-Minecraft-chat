package es.david.minecraftia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.text.Normalizer;
import java.util.*;

final class RecipeBook implements Listener {
    private final MinecraftIaPlugin plugin;
    private final Map<String, String> aliases = new HashMap<>();
    RecipeBook(MinecraftIaPlugin plugin) { this.plugin = plugin; }

    void loadAliases(ConfigurationSection section) {
        aliases.clear();
        if (section != null) for (String key : section.getKeys(false)) aliases.put(normalize(key), section.getString(key, ""));
    }
    static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").strip().replaceAll("\\s+", " ");
    }
    Material resolve(String input) {
        String normalized = normalize(input);
        String id = aliases.getOrDefault(normalized, normalized.replace(' ', '_'));
        Material material = Material.matchMaterial(id);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }
    List<String> suggestions(String prefix) {
        String normalized = normalize(prefix);
        return java.util.stream.Stream.concat(aliases.keySet().stream(), Arrays.stream(Material.values())
                        .filter(Material::isItem).filter(m -> !m.isAir()).map(m -> m.getKey().getKey()))
                .filter(s -> s.startsWith(normalized)).sorted().distinct().limit(60).toList();
    }
    boolean show(Player player, Material material) {
        List<Recipe> all = Bukkit.getRecipesFor(new ItemStack(material));
        List<Recipe> supported = all.stream().filter(RecipeBook::supported).toList();
        if (supported.isEmpty()) {
            plugin.say(player, all.isEmpty()
                    ? "No hay una receta registrada para " + material.getKey() + ". Puede obtenerse por otros medios."
                    : "Esta receta usa una mecánica especial que todavía no se puede representar en el visor.");
            return false;
        }
        open(player, new Viewer(material, supported), 0);
        return true;
    }
    private static boolean supported(Recipe recipe) {
        return recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe
                || recipe instanceof CookingRecipe<?> || recipe instanceof StonecuttingRecipe
                || recipe instanceof SmithingTransformRecipe;
    }
    private void open(Player player, Viewer viewer, int page) {
        viewer.page = page;
        Recipe recipe = viewer.recipes.get(page);
        Inventory inventory = Bukkit.createInventory(viewer, 54, Component.text("Receta " + (page + 1) + "/" + viewer.recipes.size(), NamedTextColor.DARK_AQUA));
        viewer.inventory = inventory;
        ItemStack background = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, background);
        for (int slot : RecipeLayout.GRID) inventory.setItem(slot, null);
        String station;
        if (recipe instanceof ShapedRecipe shaped) {
            station = "Mesa de trabajo: respeta la posición";
            char[] cells = RecipeLayout.flatten(shaped.getShape());
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (int i = 0; i < 9; i++) inventory.setItem(RecipeLayout.GRID[i], ingredient(choices.get(cells[i])));
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            station = "Mesa de trabajo: cualquier orden";
            List<RecipeChoice> choices = shapeless.getChoiceList();
            for (int i = 0; i < Math.min(9, choices.size()); i++) inventory.setItem(RecipeLayout.GRID[i], ingredient(choices.get(i)));
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            station = recipe instanceof BlastingRecipe ? "Alto horno" : recipe instanceof SmokingRecipe ? "Ahumador"
                    : recipe instanceof CampfireRecipe ? "Hoguera" : "Horno";
            inventory.setItem(20, ingredient(cooking.getInputChoice()));
            inventory.setItem(29, icon(Material.CLOCK, "Tiempo: " + cooking.getCookingTime() / 20.0 + " s",
                    List.of(Component.text("Combustible aparte si la estación lo necesita."))));
        } else if (recipe instanceof StonecuttingRecipe cutting) {
            station = "Cortapiedras";
            inventory.setItem(20, ingredient(cutting.getInputChoice()));
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            station = "Mesa de herrería: plantilla / base / material";
            inventory.setItem(19, ingredient(smithing.getTemplate()));
            inventory.setItem(20, ingredient(smithing.getBase()));
            inventory.setItem(21, ingredient(smithing.getAddition()));
        } else { return; }
        inventory.setItem(23, icon(Material.ARROW, "Resultado →", List.of()));
        inventory.setItem(24, recipe.getResult().clone());
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(station, NamedTextColor.YELLOW));
        lore.add(Component.text("Pasa el ratón por los ingredientes para ver alternativas."));
        lore.add(Component.text("Vista informativa: no consume ni entrega objetos."));
        if (recipe instanceof Keyed keyed) lore.add(Component.text(keyed.getKey().toString(), NamedTextColor.DARK_GRAY));
        inventory.setItem(49, icon(Material.BOOK, "Receta registrada en el servidor", lore));
        inventory.setItem(48, icon(Material.BARRIER, "Cerrar", List.of()));
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Receta anterior", List.of()));
        if (page + 1 < viewer.recipes.size()) inventory.setItem(53, icon(Material.ARROW, "Siguiente receta", List.of()));
        player.openInventory(inventory);
    }
    private ItemStack ingredient(RecipeChoice choice) {
        if (choice == null) return null;
        ItemStack item = choice.getItemStack().clone();
        List<Component> alternatives = new ArrayList<>();
        if (choice instanceof RecipeChoice.ItemTypeChoice types && types.itemTypes().size() > 1) {
            alternatives.add(Component.text("Cualquiera de estas opciones:", NamedTextColor.YELLOW));
            types.itemTypes().resolve(RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM)).stream()
                    .limit(12).forEach(type -> alternatives.add(Component.translatable(type.translationKey())));
            if (types.itemTypes().size() > 12) alternatives.add(Component.text("… y " + (types.itemTypes().size() - 12) + " opciones más."));
        } else if (choice instanceof RecipeChoice.MaterialChoice materials && materials.getChoices().size() > 1) {
            alternatives.add(Component.text("Cualquiera de estas opciones:", NamedTextColor.YELLOW));
            materials.getChoices().stream().limit(12).forEach(m -> alternatives.add(Component.translatable(m.translationKey())));
            if (materials.getChoices().size() > 12) alternatives.add(Component.text("… y " + (materials.getChoices().size() - 12) + " opciones más."));
        } else if (choice instanceof RecipeChoice.ExactChoice exact) {
            alternatives.add(Component.text("Requiere el objeto exacto y sus propiedades.", NamedTextColor.YELLOW));
            if (exact.getChoices().size() > 1) alternatives.add(Component.text(exact.getChoices().size() + " variantes válidas."));
        } else if (choice instanceof RecipeChoice.PredicateChoice) {
            alternatives.add(Component.text("Ejemplo: esta receta tiene condiciones especiales.", NamedTextColor.YELLOW));
        }
        if (!alternatives.isEmpty()) item.editMeta(meta -> {
            List<Component> lore = new ArrayList<>(meta.hasLore() ? Objects.requireNonNull(meta.lore()) : List.of());
            lore.addAll(alternatives); meta.lore(lore);
        });
        return item;
    }
    private static ItemStack icon(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> { meta.displayName(Component.text(name, NamedTextColor.AQUA)); meta.lore(lore); });
        return item;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Viewer viewer)) return;
        event.setCancelled(true); // Includes shift-click, number keys, double-click and creative clicks.
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        int target = slot == 45 ? viewer.page - 1 : slot == 53 ? viewer.page + 1 : -1;
        if (slot != 48 && (target < 0 || target >= viewer.recipes.size())) return;
        Inventory original = viewer.inventory;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != original) return;
            if (slot == 48) player.closeInventory();
            else open(player, viewer, target);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Viewer) event.setCancelled(true);
    }
    void closeViewers() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Viewer) player.closeInventory();
    }
    private static final class Viewer implements InventoryHolder {
        final Material material;
        final List<Recipe> recipes;
        Inventory inventory;
        int page;
        Viewer(Material material, List<Recipe> recipes) { this.material = material; this.recipes = recipes; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
