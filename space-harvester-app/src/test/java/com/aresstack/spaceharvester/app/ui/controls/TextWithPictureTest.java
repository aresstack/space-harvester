package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextWithPictureTest {

    private static final String LONG = String.join(" ", java.util.Collections.nCopies(80,
            "Das Archiv bekommt ein neues Format."));

    private static TextWithPicture laidOut(TextWithPicture body, int width) {
        body.setSize(width, 10);
        body.setSize(width, body.getPreferredSize().height);
        body.doLayout();
        return body;
    }

    private static String shown(TextWithPicture body) {
        return String.join(" ", body.texts().stream().map(WrappedText::getText).toList());
    }

    @Test
    void withoutAPictureItIsAPlainWrappingParagraph() {
        TextWithPicture body = laidOut(new TextWithPicture(LONG, Color.BLACK, 13.5f), 900);

        assertThat(body.hasPicture()).isFalse();
        assertThat(body.texts()).singleElement().satisfies(text -> {
            assertThat(text.getText()).isEqualTo(LONG);
            assertThat(text.getWidth()).isEqualTo(900);
        });
        assertThat(body.pictureComponent().isVisible()).isFalse();
    }

    @Test
    void thePictureStandsTopRightAtAThirdOfTheColumnAndTheTextContinuesBelowAtFullWidth() {
        TextWithPicture body = new TextWithPicture(LONG, Color.BLACK, 13.5f);
        body.picture(new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB), "Altes und neues Format");
        laidOut(body, 900);

        Rectangle picture = body.pictureBounds();
        assertThat(picture.width).as("ein Drittel, höchstens 280").isEqualTo(TextWithPicture.MAX_PICTURE_WIDTH);
        assertThat(picture.x + picture.width).isEqualTo(900);
        assertThat(picture.y).isZero();
        assertThat(picture.height).isEqualTo(Math.round(90f * 280 / 160));
        assertThat(body.texts()).hasSize(2);
        WrappedText beside = body.texts().get(0);
        WrappedText below = body.texts().get(1);
        assertThat(beside.getWidth()).isEqualTo(900 - 280 - TextWithPicture.GAP);
        assertThat(below.getWidth()).isEqualTo(900);
        assertThat(below.getY()).isGreaterThanOrEqualTo(picture.y + picture.height);
        assertThat(shown(body)).as("kein Wort geht verloren").isEqualTo(LONG);
        assertThat(body.pictureComponent().getToolTipText()).isEqualTo("Vergrößern");
        assertThat(body.caption()).isEqualTo("Altes und neues Format");

        laidOut(body, 960);
        assertThat(body.pictureBounds().width).isEqualTo(280);
        laidOut(body, 690);
        assertThat(body.pictureBounds().width).as("ein Drittel der Spalte").isEqualTo(230);
    }

    @Test
    void inANarrowColumnThePictureStandsFullWidthAboveTheText() {
        TextWithPicture body = new TextWithPicture(LONG, Color.BLACK, 13.5f);
        body.picture(new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB), "Bild");
        laidOut(body, TextWithPicture.NARROW - 1);

        assertThat(body.pictureBounds()).isEqualTo(new Rectangle(0, 0, TextWithPicture.NARROW - 1,
                Math.round(90f * (TextWithPicture.NARROW - 1) / 160)));
        assertThat(body.texts()).singleElement().satisfies(text -> {
            assertThat(text.getText()).isEqualTo(LONG);
            assertThat(text.getY()).isGreaterThan(body.pictureBounds().height);
        });
    }

    @Test
    void aShortTextStaysBesideThePictureAndALongCaptionTakesAtMostTwoLines() {
        TextWithPicture body = new TextWithPicture("Kurz.", Color.BLACK, 13.5f);
        body.picture(new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB), LONG);
        laidOut(body, 900);

        assertThat(body.texts()).singleElement().satisfies(text -> assertThat(text.getText()).isEqualTo("Kurz."));
        Rectangle caption = findCaption(body).getBounds();
        int line = findCaption(body).getFontMetrics(findCaption(body).getFont()).getHeight();
        assertThat(caption.height).isLessThanOrEqualTo(2 * line + 4);
        assertThat(body.getPreferredSize().height).isGreaterThanOrEqualTo(caption.y + caption.height);
    }

    @Test
    void theHighlightCoversTheWholeTextAcrossEveryNewWrap() {
        TextWithPicture body = new TextWithPicture(LONG, Color.BLACK, 13.5f);
        body.picture(new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB), "Bild");
        laidOut(body, 900);
        body.highlight(Color.YELLOW);

        laidOut(body, 700);

        assertThat(body.highlighted()).isTrue();
        assertThat(body.texts()).allSatisfy(text -> assertThat(text.getHighlighter().getHighlights()).hasSize(1));
        body.highlight(null);
        assertThat(body.texts()).allSatisfy(text -> assertThat(text.getHighlighter().getHighlights()).isEmpty());
    }

    @Test
    void wordsEndWhereTheWhitespaceBegins() {
        assertThat(TextWithPicture.wordEnds("Ein  neues Format")).containsExactly(3, 10, 17);
        assertThat(TextWithPicture.wordEnds("")).isEmpty();
    }

    private static WrappedText findCaption(TextWithPicture body) {
        for (java.awt.Component child : body.getComponents()) {
            if ("text-with-picture.caption".equals(child.getName())) {
                return (WrappedText) child;
            }
        }
        throw new AssertionError("keine Bildunterschrift");
    }

    @Test
    void aClickOnThePictureAsksToOpenIt() {
        TextWithPicture body = new TextWithPicture("Text", Color.BLACK, 13.5f);
        List<String> opened = new java.util.ArrayList<>();
        body.onOpen(() -> opened.add("auf"));
        body.picture(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), null);
        for (java.awt.event.MouseListener listener : body.pictureComponent().getMouseListeners()) {
            listener.mouseClicked(new java.awt.event.MouseEvent(body.pictureComponent(),
                    java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
        }
        assertThat(opened).containsExactly("auf");
    }
}
