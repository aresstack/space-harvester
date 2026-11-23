package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Textstellen der Lupe werden zu Pufferbereichen der Terminalanzeige. */
class TerminalScreenFindTest {

    @Test
    void textPositionsBecomeBufferPositionsWithoutTheLineBreaks() {
        // Drei Zeilen zu je vier Spalten.
        String text = "ABCD\nEFGH\nIJKL";
        List<TerminalView.Span> spans = TerminalWorkspace.ScreenFind.spans(text,
                List.of(new int[] {1, 3}, new int[] {5, 7}, new int[] {12, 14}));
        assertThat(spans).containsExactly(new TerminalView.Span(1, 2), new TerminalView.Span(4, 2),
                new TerminalView.Span(10, 2));
    }
}
