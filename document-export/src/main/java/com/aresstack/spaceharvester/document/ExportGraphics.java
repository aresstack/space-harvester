package com.aresstack.spaceharvester.document;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The figures of one export (#132), shared by the PDF, Word and Markdown writers of pages and chats: turns a Markdown
 * image address or a Mermaid fence into a picture the writers embed. Only three kinds of address resolve: figures the
 * export tree already carries (loaded by the source through its own sign-in), {@code data:} images inside the content,
 * and nothing else; any other address (an arbitrary web image) is never fetched. PNG, JPEG and GIF (first frame) are
 * decoded, SVG is rasterized offline like in the reader, Mermaid is drawn by the same renderer as in the chat.
 *
 * <p>Whatever does not resolve is collected in {@link #problems()}, so the export can say which figures are missing;
 * the writers put a visible notice where the figure would be. One instance per export; not thread-safe.</p>
 *
 * <p>Space Harvester: Mermaid and SVG are drawn only by renderers handed in (the Enterprise AI Client uses mermaid-java
 * and Batik from its Swing reader); without them a Mermaid fence stays code and an SVG figure is reported as not
 * displayable. {@link #image(String, String)} remembers decoded pictures only until {@link #forget()}, so a long export
 * keeps no more than one chapter of pictures in memory.</p>
 */
public final class ExportGraphics {

    /** Pictures wider than this are scaled down before they are embedded. */
    static final int MAX_PIXELS = 2000;
    static final int MERMAID_WIDTH = 1200;
    static final int SVG_WIDTH = 1200;

    /** A picture ready to embed: decoded once, with its PNG bytes. */
    public static final class Graphic {
        private final BufferedImage image;
        private final byte[] png;

        Graphic(BufferedImage image, byte[] png) {
            this.image = image;
            this.png = png;
        }

        public BufferedImage image() {
            return image;
        }

        public byte[] png() {
            return png.clone();
        }

        public int width() {
            return image.getWidth();
        }

        public int height() {
            return image.getHeight();
        }
    }

    private final MermaidImageRenderer mermaid;
    private final MermaidImageRenderer svg;
    private final Map<String, ExportImage> known = new HashMap<String, ExportImage>();
    private final Map<String, Graphic> decoded = new HashMap<String, Graphic>();
    private final Set<String> problems = new LinkedHashSet<String>();
    private final List<String> failedMermaid = new ArrayList<String>();

    /** @param mermaid draws Mermaid fences; {@code null}: fences stay code and are reported */
    public ExportGraphics(MermaidImageRenderer mermaid) {
        this(mermaid, null);
    }

    /** @param svg rasterizes SVG figures offline; {@code null}: SVG figures are reported as not displayable */
    public ExportGraphics(MermaidImageRenderer mermaid, MermaidImageRenderer svg) {
        this.mermaid = mermaid;
        this.svg = svg;
    }

    /** The graphics without Mermaid and SVG renderers: PNG, JPEG and GIF only. */
    public static ExportGraphics standard() {
        return new ExportGraphics(null, null);
    }

    /**
     * The graphics with the offline renderers of the Enterprise AI Client: Mermaid through mermaid-java and SVG
     * through Batik, both without browser or network.
     */
    public static ExportGraphics rendering() {
        return new ExportGraphics(new AresStackMermaidImageRenderer(), new SvgImageRenderer());
    }

    /** Know {@code images} (the figures of one chapter) as well. */
    public ExportGraphics with(List<ExportImage> images) {
        if (images != null) {
            for (ExportImage image : images) {
                known.put(image.src(), image);
            }
        }
        return this;
    }

    /** Drop the known figures and decoded pictures; the problems stay. */
    public void forget() {
        known.clear();
        decoded.clear();
    }

    /** Know the figures of every chapter of {@code root}. */
    public ExportGraphics with(ExportDocumentNode root) {
        if (root != null) {
            for (ExportImage image : root.images()) {
                known.put(image.src(), image);
            }
            for (ExportDocumentNode child : root.children()) {
                with(child);
            }
        }
        return this;
    }

    /** The picture behind a Markdown image address, or {@code null} (then reported in {@link #problems()}). */
    public Graphic image(String src, String caption) {
        String address = src == null ? "" : src.trim();
        String name = name(caption, address);
        Graphic cached = decoded.get(address);
        if (cached != null) {
            return cached;
        }
        byte[] bytes;
        if (address.regionMatches(true, 0, "data:", 0, 5)) {
            bytes = dataBytes(address);
            if (bytes == null) {
                problems.add(name + ": eingebettete Bilddaten nicht lesbar");
                return null;
            }
        } else {
            ExportImage image = known.get(address);
            if (image == null) {
                problems.add(name + ": nicht abgerufen (Adresse außerhalb der Quelle)");
                return null;
            }
            if (!image.ok()) {
                problems.add(name + ": " + image.failure());
                return null;
            }
            bytes = image.bytes();
        }
        Graphic graphic = decode(bytes, svg);
        if (graphic == null) {
            problems.add(name + ": Bildformat nicht darstellbar");
            return null;
        }
        decoded.put(address, graphic);
        return graphic;
    }

    /** The drawn Mermaid diagram, or {@code null} (then reported). */
    public Graphic mermaid(String code) {
        String key = "mermaid:" + code;
        Graphic cached = decoded.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage image = null;
        if (mermaid != null) {
            try {
                image = mermaid.render(code, MERMAID_WIDTH);
            } catch (RuntimeException | LinkageError e) {
                image = null;
            }
        }
        if (image == null) {
            problems.add("Mermaid-Diagramm: nicht darstellbar, als Quelltext übernommen");
            failedMermaid.add(code);
            return null;
        }
        Graphic graphic = graphic(image);
        if (graphic != null) {
            decoded.put(key, graphic);
        }
        return graphic;
    }

    /** Whether {@code src} is an address this export resolves (so the writers try it instead of keeping it as link). */
    public boolean knows(String src) {
        String address = src == null ? "" : src.trim();
        return address.regionMatches(true, 0, "data:", 0, 5) || known.containsKey(address);
    }

    /**
     * The sources of the Mermaid diagrams that could not be drawn since the last call, in the order they were met; the
     * caller reports them per chapter.
     */
    public List<String> takeMermaidFailures() {
        List<String> taken = new ArrayList<String>(failedMermaid);
        failedMermaid.clear();
        return taken;
    }

    /** The figures that could not be embedded, one line each, in the order they were met. */
    public List<String> problems() {
        return Collections.unmodifiableList(new ArrayList<String>(problems));
    }

    /** {@code data:} URI of the PNG, for single-file Markdown. */
    public static String dataUri(Graphic graphic) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(graphic.png);
    }

    // ------------------------------------------------------------------ decoding

    static Graphic decode(byte[] bytes, MermaidImageRenderer svg) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BufferedImage image = null;
        if (isSvg(bytes)) {
            if (svg == null) {
                return null;
            }
            try {
                image = svg.render(new String(bytes, StandardCharsets.UTF_8), SVG_WIDTH);
            } catch (RuntimeException | LinkageError e) {
                image = null;
            }
        } else {
            try {
                image = ImageIO.read(new ByteArrayInputStream(bytes)); // GIF: the first frame
            } catch (IOException | RuntimeException e) {
                image = null;
            }
        }
        return image == null ? null : graphic(image);
    }

    private static Graphic graphic(BufferedImage source) {
        BufferedImage image = opaque(scaled(source));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                return null;
            }
            return new Graphic(image, out.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage scaled(BufferedImage image) {
        if (image.getWidth() <= MAX_PIXELS) {
            return image;
        }
        int height = Math.max(1, Math.round(image.getHeight() * (MAX_PIXELS / (float) image.getWidth())));
        BufferedImage smaller = new BufferedImage(MAX_PIXELS, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smaller.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, MAX_PIXELS, height, null);
        } finally {
            g.dispose();
        }
        return smaller;
    }

    /** On white: transparent diagrams stay readable on paper and in every viewer. */
    private static BufferedImage opaque(BufferedImage image) {
        BufferedImage white = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = white.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return white;
    }

    private static boolean isSvg(byte[] bytes) {
        int length = Math.min(bytes.length, 512);
        String head = new String(bytes, 0, length, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("<svg") || (head.trim().startsWith("<?xml") && head.contains("svg"));
    }

    private static byte[] dataBytes(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String header = uri.substring(5, comma).toLowerCase(Locale.ROOT);
        String payload = uri.substring(comma + 1);
        try {
            if (header.endsWith(";base64")) {
                return Base64.getMimeDecoder().decode(payload);
            }
            return java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    private static String name(String caption, String address) {
        String shown = caption == null ? "" : caption.trim();
        if (!shown.isEmpty()) {
            return "Abbildung „" + shown + "“";
        }
        if (address.regionMatches(true, 0, "data:", 0, 5) || address.isEmpty()) {
            return "Abbildung";
        }
        String path = address;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query); // no query strings (tokens) in messages
        }
        int slash = path.lastIndexOf('/');
        return "Abbildung „" + (slash >= 0 ? path.substring(slash + 1) : path) + "“";
    }
}
