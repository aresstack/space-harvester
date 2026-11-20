package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der Ausgabeordner als Feld mit „Durchsuchen …“ im Feld (Angelos Vorgabe: Knöpfe zu einem Feld sitzen im Feld). */
class FolderFieldTest {

    @Test
    void theBrowseButtonSitsInsideTheFieldAndReportsTheChosenFolder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FolderField field = new FolderField(new JTextField(24));
            Path before = Path.of("alt").toAbsolutePath();
            Path chosen = Path.of("neu").toAbsolutePath();
            List<Path> changes = new ArrayList<>();
            List<Path> starts = new ArrayList<>();
            field.path(before);
            field.onChange(changes::add);
            field.chooser(start -> {
                starts.add(start);
                return Optional.of(chosen);
            });

            assertThat(inside(field, field.browseButton())).isTrue();
            assertThat(field.browseButton().getText()).isEqualTo(FolderField.BROWSE_LABEL);
            assertThat(field.textField().getText()).isEqualTo(before.toString());

            field.browseButton().doClick();

            assertThat(starts).containsExactly(before);
            assertThat(changes).containsExactly(chosen);
            assertThat(field.textField().getText()).isEqualTo(chosen.toString());
            assertThat(field.path()).isEqualTo(chosen);
        });
    }

    @Test
    void enterTakesATypedFolderOnceAndACancelledDialogChangesNothing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FolderField field = new FolderField(new JTextField(24));
            Path before = Path.of("alt").toAbsolutePath();
            List<Path> changes = new ArrayList<>();
            field.path(before);
            field.onChange(changes::add);
            field.chooser(start -> Optional.empty());

            field.browseButton().doClick();
            field.textField().postActionEvent();
            assertThat(changes).isEmpty();

            field.textField().setText("  " + Path.of("getippt").toAbsolutePath() + " ");
            field.textField().postActionEvent();
            field.textField().postActionEvent();

            assertThat(changes).containsExactly(Path.of("getippt").toAbsolutePath());
        });
    }

    @Test
    void anEmptyOrInvalidInputShowsTheCurrentFolderAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FolderField field = new FolderField(new JTextField(24));
            Path before = Path.of("alt").toAbsolutePath();
            List<Path> changes = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            field.path(before);
            field.onChange(changes::add);
            field.onInvalid(rejected::add);

            field.textField().setText("   ");
            field.textField().postActionEvent();
            assertThat(field.textField().getText()).isEqualTo(before.toString());

            field.textField().setText("ordner\u0000name");
            field.textField().postActionEvent();

            assertThat(rejected).containsExactly("ordner\u0000name");
            assertThat(changes).isEmpty();
            assertThat(field.textField().getText()).isEqualTo(before.toString());
        });
    }

    @Test
    void anExistingFileIsNotAFolderButAMissingFolderIsFine(@org.junit.jupiter.api.io.TempDir Path temp)
            throws Exception {
        Path file = java.nio.file.Files.writeString(temp.resolve("datei.txt"), "x");
        SwingUtilities.invokeAndWait(() -> {
            FolderField field = new FolderField(new JTextField(24));
            List<Path> changes = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            field.path(temp);
            field.onChange(changes::add);
            field.onInvalid(rejected::add);

            field.textField().setText(file.toString());
            field.textField().postActionEvent();

            assertThat(rejected).containsExactly(file.toString());
            assertThat(changes).isEmpty();
            assertThat(field.textField().getText()).isEqualTo(temp.toString());

            field.textField().setText(temp.resolve("neu").toString());
            field.textField().postActionEvent();

            assertThat(changes).containsExactly(temp.resolve("neu"));
        });
    }

    private static boolean inside(Container parent, Component wanted) {
        for (Component child : parent.getComponents()) {
            if (child == wanted || child instanceof Container container && inside(container, wanted)) {
                return true;
            }
        }
        return false;
    }
}
