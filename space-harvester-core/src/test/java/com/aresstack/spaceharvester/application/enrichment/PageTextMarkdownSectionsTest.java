package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Überschriften des leichten Markdowns als Abschnitte, ohne den Stand bestehender Ergebnisse zu ändern. */
class PageTextMarkdownSectionsTest {

    @Test
    void markdownHeadingsBecomeSectionsUntilTheNextHeadingOfTheSameOrHigherLevel() {
        String text = "Vorwort\n# Betrieb\nText\n## Notfallplan ##\nErst melden\n# Anhang\nEnde";

        List<PageText.Section> sections = PageText.markdownSections(text);

        assertThat(sections).extracting(PageText.Section::heading, PageText.Section::level)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Betrieb", 1),
                        org.assertj.core.groups.Tuple.tuple("Notfallplan", 2),
                        org.assertj.core.groups.Tuple.tuple("Anhang", 1));
        assertThat(text.substring(sections.get(0).start(), sections.get(0).end()))
                .isEqualTo("# Betrieb\nText\n## Notfallplan ##\nErst melden\n");
        assertThat(text.substring(sections.get(1).start())).startsWith("## Notfallplan");
        assertThat(PageText.markdownSections("kein # Titel\n#auch nicht")).isEmpty();
    }

    @Test
    void sectionsThatFollowFromTheTextDoNotChangeTheInputStamp() {
        String text = "# Betrieb\nText";

        PageText before = new PageText("knowledge-document-text/v1", text, List.of());
        PageText derived = new PageText("knowledge-document-text/v1", text, PageText.markdownSections(text), true);
        PageText stated = new PageText("knowledge-document-text/v1", text, PageText.markdownSections(text));

        assertThat(derived.sections()).hasSize(1);
        assertThat(derived.inputSha256()).isEqualTo(before.inputSha256());
        assertThat(stated.inputSha256()).isNotEqualTo(before.inputSha256());
    }
}
