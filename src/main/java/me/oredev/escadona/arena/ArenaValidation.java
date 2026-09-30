package me.oredev.escadona.arena;

import java.util.List;

public record ArenaValidation(List<String> errors, List<String> warnings) {
    public ArenaValidation {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
