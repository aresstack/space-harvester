package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** „In PPTX umwandeln“ steht wie „Herunterladen“ in der Leiste, direkt daneben (Angelo 20.11.2026). */
final class VideoConvertButtonTest {

    @Test
    void theConvertButtonSitsRightNextToDownloadAndIsALikeCopy() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(position -> { });
            JButton download = bar.download();
            JButton convert = bar.convert();
            assertThat(convert.getText()).isEqualTo("In PPTX umwandeln");
            assertThat(convert.getToolTipText()).isEqualTo("In PPTX umwandeln");
            assertThat(convert.getHorizontalTextPosition()).isEqualTo(download.getHorizontalTextPosition());
            assertThat(convert.getIconTextGap()).isEqualTo(download.getIconTextGap());
            assertThat(convert.getBackground()).isEqualTo(download.getBackground());
            Container row = download.getParent();
            assertThat(convert.getParent()).isSameAs(row);
            int at = Arrays.asList(row.getComponents()).indexOf(download);
            assertThat(row.getComponent(at + 1)).isSameAs(convert);
            assertThat(convert.isVisible()).isFalse();
        });
    }
}
