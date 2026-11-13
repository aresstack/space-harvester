package com.aresstack.spaceharvester.application.assets;

import java.util.List;
import java.util.Objects;

/**
 * Ein Abschnitt einer abgelegten Extraktion (ADR 0026, Punkt 8): aufeinanderfolgende ganze Blöcke, geschnitten an
 * Blockgrenzen. Volltextindex, Export und Analysen lesen abschnittsweise, nie den ganzen Text auf einmal.
 *
 * @param index      Nummer des Abschnitts ab 0
 * @param firstBlock Position des ersten Blocks
 * @param blocks     die Blöcke in Lesereihenfolge, nie leer
 * @param chars      Zeichen in den Blöcken
 */
public record TextSection(int index, long firstBlock, List<Block> blocks, long chars) {

    public TextSection {
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (index < 0 || firstBlock < 0 || chars < 0 || blocks.isEmpty()) {
            throw new IllegalArgumentException("Abschnitt ungültig");
        }
        if (blocks.getFirst().index() != firstBlock) {
            throw new IllegalArgumentException("Erster Block passt nicht");
        }
    }
}
