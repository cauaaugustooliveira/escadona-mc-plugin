package me.oredev.escadona.world;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldNameResolverTest {
    private final WorldNameResolver resolver = new WorldNameResolver();
    private final List<ResourceLocation> worlds = List.of(
            ResourceLocation.parse("minecraft:overworld"),
            ResourceLocation.parse("minecraft:the_nether"),
            ResourceLocation.parse("escadona:arena"));

    @Test
    void resolvesExactNamespacedIdentifier() {
        var result = resolver.resolve("escadona:arena", worlds);
        assertEquals(WorldNameResolver.Status.FOUND, result.status());
        assertEquals(ResourceLocation.parse("escadona:arena"), result.world());
    }

    @Test
    void resolvesUniqueSimpleName() {
        var result = resolver.resolve("arena", worlds);
        assertEquals(WorldNameResolver.Status.FOUND, result.status());
        assertEquals(ResourceLocation.parse("escadona:arena"), result.world());
    }

    @Test
    void reportsMissingWorld() {
        assertEquals(WorldNameResolver.Status.NOT_FOUND, resolver.resolve("the_end", worlds).status());
    }

    @Test
    void rejectsInvalidIdentifierWithoutThrowing() {
        assertEquals(WorldNameResolver.Status.INVALID, resolver.resolve("nome inválido!", worlds).status());
        assertEquals(WorldNameResolver.Status.INVALID, resolver.resolve("bad namespace:arena", worlds).status());
        assertEquals(WorldNameResolver.Status.INVALID, resolver.resolve("../arena", worlds).status());
        assertEquals(WorldNameResolver.Status.INVALID, resolver.resolve("C:\\server\\world", worlds).status());
    }

    @Test
    void reportsAllAmbiguousCandidatesInStableOrder() {
        var result = resolver.resolve("arena", List.of(
                ResourceLocation.parse("outromod:arena"), ResourceLocation.parse("escadona:arena")));
        assertEquals(WorldNameResolver.Status.AMBIGUOUS, result.status());
        assertEquals(List.of(ResourceLocation.parse("escadona:arena"), ResourceLocation.parse("outromod:arena")),
                result.candidates());
    }
}
