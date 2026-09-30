package me.oredev.escadona.reset;

import com.mojang.serialization.DynamicOps;
import me.oredev.escadona.arena.BlockRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Palette-compressed snapshot storage with lightweight sidecar metadata and file checksum. */
public final class ArenaSnapshotRepository {
    private static final String SNAPSHOT_FILE = "snapshot.nbt";
    private static final String METADATA_FILE = "metadata.nbt";
    private final Path directory;

    public ArenaSnapshotRepository(Path directory) { this.directory = directory; }

    public boolean exists(String arenaId) { return Files.isRegularFile(file(arenaId)); }

    public void save(ArenaSnapshot snapshot, HolderLookup.Provider registries, boolean replace) throws IOException {
        Path target = file(snapshot.metadata().arenaId());
        Path metadataTarget = metadataFile(snapshot.metadata().arenaId());
        if (Files.exists(target) && !replace) {
            throw new IllegalStateException("Snapshot já existe; apague-o explicitamente antes de recriar");
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Path metadataTemporary = metadataTarget.resolveSibling(metadataTarget.getFileName() + ".tmp");
        NbtIo.writeCompressed(encode(snapshot, registries), temporary);
        CompoundTag sidecar = encodeMetadata(snapshot.metadata());
        sidecar.putString("sha256", fileChecksum(temporary));
        NbtIo.writeCompressed(sidecar, metadataTemporary);
        atomicReplace(temporary, target);
        atomicReplace(metadataTemporary, metadataTarget);
    }

    public ArenaSnapshot load(String arenaId, HolderLookup.Provider registries) throws IOException {
        ArenaSnapshot.Metadata expected = readValidatedMetadata(arenaId);
        CompoundTag payload = NbtIo.readCompressed(file(arenaId), NbtAccounter.unlimitedHeap());
        ArenaSnapshot snapshot = decode(payload, registries);
        if (!snapshot.metadata().equals(expected)) {
            throw new IllegalArgumentException("Metadata lateral não corresponde ao payload do snapshot");
        }
        return snapshot;
    }

    /** Reads bounds/integrity without materializing the block payload. */
    public ArenaSnapshot.Metadata metadata(String arenaId, HolderLookup.Provider registries) throws IOException {
        return readValidatedMetadata(arenaId);
    }

    private ArenaSnapshot.Metadata readValidatedMetadata(String arenaId) throws IOException {
        Path snapshot = file(arenaId);
        Path sidecar = metadataFile(arenaId);
        if (!Files.isRegularFile(snapshot)) {
            throw new IllegalStateException("A Arena não possui um snapshot válido. Use /escadona arena snapshot create "
                    + arenaId + ".");
        }
        if (!Files.isRegularFile(sidecar)) {
            throw new IllegalStateException("Snapshot legado ou incompleto. Use /escadona arena snapshot delete "
                    + arenaId + " e recrie-o no formato compacto.");
        }
        CompoundTag metadataTag = NbtIo.readCompressed(sidecar, NbtAccounter.unlimitedHeap());
        ArenaSnapshot.Metadata metadata = decodeMetadata(metadataTag);
        String expectedHash = metadataTag.getStringOr("sha256", "");
        String actualHash = fileChecksum(snapshot);
        if (expectedHash.isBlank() || !actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalArgumentException("Checksum do snapshot é inválido");
        }
        return metadata;
    }

    public void delete(String arenaId) throws IOException {
        Files.deleteIfExists(file(arenaId));
        Files.deleteIfExists(metadataFile(arenaId));
        Path arenaDirectory = directory.resolve(arenaId);
        if (Files.isDirectory(arenaDirectory)) {
            try (var entries = Files.list(arenaDirectory)) {
                if (entries.findAny().isEmpty()) Files.deleteIfExists(arenaDirectory);
            }
        }
    }

    private Path file(String arenaId) { return directory.resolve(arenaId).resolve(SNAPSHOT_FILE); }
    private Path metadataFile(String arenaId) { return directory.resolve(arenaId).resolve(METADATA_FILE); }

    private static CompoundTag encode(ArenaSnapshot snapshot, HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        CompoundTag payload = encodeMetadata(snapshot.metadata());
        ListTag palette = new ListTag();
        snapshot.palette().forEach(state -> palette.add(BlockState.CODEC.encodeStart(ops, state).getOrThrow()));
        payload.put("palette", palette);
        payload.putIntArray("states", snapshot.stateIndices());
        ListTag blockEntities = new ListTag();
        snapshot.blockEntities().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag encoded = new CompoundTag();
            encoded.putInt("index", entry.getKey());
            encoded.put("data", entry.getValue());
            blockEntities.add(encoded);
        });
        payload.put("blockEntities", blockEntities);
        return payload;
    }

    private static ArenaSnapshot decode(CompoundTag payload, HolderLookup.Provider registries) {
        ArenaSnapshot.Metadata metadata = decodeMetadata(payload);
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag encodedPalette = payload.getList("palette")
                .orElseThrow(() -> new IllegalArgumentException("Snapshot sem paleta"));
        List<BlockState> palette = new ArrayList<>(encodedPalette.size());
        for (Tag encodedState : encodedPalette) {
            palette.add(BlockState.CODEC.parse(ops, encodedState).getOrThrow());
        }
        int[] states = payload.getIntArray("states")
                .orElseThrow(() -> new IllegalArgumentException("Snapshot sem índices de estado"));
        Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        ListTag encodedEntities = payload.getList("blockEntities").orElse(new ListTag());
        for (int index = 0; index < encodedEntities.size(); index++) {
            CompoundTag encoded = encodedEntities.getCompound(index)
                    .orElseThrow(() -> new IllegalArgumentException("Block Entity inválida"));
            int blockIndex = encoded.getIntOr("index", -1);
            CompoundTag data = encoded.getCompound("data")
                    .orElseThrow(() -> new IllegalArgumentException("Dados de Block Entity ausentes"));
            if (blockEntities.put(blockIndex, data) != null) {
                throw new IllegalArgumentException("Block Entity duplicada no snapshot");
            }
        }
        return new ArenaSnapshot(metadata, palette, states, blockEntities);
    }

    private static CompoundTag encodeMetadata(ArenaSnapshot.Metadata metadata) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schemaVersion", metadata.schemaVersion());
        tag.putString("format", metadata.format());
        tag.putString("arenaId", metadata.arenaId());
        tag.putString("dimension", metadata.dimension().location().toString());
        tag.putLong("createdAt", metadata.createdAt().toEpochMilli());
        tag.putLong("blockCount", metadata.blockCount());
        putPos(tag, "min", metadata.region().min());
        putPos(tag, "max", metadata.region().max());
        return tag;
    }

    private static ArenaSnapshot.Metadata decodeMetadata(CompoundTag tag) {
        int schema = tag.getIntOr("schemaVersion", -1);
        String format = tag.getStringOr("format", "");
        String arenaId = tag.getStringOr("arenaId", "");
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(tag.getStringOr("dimension", "minecraft:overworld")));
        BlockRegion region = new BlockRegion(readPos(tag, "min"), readPos(tag, "max"));
        long count = tag.getLongOr("blockCount", -1);
        if (count != region.volume()) throw new IllegalArgumentException("Snapshot não cobre a região completa");
        return new ArenaSnapshot.Metadata(schema, format, arenaId, dimension, region, count,
                Instant.ofEpochMilli(tag.getLongOr("createdAt", 0)));
    }

    private static void putPos(CompoundTag parent, String key, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        parent.put(key, tag);
    }

    private static BlockPos readPos(CompoundTag parent, String key) {
        CompoundTag tag = parent.getCompound(key)
                .orElseThrow(() -> new IllegalArgumentException("Bounds ausentes: " + key));
        return new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
    }

    private static String fileChecksum(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
