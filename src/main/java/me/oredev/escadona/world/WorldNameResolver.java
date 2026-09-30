package me.oredev.escadona.world;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Pure world-name resolution: exact namespaced IDs first, then unique path aliases. */
public final class WorldNameResolver {
    public Resolution resolve(String input, Collection<ResourceLocation> available) {
        if (input == null || input.isBlank()) return Resolution.invalid();
        String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.indexOf('\\') >= 0 || normalized.startsWith("/") || hasTraversalSegment(normalized)) {
            return Resolution.invalid();
        }
        List<ResourceLocation> worlds = available.stream().distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();

        if (normalized.contains(":")) {
            ResourceLocation id = ResourceLocation.tryParse(normalized);
            if (id == null || !isSafeIdentifier(id)) return Resolution.invalid();
            return worlds.contains(id) ? Resolution.found(id) : Resolution.notFound();
        }
        ResourceLocation validation = ResourceLocation.tryParse("minecraft:" + normalized);
        if (validation == null || !isSafeIdentifier(validation)) return Resolution.invalid();
        List<ResourceLocation> matches = worlds.stream().filter(id -> id.getPath().equals(normalized)).toList();
        if (matches.isEmpty()) return Resolution.notFound();
        if (matches.size() > 1) return Resolution.ambiguous(matches);
        return Resolution.found(matches.getFirst());
    }

    private static boolean hasTraversalSegment(String input) {
        String path = input.contains(":") ? input.substring(input.indexOf(':') + 1) : input;
        return java.util.Arrays.stream(path.split("/", -1))
                .anyMatch(segment -> segment.equals(".") || segment.equals(".."));
    }

    static boolean isSafeIdentifier(ResourceLocation id) {
        return id != null && !hasTraversalSegment(id.toString());
    }

    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS, INVALID }

    public record Resolution(Status status, ResourceLocation world, List<ResourceLocation> candidates) {
        public Resolution {
            candidates = List.copyOf(candidates);
        }
        public static Resolution found(ResourceLocation world) { return new Resolution(Status.FOUND, world, List.of(world)); }
        public static Resolution notFound() { return new Resolution(Status.NOT_FOUND, null, List.of()); }
        public static Resolution ambiguous(List<ResourceLocation> candidates) { return new Resolution(Status.AMBIGUOUS, null, candidates); }
        public static Resolution invalid() { return new Resolution(Status.INVALID, null, List.of()); }
    }
}
