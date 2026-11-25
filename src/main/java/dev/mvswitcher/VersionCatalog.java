package dev.mvswitcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * Known version profiles. They are data, not code: adding a version means adding an entry to
 * {@code src/main/resources/versions/index.json}.
 */
public final class VersionCatalog {
    private static final String RESOURCE = "/versions/index.json";
    private static final List<VersionProfile> PROFILES = load();

    private VersionCatalog() {}

    public static List<VersionProfile> all() { return PROFILES; }

    /** First profile whose Minecraft version or id matches. */
    public static VersionProfile find(String version) {
        return PROFILES.stream()
                .filter(p -> p.minecraft().equals(version) || p.id().equals(version))
                .findFirst().orElse(null);
    }

    /** Profile for a specific loader, matched by Minecraft version or id. */
    public static VersionProfile find(String version, String loader) {
        return PROFILES.stream()
                .filter(p -> p.loader().equalsIgnoreCase(loader))
                .filter(p -> p.minecraft().equals(version) || p.id().equals(version))
                .findFirst().orElse(null);
    }

    static List<VersionProfile> parse(InputStream input, String label) throws IOException {
        JsonNode root = new ObjectMapper().readTree(input);
        if (root == null || !root.isObject() || !root.path("profiles").isArray()) {
            throw new IOException("Version catalog must contain a 'profiles' array: " + label);
        }
        List<VersionProfile> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode node : root.get("profiles")) {
            String minecraft = text(node, "minecraft", label);
            String loader = text(node, "loader", label).toLowerCase(Locale.ROOT);
            String id = node.hasNonNull("id") ? node.get("id").asText() : minecraft;
            String loaderVersion = node.hasNonNull("loaderVersion") ? node.get("loaderVersion").asText() : null;
            if (!node.path("java").isInt() || node.get("java").asInt() < 1) {
                throw new IOException("Profile " + id + " must set a positive integer 'java': " + label);
            }
            if (!keys.add(loader + ":" + id)) {
                throw new IOException("Duplicate profile " + id + " for loader " + loader + ": " + label);
            }
            Map<String, String> properties = new LinkedHashMap<>();
            JsonNode extra = node.path("properties");
            if (!extra.isMissingNode() && !extra.isNull()) {
                if (!extra.isObject()) throw new IOException("Profile " + id + ": 'properties' must be an object: " + label);
                for (var field : extra.properties()) {
                    if (!field.getKey().matches("[A-Za-z0-9_.-]+") || !field.getValue().isTextual()) {
                        throw new IOException("Profile " + id + ": every property needs a simple name and a string value: " + label);
                    }
                    properties.put(field.getKey(), field.getValue().asText());
                }
            }
            result.add(new VersionProfile(id, minecraft, loader, loaderVersion, node.get("java").asInt(), properties));
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field, String label) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException("Every profile must set '" + field + "': " + label);
        return value;
    }

    private static List<VersionProfile> load() {
        try (InputStream input = VersionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Bundled version catalog is missing: " + RESOURCE);
            return parse(input, RESOURCE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load version catalog: " + e.getMessage(), e);
        }
    }
}
