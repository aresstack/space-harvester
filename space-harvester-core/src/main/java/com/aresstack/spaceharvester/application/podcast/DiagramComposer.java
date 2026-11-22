package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.document.SvgDrawing;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Zeichnet ein schlichtes Schaubild zu einem Thema des Podcasts aus Einzelteilen, mit der Technik aus 3D4J
 * ({@code FlowerComposer}: Häuser und Blüten aus Teilen statt einer großen Zeichnung). Die Arbeit ist geteilt nach dem,
 * was ein Sprachmodell gut und schlecht kann:
 * <ol>
 *     <li>Ein Aufruf plant das Schaubild als JSON: eine Form ({@link Form}) und 2 bis {@link #MAX_ELEMENTS} Elemente mit
 *     kurzer Beschriftung und der Beschreibung eines einfachen Symbols. Werte außerhalb ihres Bereichs werden
 *     zurechtgerückt.</li>
 *     <li>Je Element zeichnet ein eigener Aufruf nur das Symbol, alle zugleich. Ein Symbol, das sich nicht zeichnen
 *     lässt, leer ist oder einen Hintergrund hat, wird mit dem Grund neu erbeten; gelingt es nicht, zeichnet das
 *     Programm einen schlichten Punkt.</li>
 *     <li>Alles Räumliche und jede Schrift setzt das Programm: Karten, Pfeile, Beschriftungen, Titel. Modelle setzen
 *     Text in SVG unzuverlässig; so bleibt jedes Schaubild lesbar.</li>
 * </ol>
 * Jede Stufe geht über {@link Memory}, damit ein neuer Lauf Plan und fertige Symbole wiederverwendet.
 */
public final class DiagramComposer {

    /** Breite und Höhe des Schaubilds (16:9 wie der Inhaltsbereich und der Videoexport). */
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    /** Höchstens so viele Elemente; mehr wird gekürzt. */
    public static final int MAX_ELEMENTS = 5;
    static final int ATTEMPTS = 3;
    /** So viel Material geht höchstens an die Planung. */
    static final int MAX_MATERIAL_CHARS = 4000;

    static final String OUTLINE = "#2B2D42";
    static final List<String> PALETTE = List.of("#2F6FDE", "#F2B233", "#3BB273", "#E4572E", "#7E57C2");
    private static final List<String> TINTS = List.of("#E8F0FD", "#FDF4DE", "#E5F5EC", "#FCE9E3", "#F0EAF8");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FORBIDDEN = Set.of("script", "foreignobject", "image", "iframe", "audio", "video",
            "animate", "animatetransform", "animatemotion", "set", "style", "a", "text", "tspan", "textpath",
            "filter", "pattern", "mask");

    static final String PLAN_SYSTEM = "Du entwirfst schlichte Schaubilder für einen Erklär-Podcast zusammen mit einem "
            + "Programm. Das Programm setzt Karten, Pfeile und Schrift; du lieferst nur den Plan. Nur Fakten aus dem "
            + "Material, keine Namen von Personen. Antworte nur mit gültigem JSON, ohne Markdown.";

    static final String ICON_SYSTEM = """
            You draw small icons for an explanatory diagram as SVG. A program places your icon on a white card.

            Rules:
            - Answer with one complete SVG document and nothing else.
            - Root element: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">.
            - The background stays transparent: no rectangle behind the subject.
            - Draw only the subject, centered, filling about 80 percent of the square.
            - Flat modern style: solid fills from this palette: %s, dark outlines \
            (stroke="%s", stroke-width 3, round joins), at most 12 shapes.
            - Use only basic shapes and paths. No text, no letters, no images, no filters, no gradients, \
            no scripts, no external references.""".formatted(String.join(", ", PALETTE), OUTLINE);

    /** Wie die Elemente angeordnet werden. */
    public enum Form {
        /** Ablauf: Schritte nacheinander, mit Pfeilen. */
        FLOW,
        /** Ein Mittelpunkt (erstes Element) mit Bezügen ringsum. */
        HUB,
        /** Dinge nebeneinander im Vergleich. */
        COMPARE,
        /** Schichten übereinander, das erste oben. */
        STACK;

        static Form parse(String value) {
            if (value == null) {
                return FLOW;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return FLOW;
            }
        }
    }

    /** Ein Element: Beschriftung (höchstens drei Wörter), Zusatz (kurz, darf fehlen) und das Symbol in Worten. */
    public record Element(String label, String note, String icon) {
        public Element {
            label = cut(label == null ? "" : label.strip(), 28);
            note = cut(note == null ? "" : note.strip(), 48);
            icon = icon == null || icon.isBlank() ? "a simple round badge" : cut(icon.strip(), 120);
        }
    }

    /** Der Plan eines Schaubilds. */
    public record Plan(String title, Form form, List<Element> elements) {
        public Plan {
            title = cut(title == null ? "" : title.strip(), 70);
            Objects.requireNonNull(form, "form");
            elements = List.copyOf(elements);
        }

        String toJson() {
            ObjectNode node = JSON.createObjectNode();
            node.put("title", title);
            node.put("form", form.name().toLowerCase(Locale.ROOT));
            ArrayNode list = node.putArray("elements");
            for (Element element : elements) {
                list.addObject().put("label", element.label()).put("note", element.note()).put("icon", element.icon());
            }
            return JSON.writeValueAsString(node);
        }
    }

    /** Ablage der Zwischenstufen (Plan, Symbole) eines Schaubilds; {@code null} = noch nicht da. */
    public interface Memory {
        String load(String name);

        void save(String name, String content);

        /** Merkt sich nichts. */
        Memory NONE = new Memory() {
            @Override
            public String load(String name) {
                return null;
            }

            @Override
            public void save(String name, String content) {
            }
        };
    }

    /** Das fertige Schaubild als SVG mit den Reparaturen, eine Zeile je Reparatur. */
    public record Diagram(String svg, Plan plan, List<String> repairs) {
        public Diagram {
            Objects.requireNonNull(svg, "svg");
            repairs = List.copyOf(repairs);
        }
    }

    /**
     * Die Modelle der beiden Stufen (Angelo 22.11.2026: getrennt einstellbar, weil Planen und Zeichnen sich
     * verschieden fein justieren): der Plan zerlegt das Schaubild, die Einzelteile zeichnen je ein Symbol.
     *
     * @param planner entwirft den Plan (Einstellung „Bildgenerierung – Plan“)
     * @param icons   zeichnet die Einzelteile (Einstellung „Bildgenerierung – Einzelteile“)
     */
    public record Models(StepModel planner, StepModel icons) {
        public Models {
            Objects.requireNonNull(planner, "planner");
            Objects.requireNonNull(icons, "icons");
        }

        /** Ein Modell für beide Stufen. */
        public static Models of(StepModel both) {
            return new Models(both, both);
        }

        /** Ohne Plan kein Schaubild; ein fehlendes Symbol ersetzt das Programm durch einen Kreis. */
        public boolean available() {
            return planner.available();
        }
    }

    private final StepModel planner;
    private final StepModel iconModel;
    private final Executor parts;
    private final PodcastWriter.Pause pause;

    /**
     * @param parts führt die Symbole aus; seine Größe begrenzt, wie viele Aufrufe zugleich zum Modell gehen
     */
    public DiagramComposer(StepModel model, Executor parts, PodcastWriter.Pause pause) {
        this(Models.of(model), parts, pause);
    }

    /**
     * @param models die Modelle für Plan und Einzelteile
     * @param parts  führt alle Aufrufe aus; seine Größe begrenzt, wie viele Aufrufe zugleich zum Modell gehen
     */
    public DiagramComposer(Models models, Executor parts, PodcastWriter.Pause pause) {
        this.planner = models.planner();
        this.iconModel = models.icons();
        this.parts = Objects.requireNonNull(parts, "parts");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Plant und zeichnet das Schaubild zu einem Thema.
     *
     * @return leer, wenn kein brauchbarer Plan zustande kommt (dann zeigt der Podcast kein Bild)
     */
    public Optional<Diagram> compose(String topic, String material, Memory memory) {
        // Die Symbole entstehen parallel und melden ihre Reparaturen zugleich.
        List<String> repairs = java.util.Collections.synchronizedList(new ArrayList<>());
        Plan plan = Optional.ofNullable(memory.load("plan.json")).map(stored -> readPlan(stored, new ArrayList<>()))
                .orElse(null);
        if (plan == null) {
            plan = plan(topic, material, repairs);
            if (plan == null) {
                return Optional.empty();
            }
            memory.save("plan.json", plan.toJson());
        }
        List<CompletableFuture<String>> icons = new ArrayList<>();
        for (int i = 0; i < plan.elements().size(); i++) {
            final int index = i;
            final Element element = plan.elements().get(i);
            String name = String.format(Locale.ROOT, "icon-%02d.svg", index);
            String stored = memory.load(name);
            if (stored != null) {
                icons.add(CompletableFuture.completedFuture(stored));
                continue;
            }
            icons.add(CompletableFuture.supplyAsync(() -> {
                String icon = icon(element, repairs);
                if (icon != null) {
                    memory.save(name, icon);
                }
                return icon;
            }, parts).exceptionally(failure -> {
                repairs.add("Symbol " + (index + 1) + " gescheitert (" + failure.getClass().getSimpleName() + ")");
                return null;
            }));
        }
        List<String> drawn = new ArrayList<>();
        for (CompletableFuture<String> icon : icons) {
            try {
                drawn.add(icon.get());
            } catch (InterruptedException e) {
                // Lauf abgebrochen: nicht auf die übrigen Symbole warten
                icons.forEach(other -> other.cancel(true));
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (java.util.concurrent.ExecutionException e) {
                drawn.add(null);
            }
        }
        String svg = assemble(plan, drawn);
        try {
            SvgDrawing.parse(svg);
        } catch (SvgDrawing.Unsupported | RuntimeException | LinkageError e) {
            repairs.add("Schaubild nicht darstellbar (" + e.getClass().getSimpleName() + "), ohne Symbole");
            svg = assemble(plan, new ArrayList<>(java.util.Collections.nCopies(plan.elements().size(), null)));
        }
        return Optional.of(new Diagram(svg, plan, repairs));
    }

    /**
     * Ein Modellaufruf über den begrenzten Pool, damit auch Plan und Auswahl die Parallel-Einstellung einhalten.
     * Nur aus Fäden außerhalb des Pools rufen. Wird der wartende Faden unterbrochen, wirft er und bleibt unterbrochen.
     */
    static String call(Executor parts, java.util.function.Supplier<String> call) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(call, parts);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Lauf abgebrochen");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    // --- Stufe 1: Plan ------------------------------------------------------------------------------------------

    Plan plan(String topic, String material, List<String> repairs) {
        String prompt = "# THEMA\n" + topic + "\n\n# MATERIAL\n" + cut(material == null ? "" : material,
                MAX_MATERIAL_CHARS) + "\n\n# AUFGABE\nEntwirf ein Schaubild, das den Kern dieses Themas auf einen "
                + "Blick zeigt. Wähle die Form, die am besten passt: \"flow\" (Ablauf, Schritte nacheinander), \"hub\" "
                + "(ein Mittelpunkt mit Bezügen ringsum; das erste Element ist der Mittelpunkt), \"compare\" (Dinge "
                + "nebeneinander) oder \"stack\" (Schichten übereinander). 2 bis " + MAX_ELEMENTS + " Elemente. label: "
                + "deutsch, höchstens drei Wörter. note: deutsch, höchstens sechs Wörter, darf leer sein. icon: auf "
                + "Englisch ein einfaches, gegenständliches Symbol ohne Schrift (etwa \"a gear\", \"a calendar\", "
                + "\"a shield with a check mark\"). title: kurze deutsche Überschrift.\nAntworte mit genau einem "
                + "JSON-Objekt: {\"title\":\"...\",\"form\":\"flow\",\"elements\":[{\"label\":\"...\",\"note\":\"...\","
                + "\"icon\":\"...\"}]}";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            try {
                Plan plan = readPlan(call(parts, () -> planner.complete(PLAN_SYSTEM, prompt)), repairs);
                if (plan != null) {
                    return plan;
                }
                repairs.add("Schaubild: Plan " + attempt + " unbrauchbar");
            } catch (RuntimeException e) {
                repairs.add("Schaubild: Plan-Versuch " + attempt + " gescheitert (" + e.getClass().getSimpleName()
                        + ")");
            }
            if (attempt < ATTEMPTS) {
                pause.before(attempt + 1);
            }
        }
        return null;
    }

    static Plan readPlan(String answer, List<String> repairs) {
        JsonNode root = PodcastWriter.json(answer);
        if (root == null || !root.path("elements").isArray()) {
            return null;
        }
        List<Element> elements = new ArrayList<>();
        for (JsonNode node : root.path("elements")) {
            String label = node.path("label").isString() ? node.path("label").asString() : "";
            if (label.isBlank()) {
                continue;
            }
            elements.add(new Element(label, node.path("note").isString() ? node.path("note").asString() : "",
                    node.path("icon").isString() ? node.path("icon").asString() : ""));
        }
        if (elements.size() < 2) {
            return null;
        }
        if (elements.size() > MAX_ELEMENTS) {
            repairs.add("Schaubild: " + elements.size() + " Elemente auf " + MAX_ELEMENTS + " gekürzt");
            elements = elements.subList(0, MAX_ELEMENTS);
        }
        Form form = Form.parse(root.path("form").isString() ? root.path("form").asString() : null);
        if (form == Form.COMPARE && elements.size() > 3) {
            form = Form.STACK;
            repairs.add("Schaubild: Vergleich mit mehr als drei Elementen als Schichten");
        }
        String title = root.path("title").isString() ? root.path("title").asString() : "";
        return new Plan(title, form, elements);
    }

    // --- Stufe 2: Symbole ---------------------------------------------------------------------------------------

    /** Das Symbol eines Elements als bereinigtes SVG; {@code null}, wenn keins gelingt. */
    String icon(Element element, List<String> repairs) {
        String problem = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String prompt = "Draw the icon: " + element.icon() + "."
                    + (problem == null ? "" : "\n\nYour previous answer was unusable: " + problem
                    + ". Follow the rules exactly and answer with the corrected SVG only.");
            String answer;
            try {
                answer = iconModel.complete(ICON_SYSTEM, prompt);
            } catch (RuntimeException e) {
                repairs.add("Symbol \"" + element.label() + "\": Versuch " + attempt + " gescheitert ("
                        + e.getClass().getSimpleName() + ")");
                if (attempt < ATTEMPTS) {
                    pause.before(attempt + 1);
                }
                continue;
            }
            try {
                String svg = clean(answer);
                problem = iconProblem(svg);
                if (problem == null) {
                    return svg;
                }
            } catch (Unusable e) {
                problem = e.getMessage();
            }
        }
        repairs.add("Symbol \"" + element.label() + "\" ohne Modell (" + problem + ")");
        return null;
    }

    /** Eine Antwort, aus der sich kein Symbol machen lässt; die Meldung geht an das Modell zurück. */
    static final class Unusable extends Exception {
        private static final long serialVersionUID = 1L;

        Unusable(String message) {
            super(message);
        }
    }

    /**
     * Holt das SVG aus der Antwort und entfernt alles, was nicht gezeichnet werden darf: Skripte, eingebettete Inhalte,
     * Bilder, Schrift, Verweise nach außen und Ereignis-Attribute. Die Antwort kommt von einem Modell und gilt als
     * nicht vertrauenswürdig.
     */
    static String clean(String answer) throws Unusable {
        if (answer == null) {
            throw new Unusable("it was empty");
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("<svg");
        int end = lower.lastIndexOf("</svg");
        if (start < 0 || end < start) {
            throw new Unusable("it contained no <svg> element");
        }
        int close = answer.indexOf('>', end);
        String svg = answer.substring(start, close < 0 ? answer.length() : close + 1);
        if (!svg.contains("xmlns=")) {
            svg = svg.replaceFirst("(?i)<svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"");
        }
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        } catch (Exception e) {
            throw new Unusable("it is not well-formed XML");
        }
        org.w3c.dom.Element top = document.getDocumentElement();
        if (!"svg".equals(top.getLocalName())) {
            throw new Unusable("the root element is not <svg>");
        }
        strip(top);
        if (!top.hasAttribute("viewBox")) {
            top.setAttribute("viewBox", "0 0 100 100");
        }
        removeBackground(top);
        top.removeAttribute("width");
        top.removeAttribute("height");
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new Unusable("it could not be serialised");
        }
    }

    /**
     * Entfernt eine flächenfüllende Rechteck-Fläche direkt unter der Wurzel. Geprüft und eingesetzt wird danach dasselbe
     * Markup; {@link SvgDrawing} würde den Hintergrund sonst nur beim Prüfen still entfernen.
     */
    static void removeBackground(org.w3c.dom.Element top) {
        double[] box = viewBox(top.getAttribute("viewBox"));
        NodeList children = top.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"rect".equals(child.getLocalName())) {
                continue;
            }
            org.w3c.dom.Element rect = (org.w3c.dom.Element) child;
            double x = number(rect.getAttribute("x"), 0, box[2]);
            double y = number(rect.getAttribute("y"), 0, box[3]);
            double width = number(rect.getAttribute("width"), 0, box[2]);
            double height = number(rect.getAttribute("height"), 0, box[3]);
            if (x <= box[0] + box[2] * 0.02 && y <= box[1] + box[3] * 0.02 && width >= box[2] * 0.95
                    && height >= box[3] * 0.95) {
                top.removeChild(rect);
            }
        }
    }

    private static double[] viewBox(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split("[\\s,]+");
        if (parts.length == 4) {
            try {
                return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
            } catch (NumberFormatException e) {
                // Vorgabe unten
            }
        }
        return new double[]{0, 0, 100, 100};
    }

    private static double number(String value, double fallback, double whole) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String text = value.trim();
        try {
            if (text.endsWith("%")) {
                return Double.parseDouble(text.substring(0, text.length() - 1)) / 100 * whole;
            }
            return Double.parseDouble(text.replaceAll("px$", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void strip(org.w3c.dom.Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = attributes.getLength() - 1; i >= 0; i--) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase(Locale.ROOT);
            String value = attribute.getNodeValue().strip().toLowerCase(Locale.ROOT);
            boolean external = (name.endsWith("href") && !value.startsWith("#"))
                    || (value.contains("url(") && !value.matches(".*url\\(\\s*#[^)]*\\).*"));
            if (name.startsWith("on") || name.equals("style") || external) {
                element.removeAttributeNode((org.w3c.dom.Attr) attribute);
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                if (FORBIDDEN.contains(local.toLowerCase(Locale.ROOT))) {
                    element.removeChild(child);
                } else {
                    strip((org.w3c.dom.Element) child);
                }
            } else if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                    || child.getNodeType() == Node.COMMENT_NODE) {
                element.removeChild(child);
            }
        }
    }

    /** Wie 3D4J: etwas ist gezeichnet, und der Hintergrund blieb frei. {@code null} = brauchbar. */
    static String iconProblem(String svg) {
        BufferedImage image;
        try {
            image = SvgDrawing.parse(svg).raster(96);
        } catch (SvgDrawing.Unsupported | RuntimeException | LinkageError e) {
            return "it cannot be drawn or shows nothing";
        }
        if (image == null) {
            return "it cannot be drawn or shows nothing";
        }
        double coverage = coverage(image);
        if (coverage < 0.04) {
            return "the drawing is empty or tiny";
        }
        return coverage > 0.92 ? "the background is not transparent; draw only the subject" : null;
    }

    static double coverage(BufferedImage image) {
        long covered = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 32) {
                    covered++;
                }
            }
        }
        return covered / (double) ((long) image.getWidth() * image.getHeight());
    }

    // --- Stufe 3: Zusammensetzen --------------------------------------------------------------------------------

    /** Ein Platz auf der Fläche: Karte mit Mittelpunkt und Größe. */
    record Slot(double x, double y, double width, double height) {
        double centerX() {
            return x + width / 2;
        }

        double centerY() {
            return y + height / 2;
        }
    }

    static List<Slot> layout(Form form, int count) {
        List<Slot> slots = new ArrayList<>();
        double top = 130;
        double bottom = HEIGHT - 50;
        switch (form) {
            case HUB -> {
                // Mittelpunkt in der Mitte, die Bezüge in zwei Spalten links und rechts: nichts überdeckt sich.
                double cy = (top + bottom) / 2;
                slots.add(new Slot(WIDTH / 2.0 - 160, cy - 170, 320, 340));
                int around = count - 1;
                int left = (around + 1) / 2;
                double card = 290;
                for (int i = 0; i < around; i++) {
                    boolean onLeft = i < left;
                    int inColumn = onLeft ? left : around - left;
                    int row = onLeft ? i : i - left;
                    double gap = 24;
                    double height = Math.min(220, (bottom - top - gap * (inColumn - 1)) / inColumn);
                    double total = inColumn * height + (inColumn - 1) * gap;
                    double y = top + (bottom - top - total) / 2 + row * (height + gap);
                    slots.add(new Slot(onLeft ? 70 : WIDTH - 70 - card, y, card, height));
                }
            }
            case STACK -> {
                double gap = 18;
                double height = (bottom - top - gap * (count - 1)) / count;
                for (int i = 0; i < count; i++) {
                    slots.add(new Slot(160, top + i * (height + gap), WIDTH - 320, height));
                }
            }
            default -> {
                // FLOW mit Pfeilluecken, COMPARE mit schmalerem Abstand
                double gap = form == Form.FLOW ? 70 : 40;
                double margin = 60;
                double width = Math.min(330, (WIDTH - 2 * margin - gap * (count - 1)) / count);
                double total = count * width + (count - 1) * gap;
                double x0 = (WIDTH - total) / 2;
                double height = Math.min(400, bottom - top);
                double y0 = top + (bottom - top - height) / 2;
                for (int i = 0; i < count; i++) {
                    slots.add(new Slot(x0 + i * (width + gap), y0, width, height));
                }
            }
        }
        return slots;
    }

    /** Setzt das Schaubild aus Plan und Symbolen zusammen; ein fehlendes Symbol ({@code null}) wird ein Punkt. */
    static String assemble(Plan plan, List<String> icons) {
        List<Slot> slots = layout(plan.form(), plan.elements().size());
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(WIDTH).append(' ')
                .append(HEIGHT).append("\" width=\"").append(WIDTH).append("\" height=\"").append(HEIGHT).append("\">");
        svg.append("<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" "
                + "markerHeight=\"7\" orient=\"auto\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"").append(OUTLINE)
                .append("\"/></marker></defs>");
        svg.append("<rect x=\"0\" y=\"0\" width=\"").append(WIDTH).append("\" height=\"").append(HEIGHT)
                .append("\" fill=\"#FAFBFD\"/>");
        if (!plan.title().isBlank()) {
            svg.append(text(WIDTH / 2.0, 78, plan.title(), 40, "bold", OUTLINE));
        }
        connectors(svg, plan.form(), slots);
        for (int i = 0; i < slots.size(); i++) {
            card(svg, slots.get(i), plan.elements().get(i), i < icons.size() ? icons.get(i) : null, i,
                    plan.form() == Form.HUB && i == 0, plan.form() == Form.STACK);
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static void connectors(StringBuilder svg, Form form, List<Slot> slots) {
        String line = "\" stroke=\"" + OUTLINE + "\" stroke-width=\"4\" stroke-linecap=\"round\"";
        if (form == Form.FLOW) {
            for (int i = 0; i + 1 < slots.size(); i++) {
                Slot a = slots.get(i);
                Slot b = slots.get(i + 1);
                double y = a.centerY();
                svg.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f",
                        a.x() + a.width() + 10, y, b.x() - 12, y)).append(line)
                        .append(" marker-end=\"url(#arrow)\"/>");
            }
        } else if (form == Form.HUB) {
            Slot hub = slots.get(0);
            for (int i = 1; i < slots.size(); i++) {
                Slot other = slots.get(i);
                svg.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f",
                        hub.centerX(), hub.centerY(), other.centerX(), other.centerY())).append(line)
                        .append(" stroke-dasharray=\"2 10\"/>");
            }
        }
    }

    private static void card(StringBuilder svg, Slot slot, Element element, String icon, int index, boolean hub,
                             boolean band) {
        String color = PALETTE.get(index % PALETTE.size());
        String tint = TINTS.get(index % TINTS.size());
        svg.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"22\" "
                        + "fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>", slot.x(), slot.y(), slot.width(),
                slot.height(), hub ? tint : "#FFFFFF", color, hub ? "6" : "4"));
        if (band) {
            double size = Math.min(slot.height() - 24, 120);
            double iconX = slot.x() + 28;
            double iconY = slot.centerY() - size / 2;
            svg.append(icon(icon, iconX, iconY, size, color, index));
            double textX = iconX + size + 30;
            svg.append(leftText(textX, slot.centerY() - (element.note().isEmpty() ? -10 : 6), element.label(), 34,
                    OUTLINE));
            if (!element.note().isEmpty()) {
                svg.append(leftText(textX, slot.centerY() + 34, element.note(), 24, "#4A4E69"));
            }
            return;
        }
        // Niedrige Karten mit Zusatz geben dem Text mehr Platz, damit der Zusatz nicht wegfällt.
        double share = slot.height() < 300 && !element.note().isEmpty() ? 0.36 : 0.5;
        double size = Math.min(slot.width() * 0.62, slot.height() * share);
        double iconY = slot.y() + slot.height() * (share < 0.5 ? 0.07 : 0.1);
        svg.append(icon(icon, slot.centerX() - size / 2, iconY, size, color, index));
        double labelSize = slot.width() < 230 ? 24 : 30;
        List<String> label = wrap(element.label(), slot.width() < 230 ? 14 : 17);
        double y = iconY + size + labelSize + 14;
        for (String line : label) {
            svg.append(text(slot.centerX(), y, line, labelSize, "bold", OUTLINE));
            y += labelSize * 1.15;
        }
        if (!element.note().isEmpty()) {
            double noteSize = slot.width() < 230 ? 18 : 22;
            y += 6;
            for (String line : wrap(element.note(), slot.width() < 230 ? 20 : 24)) {
                if (y > slot.y() + slot.height() - 10) {
                    break;
                }
                svg.append(text(slot.centerX(), y, line, noteSize, "normal", "#4A4E69"));
                y += noteSize * 1.2;
            }
        }
    }

    private static String icon(String icon, double x, double y, double size, String color, int index) {
        if (icon == null) {
            double r = size * 0.32;
            return String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" "
                    + "stroke-width=\"4\"/>", x + size / 2, y + size / 2, r, color, OUTLINE);
        }
        String prefix = "i" + index + "-";
        String body = icon.replace("id=\"", "id=\"" + prefix).replace("url(#", "url(#" + prefix)
                .replace("href=\"#", "href=\"#" + prefix);
        String placed = String.format(Locale.ROOT, "<svg x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\"", x, y,
                size, size);
        return body.replaceFirst("(?i)<svg", java.util.regex.Matcher.quoteReplacement(placed));
    }

    private static String text(double x, double y, String content, double size, String weight, String color) {
        return String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"sans-serif\" "
                + "font-size=\"%.0f\" font-weight=\"%s\" fill=\"%s\">%s</text>", x, y, size, weight, color,
                escape(content));
    }

    private static String leftText(double x, double y, String content, double size, String color) {
        return String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" font-family=\"sans-serif\" font-size=\"%.0f\" "
                + "fill=\"%s\">%s</text>", x, y, size, color, escape(content));
    }

    /** Bricht an Wortgrenzen um; höchstens zwei Zeilen, der Rest wird gekürzt. */
    static List<String> wrap(String text, int perLine) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > perLine) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.size() > 2) {
            List<String> kept = new ArrayList<>(lines.subList(0, 2));
            kept.set(1, cut(kept.get(1) + " " + lines.get(2), perLine));
            return kept;
        }
        return lines;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int space = text.lastIndexOf(' ', max - 1);
        return text.substring(0, space > max / 2 ? space : max - 1) + "…";
    }
}
