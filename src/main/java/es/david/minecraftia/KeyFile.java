package es.david.minecraftia;

import java.nio.file.*;
import java.io.IOException;

final class KeyFile {
    static String read(Path path) throws IOException {
        for (String line : Files.readAllLines(path)) {
            String s = line.replace("\uFEFF", "").strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (s.startsWith("export ")) s = s.substring(7).strip();
            if (s.contains("=")) {
                String[] fields = s.split("=", 2);
                String name = fields[0].strip();
                if (!name.equalsIgnoreCase("OPENAI_API_KEY") && !name.equalsIgnoreCase("openai_apikey")) continue;
                s = fields[1].strip().replaceFirst("\\s+#.*$", "");
            } else if (!s.startsWith("sk-")) continue;
            if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'")))
                s = s.substring(1, s.length()-1);
            if (s.matches("sk-[A-Za-z0-9_.-]+")) return s;
        }
        return "";
    }
}
