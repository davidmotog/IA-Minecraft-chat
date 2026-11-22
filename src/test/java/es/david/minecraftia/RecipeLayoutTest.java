package es.david.minecraftia;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeLayoutTest {
    @Test void preservesEmptySlotsInShapedRecipes() {
        assertArrayEquals("DDD S  S ".toCharArray(), RecipeLayout.flatten(new String[]{"DDD", " S ", " S "}));
    }
    @Test void smallRecipeDoesNotWrapRowsIntoTheWrongColumns() {
        assertArrayEquals("WW WW    ".toCharArray(), RecipeLayout.flatten(new String[]{"WW", "WW"}));
        assertArrayEquals("A        ".toCharArray(), RecipeLayout.flatten(new String[]{"A"}));
    }
}
