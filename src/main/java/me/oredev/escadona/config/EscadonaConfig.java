package me.oredev.escadona.config;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import me.oredev.escadona.scoreboard.ScoreboardLayout;

import java.util.List;

public final class EscadonaConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> LUCKY_BLOCK_ID = BUILDER
            .comment("Registry ID of the official Lucky Block block.")
            .define("luckyBlockId", "lucky:lucky_block", EscadonaConfig::isResourceLocation);

    public static final ModConfigSpec.IntValue TRACKER_SCAN_INTERVAL_TICKS = BUILDER
            .comment("How often pending tracked blocks are checked.")
            .defineInRange("trackerScanIntervalTicks", 20, 1, 20 * 60);

    public static final ModConfigSpec.IntValue TRACKER_RESTORE_DELAY_TICKS = BUILDER
            .comment("Delay before a missing or replaced pending block is restored.")
            .defineInRange("trackerRestoreDelayTicks", 10, 1, 20 * 60);

    public static final ModConfigSpec.IntValue BARRIER_COUNTDOWN_SECONDS = BUILDER
            .comment("Global barrier countdown duration.")
            .defineInRange("barrierCountdownSeconds", 60, 1, 60 * 60);

    public static final ModConfigSpec.IntValue RESPAWN_SECONDS = BUILDER
            .comment("Player respawn countdown duration.")
            .defineInRange("respawnSeconds", 5, 1, 60);

    public static final ModConfigSpec.IntValue MAX_MATCH_MINUTES = BUILDER
            .comment("Maximum match duration.")
            .defineInRange("maxMatchMinutes", 30, 1, 24 * 60);

    public static final ModConfigSpec.IntValue FINISHING_DURATION_SECONDS = BUILDER
            .comment("How long results remain visible before the match closes.")
            .defineInRange("finishingDurationSeconds", 10, 1, 5 * 60);

    public static final ModConfigSpec.IntValue MAX_SNAPSHOT_BLOCKS = BUILDER
            .comment("Maximum inclusive arena-region volume accepted for a physical snapshot.")
            .defineInRange("maxSnapshotBlocks", 2_000_000, 1_000, 50_000_000);

    public static final ModConfigSpec.IntValue MAX_SNAPSHOT_CHUNKS = BUILDER
            .comment("Maximum horizontal chunks loaded while capturing an arena snapshot.")
            .defineInRange("maxSnapshotChunks", 512, 16, 10_000);

    public static final ModConfigSpec.IntValue RESET_BLOCKS_PER_TICK = BUILDER
            .comment("Maximum blocks processed per phase and server tick during arena restoration.")
            .defineInRange("resetBlocksPerTick", 2_000, 1, 100_000);

    static {
        BUILDER.push("lobby");
    }

    public static final ModConfigSpec.IntValue LOBBY_SELECTOR_SLOT = BUILDER
            .comment("Hotbar slot used by the lobby arena selector (0-8).")
            .defineInRange("selectorSlot", 4, 0, 8);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("scoreboard");
    }

    public static final ModConfigSpec.BooleanValue SCOREBOARD_ENABLED = BUILDER
            .comment("Whether Escadona sends a vanilla per-player sidebar in the main lobby and during matches.")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> SCOREBOARD_FOOTER = BUILDER
            .comment("Footer displayed at the bottom of the Escadona sidebar.")
            .define("footer", "Escadona", value -> value instanceof String text && text.length() <= 64);

    public static final ModConfigSpec.IntValue SCOREBOARD_UPDATE_INTERVAL_TICKS = BUILDER
            .comment("Maximum interval between scoreboard refresh passes.")
            .defineInRange("updateIntervalTicks", 20, 1, 20 * 60);

    public static final ModConfigSpec.ConfigValue<String> SCOREBOARD_TITLE = BUILDER
            .comment("Sidebar title. Supports & color/format codes.")
            .define("title", ScoreboardLayout.defaults().title(), EscadonaConfig::isScoreboardText);

    public static final ModConfigSpec.ConfigValue<String> SCOREBOARD_PLACEMENT_LINE = BUILDER
            .comment("Format expanded by {placements}. Placeholders: {rank_color}, {position}, {player}.")
            .define("placementLine", ScoreboardLayout.defaults().placementLine(), EscadonaConfig::isScoreboardText);

    public static final ModConfigSpec.ConfigValue<String> SCOREBOARD_RESULT_LINE = BUILDER
            .comment("Format expanded by {results}. Also supports {time}.")
            .define("resultLine", ScoreboardLayout.defaults().resultLine(), EscadonaConfig::isScoreboardText);

    static {
        BUILDER.push("lines");
    }

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_LOBBY = layout("lobby", ScoreboardLayout.defaults().lobby());
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_WAITING = layout("waiting", ScoreboardLayout.defaults().waiting());
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_STARTING = layout("starting", ScoreboardLayout.defaults().starting());
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_PLAYING = layout("playing", ScoreboardLayout.defaults().playing());
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_FINISHED = layout("finished", ScoreboardLayout.defaults().finished());
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_DEAD_RESPAWNING = layout("deadRespawning", ScoreboardLayout.defaults().deadRespawning());
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOREBOARD_FINISHING = layout("finishing", ScoreboardLayout.defaults().finishing());

    static {
        BUILDER.pop(2);
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private EscadonaConfig() {
    }

    public static ResourceLocation luckyBlockId() {
        return ResourceLocation.parse(LUCKY_BLOCK_ID.get());
    }

    public static boolean scoreboardEnabled() {
        return Boolean.TRUE.equals(configured(SCOREBOARD_ENABLED, true));
    }

    public static int scoreboardUpdateIntervalTicks() {
        return configured(SCOREBOARD_UPDATE_INTERVAL_TICKS, 20);
    }

    public static int lobbySelectorSlot() {
        return configured(LOBBY_SELECTOR_SLOT, 4);
    }

    public static String scoreboardFooter() {
        return configured(SCOREBOARD_FOOTER, "Escadona");
    }

    public static ScoreboardLayout scoreboardLayout() {
        ScoreboardLayout defaults = ScoreboardLayout.defaults();
        return new ScoreboardLayout(configured(SCOREBOARD_TITLE, defaults.title()),
                configured(SCOREBOARD_PLACEMENT_LINE, defaults.placementLine()),
                configured(SCOREBOARD_RESULT_LINE, defaults.resultLine()),
                strings(SCOREBOARD_LOBBY, defaults.lobby()), strings(SCOREBOARD_WAITING, defaults.waiting()),
                strings(SCOREBOARD_STARTING, defaults.starting()), strings(SCOREBOARD_PLAYING, defaults.playing()),
                strings(SCOREBOARD_FINISHED, defaults.finished()),
                strings(SCOREBOARD_DEAD_RESPAWNING, defaults.deadRespawning()),
                strings(SCOREBOARD_FINISHING, defaults.finishing()));
    }

    private static ModConfigSpec.ConfigValue<List<? extends String>> layout(String name, List<String> defaults) {
        return BUILDER.comment("Editable sidebar lines. Empty strings create spacing; placeholders are documented in README.")
                .defineListAllowEmpty(name, defaults, () -> "", EscadonaConfig::isScoreboardText);
    }

    private static List<String> strings(ModConfigSpec.ConfigValue<List<? extends String>> value,
                                        List<String> fallback) {
        List<? extends String> configured = configured(value, fallback);
        return configured.stream().map(String::valueOf).toList();
    }

    private static <T> T configured(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T configured = value.get();
            return configured == null ? fallback : configured;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean isScoreboardText(Object value) {
        return value instanceof String text && text.length() <= 256;
    }

    private static boolean isResourceLocation(Object value) {
        return value instanceof String string && ResourceLocation.tryParse(string) != null;
    }
}
