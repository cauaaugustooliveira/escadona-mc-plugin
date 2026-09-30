package me.oredev.escadona.reset;

import me.oredev.escadona.arena.Arena;

/** Pure pre-flight rule shared by match start and tests. */
public final class ArenaSnapshotValidator {
    private ArenaSnapshotValidator() { }

    public static void requireCompatible(Arena arena, ArenaSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException("A Arena não possui um snapshot válido. Use /escadona arena snapshot create "
                    + arena.id() + ".");
        }
        requireMetadataCompatible(arena, snapshot.metadata());
    }

    public static void requireMetadataCompatible(Arena arena, ArenaSnapshot.Metadata metadata) {
        if (metadata == null) {
            throw new IllegalStateException("A Arena não possui um snapshot válido. Use /escadona arena snapshot create "
                    + arena.id() + ".");
        }
        if (!metadata.arenaId().equals(arena.id())
                || !metadata.dimension().equals(arena.dimension())
                || !metadata.region().equals(arena.region())) {
            throw new IllegalStateException("O snapshot não corresponde à dimensão ou região atual. Recrie-o explicitamente.");
        }
    }
}
