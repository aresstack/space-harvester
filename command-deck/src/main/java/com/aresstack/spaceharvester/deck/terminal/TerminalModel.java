package com.aresstack.spaceharvester.deck.terminal;

import java.util.Arrays;
import java.util.Optional;

/** Die 3270-Bildschirmmodelle mit ihrer Zeilen- und Spaltenzahl; Modell 2 (24 × 80) ist der Normalfall. */
public enum TerminalModel {
    MODEL_2("IBM-3278-2", 24, 80),
    MODEL_3("IBM-3278-3", 32, 80),
    MODEL_4("IBM-3278-4", 43, 80),
    MODEL_5("IBM-3278-5", 27, 132);

    private final String type;
    private final int rows;
    private final int columns;

    TerminalModel(String type, int rows, int columns) {
        this.type = type;
        this.rows = rows;
        this.columns = columns;
    }

    /** Der Terminaltyp, wie er beim Verbindungsaufbau gemeldet wird (etwa {@code IBM-3278-2}). */
    public String type() {
        return type;
    }

    public int rows() {
        return rows;
    }

    public int columns() {
        return columns;
    }

    /** Auch die Farbvarianten 3279 zählen zum gleichen Modell. */
    public static Optional<TerminalModel> ofType(String type) {
        if (type == null) {
            return Optional.empty();
        }
        String normalized = type.trim().toUpperCase(java.util.Locale.ROOT).replace("IBM-3279-", "IBM-3278-");
        return Arrays.stream(values()).filter(model -> model.type.equals(normalized)).findFirst();
    }

    @Override
    public String toString() {
        return type + " (" + rows + " × " + columns + ")";
    }
}
