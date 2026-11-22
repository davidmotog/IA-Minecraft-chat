package es.david.minecraftia;

final class RecipeLayout {
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private RecipeLayout() {}
    static char[] flatten(String[] shape) {
        char[] grid = new char[9];
        java.util.Arrays.fill(grid, ' ');
        if (shape.length > 3) throw new IllegalArgumentException("Too many rows");
        for (int row = 0; row < shape.length; row++) {
            if (shape[row].length() > 3) throw new IllegalArgumentException("Too many columns");
            for (int col = 0; col < shape[row].length(); col++) grid[row * 3 + col] = shape[row].charAt(col);
        }
        return grid;
    }
}
