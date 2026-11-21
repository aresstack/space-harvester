package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PodcastPicturesTest {

    static final String KEY = "0123456789abcdef";

    @TempDir
    Path folder;

    static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    static StepModel answering(String answer, AtomicInteger calls) {
        return new StepModel() {
            @Override
            public String version() {
                return "t";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                calls.incrementAndGet();
                return answer;
            }
        };
    }

    static PodcastPictures.PageImageSource source(byte[] bytes) {
        return new PodcastPictures.PageImageSource() {
            @Override
            public List<Candidate> candidates(String sourceId, List<String> pageIds) {
                return List.of(new Candidate("p1", "aa", "Logo", "Ein kleines Logo", "image/png"),
                        new Candidate("p2", "bb", "Ablauf.png", "Ein Ablaufdiagramm des Nachtlaufs", "image/png"));
            }

            @Override
            public Optional<byte[]> content(String sourceId, Candidate candidate) {
                return Optional.of(bytes);
            }
        };
    }

    PodcastPictures pictures(StepModel chooser, StepModel drawer, PodcastPictures.PageImageSource images) {
        return new PodcastPictures(new PodcastStore(folder), chooser, drawer, images, Runnable::run, a -> { });
    }

    @Test
    void aFittingPageImageComesFirstAndIsUsedOnlyOnce() throws Exception {
        AtomicInteger drawn = new AtomicInteger();
        PodcastPictures pictures = pictures(answering("{\"choice\":2}", new AtomicInteger()),
                answering(DiagramComposerTest.PLAN, drawn), source(png(640, 360)));
        Set<String> used = new HashSet<>();
        List<String> repairs = new ArrayList<>();
        PodcastPictures.Picture picture = pictures.make(KEY, "s", new PodcastPictures.Brief(1, "Nordlicht", "m",
                List.of("p1", "p2")), used, repairs);

        assertThat(picture.kind()).isEqualTo(PodcastPictures.Kind.PAGE);
        assertThat(picture.pageId()).isEqualTo("p2");
        assertThat(picture.caption()).isEqualTo("Ablauf.png");
        assertThat(picture.file()).exists().hasFileName("pic-01.png");
        assertThat(drawn).hasValue(0);
        assertThat(used).containsExactly("bb");
        assertThat(pictures.picture(KEY, 1)).contains(picture);
    }

    @Test
    void withoutAFittingImageADiagramIsDrawn() throws Exception {
        PodcastPictures pictures = pictures(answering("{\"choice\":0}", new AtomicInteger()),
                answering(DiagramComposerTest.PLAN, new AtomicInteger()), source(png(640, 360)));
        PodcastPictures.Picture picture = pictures.make(KEY, "s", new PodcastPictures.Brief(2, "Nordlicht", "m",
                List.of("p1")), new HashSet<>(), new ArrayList<>());
        assertThat(picture.kind()).isEqualTo(PodcastPictures.Kind.DIAGRAM);
        assertThat(picture.caption()).isEqualTo("Nachtlauf Nordlicht");
        assertThat(Files.readString(picture.file())).startsWith("<svg");
    }

    @Test
    void aTooSmallImageFallsBackToADiagram() throws Exception {
        PodcastPictures pictures = pictures(answering("{\"choice\":2}", new AtomicInteger()),
                answering(DiagramComposerTest.PLAN, new AtomicInteger()), source(png(64, 64)));
        List<String> repairs = new ArrayList<>();
        assertThat(pictures.make(KEY, "s", new PodcastPictures.Brief(0, "t", "m", List.of("p2")), new HashSet<>(),
                repairs).kind()).isEqualTo(PodcastPictures.Kind.DIAGRAM);
        assertThat(repairs).anyMatch(repair -> repair.contains("zu klein"));
    }

    @Test
    void failingModelsMeanNoPictureAndTheDecisionIsKept() {
        StepModel broken = new StepModel() {
            @Override
            public String version() {
                return "x";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                throw new IllegalStateException("weg");
            }
        };
        PodcastPictures pictures = pictures(broken, broken, PodcastPictures.PageImageSource.NONE);
        PodcastPictures.Picture picture = pictures.make(KEY, "s", new PodcastPictures.Brief(3, "t", "m", List.of()),
                new HashSet<>(), new ArrayList<>());
        assertThat(picture.kind()).isEqualTo(PodcastPictures.Kind.NONE);
        assertThat(pictures.picture(KEY, 3)).map(PodcastPictures.Picture::kind).contains(PodcastPictures.Kind.NONE);
        assertThat(pictures.picture(KEY, 4)).isEmpty();
    }
}
