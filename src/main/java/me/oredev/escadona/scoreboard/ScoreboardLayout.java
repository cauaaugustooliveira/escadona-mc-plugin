package me.oredev.escadona.scoreboard;

import java.util.List;

/** User-editable text templates; domain values are supplied by ScoreboardRenderer. */
public record ScoreboardLayout(String title, String placementLine, String resultLine,
                               List<String> lobby, List<String> waiting, List<String> starting,
                               List<String> playing, List<String> finished, List<String> deadRespawning,
                               List<String> finishing) {
    public ScoreboardLayout {
        lobby = List.copyOf(lobby);
        waiting = List.copyOf(waiting);
        starting = List.copyOf(starting);
        playing = List.copyOf(playing);
        finished = List.copyOf(finished);
        deadRespawning = List.copyOf(deadRespawning);
        finishing = List.copyOf(finishing);
    }

    public static ScoreboardLayout defaults() {
        return new ScoreboardLayout(
                "&6&lESCADONA",
                "{rank_color}{position}º {player}",
                "{rank_color}{position}º {player} &8- &b{time}",
                List.of("&7Jogadores online: &f{online_players}", "", "&7Kills: &c{kills}",
                        "&7Vitórias: &6{wins}", "", "&8{footer}"),
                List.of("&7Jogadores: &f{players}/{max_players}", "&7Kills: &c{kills}",
                        "&7Vitórias: &6{wins}", "", "&eAguardando jogadores...", "", "&8{footer}"),
                List.of("&7Jogadores: &f{players}/{max_players}", "&7Iniciando em: &e{starting_seconds}s",
                        "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "", "&6Prepare-se!", "", "&8{footer}"),
                List.of("&7Tempo: &f{elapsed}", "&7Parte: &f{section}/{total_sections}",
                        "&7Lucky Blocks: {lucky_progress}", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}",
                        "", "&7Barreira: &e{barrier}", "",
                        "{placements}", "", "&8{footer}"),
                List.of("&7Posição: &a{position}", "&7Tempo: &f{finish_time}", "",
                        "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "{placements}", "", "&8{footer}"),
                List.of("&7Tempo: &f{elapsed}", "&7Respawn: &e{respawn_seconds}s", "",
                        "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "{placements}", "", "&8{footer}"),
                List.of("&6&lRESULTADO", "", "{results}", "", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}",
                        "&eReiniciando arena...", "", "&8{footer}"));
    }
}
