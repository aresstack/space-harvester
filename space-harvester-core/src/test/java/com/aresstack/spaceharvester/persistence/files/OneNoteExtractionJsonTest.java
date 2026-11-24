package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.Location;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class OneNoteExtractionJsonTest {

    @Test
    void roundTripNotePageLocation() {
        Block original = new Block.Paragraph(0, Optional.of(Location.notePage(2)), List.of(), "Note");
        assertThat(ExtractionJson.block(ExtractionJson.block(original))).isEqualTo(original);
    }

    @Test
    void readExistingLocationsWithoutNotePage() {
        assertThat(read("{\"page\":2}").location()).contains(Location.page(2));
        assertThat(read("{\"slide\":3}").location()).contains(Location.slide(3));
        assertThat(read("{\"sheet\":\"Kosten\"}").location()).contains(Location.sheet("Kosten"));
    }

    @Test
    void retainTextWhenNotePageIndexIsMissingOrInvalid() {
        for (String location : List.of("{\"notePage\":{}}", "{\"notePage\":{\"index\":0}}",
                "{\"notePage\":{\"index\":-1}}", "{\"notePage\":{\"index\":\"2\"}}",
                "{\"notePage\":{\"index\":1.5}}", "{\"notePage\":{\"index\":2147483648}}")) {
            assertThat(read(location)).isEqualTo(new Block.Paragraph(0, Optional.empty(), List.of(), "Note"));
        }
        assertThat(read("{\"page\":2,\"notePage\":{}}").location()).contains(Location.page(2));
    }

    private static Block read(String location) {
        ObjectNode node = ExtractionJson.block(new Block.Paragraph(0, Optional.empty(), List.of(), "Note"));
        node.set("location", ExtractionJson.JSON.readTree(location));
        return ExtractionJson.block(node);
    }
}
