package me.oredev.escadona.world;

import me.oredev.escadona.Escadona;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Validates, copies, persists and materializes ordinary Overworld save folders. */
public final class WorldImportManager implements AutoCloseable {
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final long MAX_LEVEL_DAT_BYTES = 100L * 1024L * 1024L;
    private static final List<String> COPIED_DIRECTORIES = List.of("region", "entities", "poi");

    private final JsonWorldRegistry repository;
    private final Path serverRoot;
    private final Path dedicatedImports;
    private final DynamicWorldLoader loader;
    private final ExecutorService copyWorker;
    private final Map<String, ImportedWorld> worlds = new LinkedHashMap<>();
    private final Set<String> activeImports = new LinkedHashSet<>();
    private final Map<String, CompletableFuture<Void>> spawnPreparations = new ConcurrentHashMap<>();

    public WorldImportManager(JsonWorldRegistry repository, Path serverRoot) {
        this(repository, serverRoot, new DynamicWorldLoader());
    }

    WorldImportManager(JsonWorldRegistry repository, Path serverRoot, DynamicWorldLoader loader) {
        this.repository = repository;
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.dedicatedImports = this.serverRoot.resolve("escadona").resolve("world-imports").normalize();
        this.loader = loader;
        this.copyWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Escadona world importer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public StartupResult loadRegisteredAtStartup(MinecraftServer server) throws IOException {
        synchronized (this) {
            worlds.clear();
            worlds.putAll(repository.load());
        }
        int loaded = 0;
        int invalid = 0;
        boolean changed = false;
        for (ImportedWorld entry : entries()) {
            Path storage = loader.storagePath(server, entry);
            try {
                ImportedWorld current = entry;
                if (entry.state() == WorldImportState.IMPORTING) {
                    if (looksLikeImportedStorage(storage)) {
                        current = entry.withState(WorldImportState.IMPORTED, null);
                        put(current);
                        changed = true;
                    } else {
                        current = entry.withState(WorldImportState.INVALID,
                                "Importação interrompida antes da publicação do storage.");
                        put(current);
                        changed = true;
                    }
                }
                if (current.state() == WorldImportState.IMPORTED || current.state() == WorldImportState.LOADED) {
                    if (!looksLikeImportedStorage(storage)) {
                        put(current.withState(WorldImportState.INVALID, "Storage importado ausente ou incompleto."));
                        invalid++;
                        changed = true;
                        continue;
                    }
                    var level = loader.load(server, current);
                    scheduleSpawnPreparation(level, current);
                    if (current.state() != WorldImportState.LOADED) {
                        put(current.withState(WorldImportState.LOADED, null));
                        changed = true;
                    }
                    loaded++;
                } else {
                    invalid++;
                }
            } catch (Exception exception) {
                put(entry.withState(WorldImportState.INVALID, concise(exception)));
                invalid++;
                changed = true;
                Escadona.LOGGER.error("Could not load imported world {} during startup", entry.worldId(), exception);
            }
        }
        if (changed) persist();
        return new StartupResult(entries().size(), loaded, invalid);
    }

    /** Starts filesystem work and calls completion back on the Minecraft server thread. */
    public LoadResult load(MinecraftServer server, String rawName, Consumer<LoadResult> completion) {
        String name = normalizeName(rawName);
        if (!isSafeName(name)) return result(LoadStatus.INVALID_NAME, null, "Nome de mundo inválido: '" + rawName + "'.");
        ImportedWorld registered;
        synchronized (this) {
            registered = worlds.get(name);
            if (registered != null && server.getLevel(registered.dimensionKey()) != null) {
                return result(LoadStatus.ALREADY_LOADED, registered,
                        "O mundo '" + name + "' já está carregado.");
            }
            if (activeImports.contains(name)) {
                return result(LoadStatus.DUPLICATE, registered,
                        "Já existe uma importação Escadona chamada '" + name + "'.");
            }
        }
        if (registered != null && registered.state() == WorldImportState.INVALID) {
            Path registeredStorage = loader.storagePath(server, registered);
            if (looksLikeImportedStorage(registeredStorage)) {
                try {
                    loader.load(server, registered);
                    ImportedWorld loaded = registered.withState(WorldImportState.LOADED, null);
                    synchronized (this) { put(loaded); persist(); }
                    return result(LoadStatus.LOADED, loaded,
                            "Mapa '" + name + "' carregado com sucesso a partir do storage já importado.");
                } catch (Exception exception) {
                    return result(LoadStatus.FAILED, registered,
                            "O storage importado de '" + name + "' continua inválido: " + concise(exception));
                }
            }
            try {
                synchronized (this) {
                    worlds.remove(name);
                    persist();
                }
                registered = null;
            } catch (IOException exception) {
                return result(LoadStatus.FAILED, registered,
                        "Não foi possível preparar uma nova tentativa: " + concise(exception));
            }
        }
        if (registered != null) {
            return result(LoadStatus.DUPLICATE, registered,
                    "Já existe uma importação Escadona chamada '" + name + "'.");
        }

        final Path source;
        final SaveMetadata metadata;
        final ImportedWorld importing;
        final Path destination;
        try {
            source = resolveSource(name);
            if (source == null) return result(LoadStatus.NOT_FOUND, null,
                    "Nenhuma pasta de mundo chamada '" + name + "' foi encontrada.");
            validateSourceLocation(server, source);
            metadata = readSaveMetadata(source);
            ResourceLocation id = worldId(name);
            destination = loader.storagePath(server, placeholder(name, metadata));
            importing = new ImportedWorld(name, id, relativeDisplay(source),
                    relativeDisplay(destination),
                    WorldImportState.IMPORTING, Instant.now(), metadata.dataVersion(), metadata.seed(),
                    metadata.spawn(), metadata.spawnAngle(), metadata.gameTime(), metadata.dayTime(), null);
            synchronized (this) {
                activeImports.add(name);
                worlds.put(name, importing);
                persist();
            }
        } catch (InvalidSaveException exception) {
            return result(LoadStatus.INVALID_SAVE, null,
                    "A pasta '" + name + "' não contém um save Minecraft válido: " + exception.getMessage());
        } catch (Exception exception) {
            Escadona.LOGGER.error("Could not prepare world import {}", name, exception);
            return result(LoadStatus.FAILED, null, "Falha ao preparar a importação de '" + name + "': " + concise(exception));
        }

        copyWorker.execute(() -> copyAndActivate(server, source, destination, importing, completion));
        return result(LoadStatus.IMPORTING, importing,
                "Importação de '" + name + "' iniciada. O mapa está sendo copiado em segundo plano.");
    }

    private void copyAndActivate(MinecraftServer server, Path source, Path destination, ImportedWorld importing,
                                 Consumer<LoadResult> completion) {
        Path temporary = destination.resolveSibling("." + importing.name() + ".importing");
        try {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("o storage de destino já existe: " + destination);
            }
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) deleteTree(temporary);
            Files.createDirectories(temporary);
            for (String directory : COPIED_DIRECTORIES) {
                Path candidate = source.resolve(directory);
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    copyTree(candidate, temporary.resolve(directory));
                }
            }
            if (!looksLikeImportedStorage(temporary)) throw new IOException("a cópia não contém dados regionais");
            Files.createDirectories(destination.getParent());
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination);
            }
            ImportedWorld imported = importing.withState(WorldImportState.IMPORTED, null);
            synchronized (this) { put(imported); persist(); }
            server.execute(() -> activate(server, imported, completion));
        } catch (Exception exception) {
            fail(importing, exception);
            server.execute(() -> completion.accept(result(LoadStatus.FAILED, importing,
                    "Falha ao importar '" + importing.name() + "': " + concise(exception))));
        }
    }

    private void activate(MinecraftServer server, ImportedWorld imported, Consumer<LoadResult> completion) {
        try {
            var level = loader.load(server, imported);
            ImportedWorld loaded = imported.withState(WorldImportState.LOADED, null);
            synchronized (this) {
                activeImports.remove(imported.name());
                put(loaded);
                persist();
            }
            Escadona.LOGGER.info("Imported and hot-loaded world {} from {}", loaded.worldId(), loaded.origin());
            scheduleSpawnPreparation(level, loaded).whenComplete((ignored, error) ->
                    server.execute(() -> {
                        if (error == null) {
                            Escadona.LOGGER.info("Prepared imported world spawn {} asynchronously", loaded.worldId());
                            completion.accept(result(LoadStatus.LOADED, loaded,
                                    "Mapa '" + loaded.name() + "' importado e carregado com sucesso."));
                        } else {
                            Escadona.LOGGER.error("Imported world {} loaded, but asynchronous spawn preparation failed",
                                    loaded.worldId(), error);
                            completion.accept(result(LoadStatus.LOADED, loaded,
                                    "Mapa '" + loaded.name() + "' foi carregado, mas o spawn não pôde ser pré-carregado: "
                                            + concise(error)));
                        }
                    }));
        } catch (Exception exception) {
            fail(imported, exception);
            completion.accept(result(LoadStatus.FAILED, imported,
                    "O mapa foi copiado, mas não pôde ser carregado: " + concise(exception)));
        }
    }

    private void fail(ImportedWorld imported, Exception exception) {
        Escadona.LOGGER.error("World import failed for {}", imported.worldId(), exception);
        synchronized (this) {
            activeImports.remove(imported.name());
            put(imported.withState(WorldImportState.INVALID, concise(exception)));
            try { persist(); } catch (IOException saveError) {
                Escadona.LOGGER.error("Could not persist failed world import {}", imported.worldId(), saveError);
            }
        }
    }

    public WorldList list(MinecraftServer server) {
        List<WorldListEntry> result = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        for (ImportedWorld entry : entries()) {
            known.add(entry.name());
            WorldListState state = server.getLevel(entry.dimensionKey()) != null ? WorldListState.LOADED
                    : switch (entry.state()) {
                        case IMPORTING -> WorldListState.IMPORTING;
                        case IMPORTED -> WorldListState.IMPORTED;
                        case LOADED -> WorldListState.RESTART_REQUIRED;
                        case INVALID -> WorldListState.INVALID;
                    };
            result.add(new WorldListEntry(entry.name(), state, entry.error()));
        }
        for (String available : availableImportNames()) {
            if (known.add(available)) result.add(new WorldListEntry(available, WorldListState.IMPORT_AVAILABLE, null));
        }
        result.sort(Comparator.comparing(WorldListEntry::name));
        return new WorldList(result);
    }

    public List<String> availableImportNames() {
        Set<String> names = new LinkedHashSet<>();
        discover(serverRoot, names);
        discover(dedicatedImports, names);
        return names.stream().sorted().toList();
    }

    public synchronized List<ImportedWorld> entries() {
        return worlds.values().stream().sorted(Comparator.comparing(ImportedWorld::name)).toList();
    }

    public synchronized ImportedWorld find(String name) { return worlds.get(normalizeName(name)); }

    /** Renews a temporary chunk ticket and prepares the spawn before world tp inspects blocks. */
    public CompletableFuture<Void> prepareSpawnForTeleport(MinecraftServer server, String input) {
        String name = normalizeName(input);
        if (name.startsWith(Escadona.MOD_ID + ":")) name = name.substring(Escadona.MOD_ID.length() + 1);
        ImportedWorld imported = find(name);
        if (imported == null) return null;
        var level = server.getLevel(imported.dimensionKey());
        if (level == null) return null;
        CompletableFuture<Void> active = spawnPreparations.get(name);
        CompletableFuture<Void> preparation = active != null && !active.isDone()
                ? active : scheduleSpawnPreparation(level, imported);
        CompletableFuture<Void> retained = new CompletableFuture<>();
        preparation.whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) {
                retained.completeExceptionally(error);
            } else {
                loader.retainSpawnForTeleport(level, imported);
                retained.complete(null);
            }
        }));
        return retained;
    }

    private CompletableFuture<Void> scheduleSpawnPreparation(net.minecraft.server.level.ServerLevel level,
                                                              ImportedWorld imported) {
        CompletableFuture<Void> preparation = new CompletableFuture<>();
        spawnPreparations.put(imported.name(), preparation);
        loader.retainSpawnForTeleport(level, imported);
        copyWorker.execute(() -> {
            try {
                loader.prepareSpawnAsync(level, imported).whenComplete((ignored, error) -> {
                    if (error == null) preparation.complete(null);
                    else preparation.completeExceptionally(error);
                });
            } catch (Throwable error) {
                preparation.completeExceptionally(error);
            }
        });
        return preparation;
    }

    Path resolveSource(String name) throws IOException {
        if (!isSafeName(name)) return null;
        Path dedicated = directChild(dedicatedImports, name);
        if (Files.isDirectory(dedicated, LinkOption.NOFOLLOW_LINKS)) return dedicated.toRealPath();
        Path root = directChild(serverRoot, name);
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return root.toRealPath();
        return null;
    }

    static boolean isSafeName(String name) { return name != null && SAFE_NAME.matcher(name).matches(); }

    static ResourceLocation worldId(String name) {
        if (!isSafeName(name)) throw new IllegalArgumentException("Unsafe imported world name: " + name);
        return ResourceLocation.fromNamespaceAndPath(Escadona.MOD_ID, name);
    }

    static SaveMetadata readSaveMetadata(Path source) throws IOException, InvalidSaveException {
        if (Files.isSymbolicLink(source)) throw new InvalidSaveException("links simbólicos não são permitidos");
        Path levelDat = source.resolve("level.dat");
        if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)) throw new InvalidSaveException("level.dat ausente");
        if (!Files.isDirectory(source.resolve("region"), LinkOption.NOFOLLOW_LINKS)) throw new InvalidSaveException("diretório region ausente");
        if (Files.size(levelDat) > MAX_LEVEL_DAT_BYTES) throw new InvalidSaveException("level.dat excede o limite de segurança");
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.create(MAX_LEVEL_DAT_BYTES));
            CompoundTag data = root.getCompound("Data").orElseThrow(() -> new InvalidSaveException("tag Data ausente"));
            int dataVersion = data.getIntOr("DataVersion", 0);
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            if (dataVersion <= 0 || dataVersion > current) {
                throw new InvalidSaveException("DataVersion incompatível: " + dataVersion + " (servidor: " + current + ")");
            }
            CompoundTag worldGen = data.getCompound("WorldGenSettings").orElse(null);
            long seed = worldGen == null ? data.getLongOr("RandomSeed", 0L) : worldGen.getLongOr("seed", 0L);
            BlockPos spawn = new BlockPos(data.getIntOr("SpawnX", 0), data.getIntOr("SpawnY", 64),
                    data.getIntOr("SpawnZ", 0));
            float angle = data.getFloatOr("SpawnAngle", 0.0F);
            return new SaveMetadata(dataVersion, seed, spawn, angle,
                    data.getLongOr("Time", 0L), data.getLongOr("DayTime", 0L));
        } catch (InvalidSaveException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidSaveException("level.dat ilegível: " + concise(exception), exception);
        }
    }

    private void validateSourceLocation(MinecraftServer server, Path source) throws IOException, InvalidSaveException {
        Path real = source.toRealPath();
        Path parent = real.getParent();
        Path serverReal = serverRoot.toRealPath();
        Path dedicatedReal = Files.exists(dedicatedImports) ? dedicatedImports.toRealPath() : dedicatedImports;
        if (!parent.equals(serverReal) && !parent.equals(dedicatedReal)) {
            throw new InvalidSaveException("a pasta escapa dos diretórios de importação permitidos");
        }
        Path primary = server.getWorldPath(LevelResource.ROOT).toRealPath();
        if (real.equals(primary)) throw new InvalidSaveException("o mundo principal não pode ser importado");
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) throw new IOException("link simbólico rejeitado: " + directory);
                Path relative = source.relativize(directory);
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("entrada de filesystem não permitida: " + file);
                }
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void discover(Path parent, Set<String> names) {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        try (var children = Files.list(parent)) {
            children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(WorldImportManager::isSafeName)
                    .filter(name -> Files.isRegularFile(parent.resolve(name).resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
                    .forEach(names::add);
        } catch (IOException exception) {
            Escadona.LOGGER.warn("Could not scan world import directory {}", parent, exception);
        }
    }

    private synchronized void put(ImportedWorld world) { worlds.put(world.name(), world); }
    private synchronized void persist() throws IOException { repository.save(worlds.values()); }
    private ImportedWorld placeholder(String name, SaveMetadata metadata) {
        return new ImportedWorld(name, worldId(name), "", "", WorldImportState.IMPORTING, Instant.EPOCH,
                metadata.dataVersion(), metadata.seed(), metadata.spawn(), metadata.spawnAngle(),
                metadata.gameTime(), metadata.dayTime(), null);
    }
    private String relativeDisplay(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(serverRoot) ? serverRoot.relativize(normalized).toString().replace('\\', '/') : normalized.toString();
    }
    private static Path directChild(Path parent, String name) throws IOException {
        Path child = parent.resolve(name).normalize();
        if (!child.getParent().equals(parent.normalize())) throw new IOException("path traversal rejected");
        return child;
    }
    private static boolean looksLikeImportedStorage(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(path.resolve("region"), LinkOption.NOFOLLOW_LINKS);
    }
    private static String normalizeName(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String concise(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
    private static LoadResult result(LoadStatus status, ImportedWorld world, String message) {
        return new LoadResult(status, world, message);
    }

    @Override public void close() { copyWorker.shutdownNow(); }

    public enum LoadStatus { IMPORTING, LOADED, ALREADY_LOADED, NOT_FOUND, INVALID_NAME, INVALID_SAVE, DUPLICATE, FAILED }
    public enum WorldListState { IMPORT_AVAILABLE, IMPORTING, IMPORTED, RESTART_REQUIRED, LOADED, INVALID }
    public record LoadResult(LoadStatus status, ImportedWorld world, String message) {
        public boolean accepted() { return status == LoadStatus.IMPORTING || status == LoadStatus.LOADED || status == LoadStatus.ALREADY_LOADED; }
    }
    public record WorldListEntry(String name, WorldListState state, String detail) { }
    public record WorldList(List<WorldListEntry> entries) { public WorldList { entries = List.copyOf(entries); } }
    public record StartupResult(int registered, int loaded, int invalid) { }
    record SaveMetadata(int dataVersion, long seed, BlockPos spawn, float spawnAngle,
                        long gameTime, long dayTime) { }

    static final class InvalidSaveException extends Exception {
        InvalidSaveException(String message) { super(message); }
        InvalidSaveException(String message, Throwable cause) { super(message, cause); }
    }
}
