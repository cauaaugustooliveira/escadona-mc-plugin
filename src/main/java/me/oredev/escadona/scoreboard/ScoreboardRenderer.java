package me.oredev.escadona.scoreboard;

import me.oredev.escadona.arena.Lane;
import me.oredev.escadona.barrier.BarrierCountdown;
import me.oredev.escadona.barrier.BarrierState;
import me.oredev.escadona.game.Match;
import me.oredev.escadona.game.PlayerState;
import me.oredev.escadona.game.RacePlayer;
import me.oredev.escadona.luckyblock.LuckyBlockTracker;
import me.oredev.escadona.respawn.RespawnManager;
import me.oredev.escadona.stats.PlayerStatistics;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Pure presentation layer that applies configurable templates to authoritative match state. */
public final class ScoreboardRenderer {
    public ScoreboardView renderLobby(int onlinePlayers, String footer, ScoreboardLayout layout,
                                      PlayerStatistics statistics) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("online_players", Integer.toString(onlinePlayers));
        values.put("footer", footer);
        values.put("kills", Integer.toString(statistics.kills()));
        values.put("wins", Integer.toString(statistics.wins()));
        return new ScoreboardView(LegacyTextFormatter.parse(substitute(layout.title(), values)),
                renderLines(layout.lobby(), values, Map.of()));
    }

    public ScoreboardView render(Match match, RacePlayer player, LuckyBlockTracker tracker,
                                 Optional<RespawnManager.PendingRespawn> pendingRespawn,
                                 long currentTick, Instant now, Function<UUID, String> names, String footer) {
        return render(match, player, tracker, pendingRespawn, currentTick, now, names, footer,
                ScoreboardLayout.defaults(), PlayerStatistics.EMPTY);
    }

    public ScoreboardView render(Match match, RacePlayer player, LuckyBlockTracker tracker,
                                 Optional<RespawnManager.PendingRespawn> pendingRespawn,
                                 long currentTick, Instant now, Function<UUID, String> names, String footer,
                                 ScoreboardLayout layout) {
        return render(match, player, tracker, pendingRespawn, currentTick, now, names, footer, layout,
                PlayerStatistics.EMPTY);
    }

    public ScoreboardView render(Match match, RacePlayer player, LuckyBlockTracker tracker,
                                 Optional<RespawnManager.PendingRespawn> pendingRespawn,
                                 long currentTick, Instant now, Function<UUID, String> names, String footer,
                                 ScoreboardLayout layout, PlayerStatistics statistics) {
        Map<String, String> values = baseValues(match, footer, statistics);
        Map<String, List<String>> expansions = new LinkedHashMap<>();
        List<String> template;
        switch (match.state()) {
            case WAITING -> template = layout.waiting();
            case STARTING -> {
                values.put("starting_seconds", Integer.toString(match.startingRemainingSeconds(currentTick)));
                template = layout.starting();
            }
            case PLAYING -> {
                values.put("elapsed", formatDuration(match.elapsed(now)));
                expansions.put("placements", placements(match, names, true, layout.placementLine()));
                if (player.state() == PlayerState.FINISHED) {
                    values.put("position", ordinal(player.placement().orElse(0)));
                    values.put("finish_time", formatDuration(player.finishTime().orElse(Duration.ZERO)));
                    template = layout.finished();
                } else if (player.state() == PlayerState.DEAD_RESPAWNING
                        && pendingRespawn.filter(ticket -> !ticket.matchEnded()).isPresent()) {
                    long ticks = Math.max(0, pendingRespawn.orElseThrow().dueAtTick() - currentTick);
                    values.put("respawn_seconds", Long.toString((long) Math.ceil(ticks / 20.0D)));
                    template = layout.deadRespawning();
                } else {
                    addPlayingValues(values, match, player, tracker, currentTick);
                    template = layout.playing();
                }
            }
            case FINISHING -> {
                expansions.put("results", placements(match, names, false, layout.resultLine()));
                template = layout.finishing();
            }
            default -> throw new IllegalStateException("Unsupported game state: " + match.state());
        }
        return new ScoreboardView(LegacyTextFormatter.parse(substitute(layout.title(), values)),
                renderLines(template, values, expansions));
    }

    private static Map<String, String> baseValues(Match match, String footer, PlayerStatistics statistics) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("players", Integer.toString(match.players().size()));
        values.put("max_players", Integer.toString(match.arena().lanes().size()));
        values.put("arena", match.arena().id());
        values.put("footer", footer);
        values.put("kills", Integer.toString(statistics.kills()));
        values.put("wins", Integer.toString(statistics.wins()));
        return values;
    }

    private static void addPlayingValues(Map<String, String> values, Match match, RacePlayer player,
                                         LuckyBlockTracker tracker, long currentTick) {
        Lane lane = match.arena().lanes().get(player.laneId());
        int totalSections = lane == null ? 0 : lane.sections().size();
        int currentSection = totalSections == 0 ? 0 : Math.min(player.currentSection(), totalSections);
        LuckyBlockTracker.SectionStatus progress = tracker.sectionStatus(
                match.id(), player.playerId(), player.laneId(), currentSection);
        boolean complete = player.completedSections().size() >= totalSections && totalSections > 0;
        values.put("section", Integer.toString(currentSection));
        values.put("total_sections", Integer.toString(totalSections));
        values.put("broken_lucky", Integer.toString(progress.broken()));
        values.put("total_lucky", Integer.toString(progress.total()));
        values.put("lucky_progress", complete ? "&aCOMPLETO" : "&f" + progress.broken() + "/" + progress.total());
        values.put("barrier", barrier(match, currentSection, currentTick));
    }

    private static List<Component> renderLines(List<String> templates, Map<String, String> values,
                                               Map<String, List<String>> expansions) {
        List<Component> lines = new ArrayList<>();
        for (String template : templates) {
            String expansionName = expansionName(template);
            if (expansionName != null && expansions.containsKey(expansionName)) {
                for (String expanded : expansions.get(expansionName)) {
                    if (lines.size() == ScoreboardView.MAX_LINES) return List.copyOf(lines);
                    lines.add(LegacyTextFormatter.parse(substitute(expanded, values)));
                }
            } else {
                if (lines.size() == ScoreboardView.MAX_LINES) break;
                lines.add(LegacyTextFormatter.parse(substitute(template, values)));
            }
        }
        return List.copyOf(lines);
    }

    private static String expansionName(String template) {
        String trimmed = template.trim();
        if (trimmed.length() > 2 && trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return null;
    }

    private static String substitute(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    private static List<String> placements(Match match, Function<UUID, String> names, boolean placeholders,
                                           String format) {
        List<String> result = new ArrayList<>();
        int limit = Math.min(3, match.requiredFinishers());
        for (int position = 1; position <= limit; position++) {
            final int expected = position;
            var placement = match.placements().stream().filter(value -> value.position() == expected).findFirst().orElse(null);
            if (placement == null && !placeholders) continue;
            String color = position == 1 ? "&6" : position == 2 ? "&f" : "&#CD7F32";
            String line = format
                    .replace("{rank_color}", color)
                    .replace("{position}", Integer.toString(position))
                    .replace("{player}", placement == null ? "---" : names.apply(placement.playerId()))
                    .replace("{time}", placement == null ? "" : formatDuration(placement.finishTime()));
            result.add(line);
        }
        return List.copyOf(result);
    }

    private static String barrier(Match match, int currentSection, long tick) {
        var barriers = match.arena().barriers().stream()
                .sorted(Comparator.comparingInt(value -> value.afterSection())).toList();
        for (var barrier : barriers) {
            BarrierState state = match.barriers().state(barrier.afterSection());
            if (state == BarrierState.OPEN) continue;
            BarrierCountdown countdown = match.barriers().countdowns().get(barrier.afterSection());
            if (state == BarrierState.COUNTDOWN && countdown != null) {
                return (long) Math.ceil(Math.max(0, countdown.opensAtTick() - tick) / 20.0D) + "s";
            }
            return "Aguardando";
        }
        int lastSection = match.arena().lanes().values().stream().mapToInt(lane -> lane.sections().size()).max().orElse(0);
        return currentSection >= lastSection ? "FINAL" : "Aguardando";
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainder)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    private static String ordinal(int position) { return position + "º"; }
}
