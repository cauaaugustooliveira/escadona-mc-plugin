package me.oredev.escadona.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ArenaDraft {
    private final String id;
    private ResourceKey<Level> dimension;
    private BlockPos deathWait;
    private BlockPos regionPos1;
    private BlockPos regionPos2;
    private final Map<String, LaneDraft> lanes = new LinkedHashMap<>();
    private final Set<Integer> sections = new TreeSet<>();
    private final Map<Integer, RegionDraft> barriers = new LinkedHashMap<>();
    private final RegionDraft finishLine = new RegionDraft();
    private final Map<String, String> settings = new LinkedHashMap<>();

    public ArenaDraft(String id) {
        this.id = id;
        settings.put(Arena.KEEP_INVENTORY_ON_DEATH, "false");
    }

    public static ArenaDraft fromArena(Arena arena) {
        ArenaDraft draft = new ArenaDraft(arena.id());
        draft.dimension = arena.dimension();
        draft.deathWait = arena.deathWait() == null ? null : arena.deathWait().pos();
        draft.regionPos1 = arena.region().min();
        draft.regionPos2 = arena.region().max();
        for (Lane lane : arena.lanes().values()) {
            LaneDraft laneDraft = new LaneDraft(lane.id());
            laneDraft.spawn = lane.spawn().pos();
            for (Section section : lane.sections().values()) {
                draft.sections.add(section.number());
                laneDraft.blocks.put(section.number(), new HashSet<>(section.requiredLuckyBlocks()));
                if (section.checkpoint() != null) {
                    laneDraft.checkpoints.put(section.number(), section.checkpoint().pos());
                }
            }
            draft.lanes.put(lane.id(), laneDraft);
        }
        for (Barrier barrier : arena.barriers()) {
            draft.barriers.put(barrier.afterSection(), RegionDraft.of(barrier.region()));
        }
        if (arena.finishLine() != null) {
            draft.finishLine.copyFrom(arena.finishLine().region());
        }
        draft.settings.putAll(arena.settings());
        return draft;
    }

    public String id() { return id; }
    public ResourceKey<Level> dimension() { return dimension; }
    public BlockPos deathWait() { return deathWait; }
    public BlockPos regionPos1() { return regionPos1; }
    public BlockPos regionPos2() { return regionPos2; }
    public Map<String, LaneDraft> lanes() { return Map.copyOf(lanes); }
    public Set<Integer> sections() { return Set.copyOf(sections); }
    public Map<Integer, RegionDraft> barriers() { return Map.copyOf(barriers); }
    public RegionDraft finishLine() { return finishLine; }
    public Map<String, String> settings() { return Map.copyOf(settings); }

    public void setDimension(ResourceKey<Level> dimension) { this.dimension = dimension; }
    public void setDeathWait(BlockPos deathWait) { this.deathWait = deathWait.immutable(); }
    public void setRegionPos1(BlockPos pos) { this.regionPos1 = pos.immutable(); }
    public void setRegionPos2(BlockPos pos) { this.regionPos2 = pos.immutable(); }

    public void addLane(String laneId) {
        if (lanes.putIfAbsent(laneId, new LaneDraft(laneId)) != null) {
            throw new IllegalArgumentException("Lane already exists: " + laneId);
        }
    }

    public void setLaneSpawn(String laneId, BlockPos spawn) {
        requireLane(laneId).spawn = spawn.immutable();
    }

    public void addSection(int sectionNumber) {
        if (sectionNumber < 1) throw new IllegalArgumentException("Section number must be positive");
        sections.add(sectionNumber);
        lanes.values().forEach(lane -> lane.blocks.computeIfAbsent(sectionNumber, ignored -> new HashSet<>()));
    }

    public void addLuckyBlock(String laneId, int sectionNumber, BlockPos pos) {
        if (!sections.contains(sectionNumber)) throw new IllegalArgumentException("Unknown section: " + sectionNumber);
        requireLane(laneId).blocks.computeIfAbsent(sectionNumber, ignored -> new HashSet<>()).add(pos.immutable());
    }

    public void setLaneCheckpoint(String laneId, int sectionNumber, BlockPos pos) {
        requireSection(sectionNumber);
        requireLane(laneId).checkpoints.put(sectionNumber, pos.immutable());
    }

    public void clearLaneCheckpoint(String laneId, int sectionNumber) {
        requireSection(sectionNumber);
        requireLane(laneId).checkpoints.remove(sectionNumber);
    }

    public void setBarrierPos1(int sectionNumber, BlockPos pos) {
        requireSection(sectionNumber);
        barriers.computeIfAbsent(sectionNumber, ignored -> new RegionDraft()).pos1 = pos.immutable();
    }

    public void setBarrierPos2(int sectionNumber, BlockPos pos) {
        requireSection(sectionNumber);
        barriers.computeIfAbsent(sectionNumber, ignored -> new RegionDraft()).pos2 = pos.immutable();
    }

    public void setFinishPos1(BlockPos pos) { finishLine.pos1 = pos.immutable(); }
    public void setFinishPos2(BlockPos pos) { finishLine.pos2 = pos.immutable(); }
    public void putSetting(String key, String value) { settings.put(key, value); }
    public boolean keepInventoryOnDeath() {
        return Boolean.parseBoolean(settings.getOrDefault(Arena.KEEP_INVENTORY_ON_DEATH, "false"));
    }
    public void setKeepInventoryOnDeath(boolean keepInventory) {
        settings.put(Arena.KEEP_INVENTORY_ON_DEATH, Boolean.toString(keepInventory));
    }

    public ArenaValidation validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (dimension == null) errors.add("dimension is not configured");
        if (regionPos1 == null || regionPos2 == null) errors.add("arena region is incomplete");
        if (lanes.size() < 2) errors.add("at least two lanes are required");
        if (sections.isEmpty()) errors.add("at least one section is required");
        int expectedSection = 1;
        for (int section : sections) {
            if (section != expectedSection) {
                errors.add("sections must be consecutive starting at 1");
                break;
            }
            expectedSection++;
        }

        BlockRegion arenaRegion = regionPos1 != null && regionPos2 != null ? new BlockRegion(regionPos1, regionPos2) : null;
        Set<BlockPos> usedPositions = new HashSet<>();
        for (LaneDraft lane : lanes.values()) {
            if (lane.spawn == null) errors.add("lane " + lane.id + " has no spawn");
            else if (arenaRegion != null && !arenaRegion.contains(lane.spawn)) errors.add("lane " + lane.id + " spawn is outside arena region");
            for (int section : sections) {
                Set<BlockPos> blocks = lane.blocks.getOrDefault(section, Set.of());
                if (blocks.isEmpty()) errors.add("lane " + lane.id + " section " + section + " has no Lucky Blocks");
                for (BlockPos pos : blocks) {
                    if (!usedPositions.add(pos)) errors.add("Lucky Block position is duplicated: " + pos.toShortString());
                    if (arenaRegion != null && !arenaRegion.contains(pos)) errors.add("Lucky Block is outside arena region: " + pos.toShortString());
                }
                BlockPos checkpoint = lane.checkpoints.get(section);
                if (checkpoint != null && arenaRegion != null && !arenaRegion.contains(checkpoint)) {
                    errors.add("checkpoint da lane " + lane.id + " seção " + section + " está fora da região da arena");
                }
            }
        }

        if (!sections.isEmpty()) {
            int finalSection = sections.stream().mapToInt(Integer::intValue).max().orElseThrow();
            for (int section : sections) {
                if (section == finalSection) continue;
                RegionDraft barrier = barriers.get(section);
                if (barrier == null || !barrier.complete()) errors.add("barrier after section " + section + " is incomplete");
            }
        }
        for (Map.Entry<Integer, RegionDraft> entry : barriers.entrySet()) {
            if (!entry.getValue().complete()) errors.add("barrier after section " + entry.getKey() + " is incomplete");
            else if (arenaRegion != null && (!arenaRegion.contains(entry.getValue().pos1) || !arenaRegion.contains(entry.getValue().pos2))) {
                errors.add("barrier after section " + entry.getKey() + " is outside arena region");
            }
        }
        if (!finishLine.complete()) errors.add("finish line is not configured");
        else if (arenaRegion != null && (!arenaRegion.contains(finishLine.pos1) || !arenaRegion.contains(finishLine.pos2))) {
            errors.add("finish line is outside arena region");
        }
        String keepInventory = settings.getOrDefault(Arena.KEEP_INVENTORY_ON_DEATH, "false");
        if (!keepInventory.equalsIgnoreCase("true") && !keepInventory.equalsIgnoreCase("false")) {
            errors.add(Arena.KEEP_INVENTORY_ON_DEATH + " must be true or false");
        }
        return new ArenaValidation(errors, warnings);
    }

    public Arena toArena() {
        ArenaValidation validation = validate();
        if (!validation.isValid()) throw new IllegalStateException(String.join("; ", validation.errors()));
        Map<String, Lane> builtLanes = new LinkedHashMap<>();
        for (LaneDraft lane : lanes.values()) {
            Map<Integer, Section> builtSections = new LinkedHashMap<>();
            for (int number : sections) {
                BlockPos checkpoint = lane.checkpoints.get(number);
                builtSections.put(number, new Section(number, lane.blocks.getOrDefault(number, Set.of()),
                        checkpoint == null ? null : GlobalPos.of(dimension, checkpoint)));
            }
            builtLanes.put(lane.id, new Lane(lane.id, GlobalPos.of(dimension, lane.spawn), builtSections));
        }
        List<Barrier> builtBarriers = barriers.entrySet().stream()
                .filter(entry -> entry.getValue().complete())
                .map(entry -> new Barrier(entry.getKey(), entry.getValue().toRegion()))
                .toList();
        FinishLine builtFinish = finishLine.complete() ? new FinishLine(finishLine.toRegion()) : null;
        GlobalPos builtDeathWait = deathWait == null ? null : GlobalPos.of(dimension, deathWait);
        return new Arena(id, dimension, builtDeathWait, builtLanes, builtBarriers,
                builtFinish, new BlockRegion(regionPos1, regionPos2), settings);
    }

    private LaneDraft requireLane(String laneId) {
        LaneDraft lane = lanes.get(laneId);
        if (lane == null) throw new IllegalArgumentException("Unknown lane: " + laneId);
        return lane;
    }

    private void requireSection(int sectionNumber) {
        if (!sections.contains(sectionNumber)) throw new IllegalArgumentException("Unknown section: " + sectionNumber);
    }

    public static final class LaneDraft {
        private final String id;
        private BlockPos spawn;
        private final Map<Integer, Set<BlockPos>> blocks = new LinkedHashMap<>();
        private final Map<Integer, BlockPos> checkpoints = new LinkedHashMap<>();

        private LaneDraft(String id) { this.id = id; }
        public String id() { return id; }
        public BlockPos spawn() { return spawn; }
        public Map<Integer, Set<BlockPos>> blocks() {
            Map<Integer, Set<BlockPos>> copy = new LinkedHashMap<>();
            blocks.forEach((section, positions) -> copy.put(section, Set.copyOf(positions)));
            return Map.copyOf(copy);
        }
        public Map<Integer, BlockPos> checkpoints() { return Map.copyOf(checkpoints); }
    }

    public static final class RegionDraft {
        private BlockPos pos1;
        private BlockPos pos2;

        public BlockPos pos1() { return pos1; }
        public BlockPos pos2() { return pos2; }
        public boolean complete() { return pos1 != null && pos2 != null; }
        public BlockRegion toRegion() { return new BlockRegion(pos1, pos2); }
        private void copyFrom(BlockRegion region) { pos1 = region.min(); pos2 = region.max(); }
        private static RegionDraft of(BlockRegion region) { RegionDraft draft = new RegionDraft(); draft.copyFrom(region); return draft; }
    }
}
