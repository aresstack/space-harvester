package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Einstellungen › Bibliotheken: liest den Lizenzbericht des Builds; Lizenz links in fester Spalte, Bibliothek rechts. */
class LibrariesViewTest {

    @Test
    void theReportIsParsedWithTheModule() throws IOException {
        List<OssLibrary> libraries = OssLibraries.parse("""
                [
                  {"group":"org.example","artifact":"lib","version":"1.2.3","license":"Apache-2.0","module":"space-harvester-core"},
                  {"group":"org.example","artifact":"with-\\"quote\\"","version":"2.0","license":"MIT"}
                ]
                """);
        assertThat(libraries).hasSize(2);
        assertThat(libraries.get(0).name()).isEqualTo("org.example:lib");
        assertThat(libraries.get(0).module()).isEqualTo("space-harvester-core");
        assertThat(libraries.get(1).artifact()).isEqualTo("with-\"quote\"");
        assertThat(libraries.get(1).module()).isEmpty();
        assertThatThrownBy(() -> OssLibraries.parse("[{\"group\":\"g\"}]")).isInstanceOf(IOException.class)
                .hasMessageContaining("artifact");
        assertThatThrownBy(() -> OssLibraries.parse("{}")).isInstanceOf(IOException.class);
    }

    @Test
    void theBuildShipsTheReport() throws IOException {
        List<OssLibrary> all = OssLibraries.read();
        assertThat(all).as("generateOssLicenseReport läuft vor processResources").isNotEmpty();
        assertThat(all).extracting(OssLibrary::license).doesNotContain("Unknown", "");
        assertThat(all).filteredOn(library -> library.artifact().equals("jackson-databind"))
                .extracting(OssLibrary::license).containsOnly("Apache-2.0");
        assertThat(all).filteredOn(library -> library.artifact().equals("maltparser-de-gsd"))
                .as("das Parsermodell, das die App erst auf Klick lädt").extracting(OssLibrary::license)
                .containsExactly("CC BY-SA 4.0");
        assertThat(all).extracting(OssLibrary::group).doesNotContain("com.aresstack.spaceharvester");
        // Nur die ausgelieferte Fassung je Bibliothek: was ein Modul für sich allein wählte, zählt nicht.
        assertThat(all).extracting(OssLibrary::name).doesNotHaveDuplicates();
        assertThat(all).filteredOn(library -> library.artifact().equals("logback-classic"))
                .as("Lizenz aus dem POM, nicht aus einer veralteten Tabelle").extracting(OssLibrary::license)
                .containsExactly("EPL-2.0 / LGPL-2.1-only");
    }

    @Test
    void licensesSitInAFixedColumnLibrariesInTheRestAndTheFilterNarrows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LibrariesView view = new LibrariesView();
            // So hoch wie die Liste, wie in der scrollenden Spalte der Einstellungen (zu klein legt GridBag nichts aus).
            view.component().setSize(900, Math.max(600, view.component().getPreferredSize().height));
            layoutAll(view.component());
            List<JLabel> labels = new ArrayList<>();
            collect(view.component(), labels);
            List<WrappedText> texts = new ArrayList<>();
            collect(view.component(), texts, WrappedText.class);
            java.util.Set<String> names = view.libraries().stream().map(OssLibrary::license)
                    .collect(java.util.stream.Collectors.toSet());
            List<WrappedText> licenses = texts.stream().filter(text -> names.contains(text.getText())).toList();
            WrappedText license = licenses.stream().filter(text -> text.getText().equals("Apache-2.0")).findFirst()
                    .orElseThrow();
            WrappedText longest = licenses.stream().max(java.util.Comparator.comparingInt(t -> t.getText().length()))
                    .orElseThrow();
            JLabel library = find(labels, "<b>jackson-databind</b>");
            assertThat(license.getWidth()).isEqualTo(LibrariesView.LICENSE_WIDTH);
            // Lange Lizenznamen brechen in der Spalte um, statt rechts abgeschnitten zu werden.
            assertThat(longest.getWidth()).isEqualTo(LibrariesView.LICENSE_WIDTH);
            assertThat(longest.getHeight()).as("mehrzeilig: " + longest.getText())
                    .isGreaterThanOrEqualTo(2 * license.getHeight());
            assertThat(longest.getParent().getHeight()).isEqualTo(longest.getHeight());
            int libraryX = SwingUtilities.convertPoint(library.getParent(), library.getLocation(), view.component()).x;
            int licenseX = SwingUtilities.convertPoint(license.getParent(), license.getLocation(), view.component()).x;
            assertThat(libraryX).as("Bibliothek rechts neben der Lizenzspalte").isGreaterThan(licenseX
                    + LibrariesView.LICENSE_WIDTH);

            int all = view.libraries().size();
            view.filter().setText("maltparser");
            assertThat(view.shown()).isNotEmpty().hasSizeLessThan(all)
                    .allMatch(entry -> entry.matches("maltparser"));
            view.filter().setText("gibt-es-nicht-xyz");
            assertThat(view.shown()).isEmpty();
            view.filter().setText("");
            assertThat(view.shown()).hasSize(all);
        });
    }

    private static JLabel find(List<JLabel> labels, String text) {
        return labels.stream().filter(label -> label.getText() != null && label.getText().contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("kein Label mit " + text));
    }

    private static void collect(Container container, List<JLabel> labels) {
        collect(container, labels, JLabel.class);
    }

    private static <T> void collect(Container container, List<T> found, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container inner) {
                collect(inner, found, type);
            }
        }
    }

    private static void layoutAll(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container inner) {
                layoutAll(inner);
            }
        }
    }
}
