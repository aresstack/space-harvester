package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings;
import com.aresstack.spaceharvester.deck.ftp.BinarySample;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bind binary diagnostics to a single explicitly approved resource. */
public final class MvsDiagnosisTools implements ChatToolset {
    public static final String INSPECT = "inspect_mvs_dataset";
    public static final String READ = "read_mvs_bytes";
    public static final String RECORDS = "inspect_mvs_records";
    private static final String DENIED = "Binärdiagnose nicht freigegeben oder Auswahl geändert.";

    public static final class Grant {
        private final FtpSession session;
        private final RemoteEntry entry;
        private volatile boolean revoked;
        private final MvsDiagnosisSettings settings;
        private CompletableFuture<Snapshot> pending;
        private long remaining;

        public Grant(FtpSession session, RemoteEntry entry) {
            this(session, entry, MvsDiagnosisSettings.shared());
        }

        Grant(FtpSession session, RemoteEntry entry, MvsDiagnosisSettings settings) {
            this.session = session;
            this.entry = entry;
            this.settings = settings;
            this.remaining = settings.get(MvsDiagnosisSettings.Value.BUDGET_BYTES);
        }

        public String path() { return entry.path(); }
        public boolean active() { return !revoked; }

        public synchronized void revoke() {
            revoked = true;
            if (pending != null) pending.cancel(false);
            pending = null;
        }

        private synchronized CompletableFuture<Snapshot> snapshot() {
            if (revoked) throw new IllegalStateException(DENIED);
            if (pending == null) {
                pending = session.filesOnce(c -> {
                    if (revoked) throw new IllegalStateException(DENIED);
                    var details = c.datasetDetails(entry.path());
                    if (details.equals(RemoteEntry.Details.NONE)) details = entry.details();
                    return new Snapshot(details, c.readBinarySample(entry.path(),
                            settings.number(MvsDiagnosisSettings.Value.SAMPLE_BYTES)));
                });
            }
            return pending;
        }

        private synchronized byte[] read(BinarySample sample, long offset, int length) {
            if (revoked) throw new IllegalStateException(DENIED);
            int page = settings.number(MvsDiagnosisSettings.Value.READ_BYTES);
            if (length < 1 || length > page || offset < 0 || offset >= sample.capturedBytes()) {
                throw new IllegalArgumentException("Offset außerhalb der Stichprobe oder Länge nicht 1–" + page + ".");
            }
            int count = (int) Math.min(length, sample.capturedBytes() - offset);
            if (count > remaining) throw new IllegalStateException("Diagnosebudget erschöpft; erneut freigeben.");
            remaining -= count;
            return sample.slice(offset, count);
        }
    }

    private record Snapshot(RemoteEntry.Details details, BinarySample sample) {}

    private final ChatToolset base;
    private final Grant grant;
    private final MvsDiagnosisSettings settings;
    private final BooleanSupplier shown;
    private volatile boolean approvedModel;
    private volatile boolean cancelled;

    public MvsDiagnosisTools(ChatToolset base, Grant grant, BooleanSupplier shown) {
        this.base = base;
        this.grant = grant;
        this.shown = shown;
        this.settings = grant.settings;
    }

    @Override
    public void modelSelected(String model) {
        base.modelSelected(model);
        approvedModel = "gpt-oss-120b".equalsIgnoreCase(model)
                || "openai/gpt-oss-120b".equalsIgnoreCase(model);
    }

    private boolean available() {
        return approvedModel && !cancelled && grant.active() && shown.getAsBoolean();
    }

    @Override
    public List<AiChat.Tool> definitions() {
        var tools = new ArrayList<>(base.definitions());
        if (available()) {
            tools.add(new AiChat.Tool(INSPECT, "Ermittelt DCB-Metadaten, Länge und SHA-256 des freigegebenen "
                    + "binären FTP-Transfers (Stichprobe der ersten "
                    + settings.get(MvsDiagnosisSettings.Value.SAMPLE_BYTES) + " Bytes im Speicher).",
                    "{\"type\":\"object\",\"properties\":{}}"));
            tools.add(new AiChat.Tool(READ, "Liest Originalbytes aus der Stichprobe als Hex, ASCII und IBM1047; "
                    + "Offset nullbasiert, höchstens " + settings.get(MvsDiagnosisSettings.Value.READ_BYTES)
                    + " Bytes pro Aufruf, " + settings.get(MvsDiagnosisSettings.Value.BUDGET_BYTES)
                    + " Bytes pro Freigabe.",
                    "{\"type\":\"object\",\"properties\":{\"offset\":{\"type\":\"integer\"},"
                    + "\"length\":{\"type\":\"integer\"}},\"required\":[\"offset\",\"length\"]}"));
            tools.add(new AiChat.Tool(RECORDS, "Prüft mögliche Satzgrenzen bei RECFM F anhand LRECL und "
                    + "Transferlänge. Kein Nachweis fachlicher Satzstrukturen.",
                    "{\"type\":\"object\",\"properties\":{}}"));
        }
        return List.copyOf(tools);
    }

    private static boolean diagnostic(String name) {
        return INSPECT.equals(name) || READ.equals(name) || RECORDS.equals(name);
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (!diagnostic(call.name())) return base.run(call);
        if (!available()) return DENIED;
        try {
            var args = DeckChatTools.arguments(call);
            long offset = args.path("offset").asLong(-1);
            int length = args.path("length").asInt(-1);
            int page = settings.number(MvsDiagnosisSettings.Value.READ_BYTES);
            if (READ.equals(call.name()) && (offset < 0 || length < 1 || length > page)) {
                return "Offset muss >= 0 sein, Länge 1–" + page + ".";
            }
            Snapshot snapshot = grant.snapshot().get(settings.get(MvsDiagnosisSettings.Value.TIMEOUT_SECONDS),
                    TimeUnit.SECONDS);
            if (!available()) return DENIED;
            String result = switch (call.name()) {
                case INSPECT -> describe(snapshot);
                case RECORDS -> records(snapshot);
                case READ -> page(snapshot.sample(), offset, length);
                default -> DENIED;
            };
            return available() ? result : DENIED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Binärdiagnose abgebrochen.";
        } catch (Exception e) {
            return available() ? "Binärdiagnose fehlgeschlagen: " + FtpSession.describe(e) : DENIED;
        }
    }

    private String page(BinarySample sample, long offset, int length) {
        byte[] bytes = grant.read(sample, offset, length);
        long next = offset + bytes.length;
        return "Offset: " + offset + "; geliefert: " + bytes.length + "; nächster Offset: "
                + (next < sample.capturedBytes() ? Long.toString(next) : "Ende der Stichprobe")
                + "; Stichprobe: " + sample.capturedBytes() + "; Transfer: " + sample.totalBytes()
                + "\n" + hex(bytes, offset);
    }

    private String describe(Snapshot s) {
        var d = s.details();
        return grant.path() + "\nDSORG: " + known(d.organization()) + "\nRECFM: " + known(d.recordFormat())
                + "\nLRECL: " + known(d.recordLength()) + "\nBLKSIZE: " + known(d.blockSize())
                + "\nFTP TYPE I / STRU F, Bytes: " + s.sample().totalBytes()
                + "\nStichprobe: " + s.sample().capturedBytes() + " Bytes ab Offset 0"
                + "\nSHA-256: " + s.sample().sha256() + "\nKein physisches DASD-Abbild.";
    }

    private static String records(Snapshot s) {
        int lrecl = s.details().recordLength();
        long size = s.sample().totalBytes();
        if (!"F".equalsIgnoreCase(s.details().recordFormat()) || lrecl <= 0) {
            return "Keine bestätigten festen Satzgrenzen: RECFM/LRECL fehlen oder passen nicht.";
        }
        if (size % lrecl != 0) {
            return "Transferlänge nicht durch LRECL teilbar; " + size / lrecl
                    + " vollständige LRECL-Einheiten, Restbytes: " + size % lrecl
                    + ". Satzgrenzen nicht bestätigt.";
        }
        return "Mögliche feste Sätze: " + size / lrecl + ", Abstand: " + lrecl
                + " Bytes, Offsets n * " + lrecl + " (n ab 0)."
                + " Dies sind Kandidaten im Transfer, keine bestätigten fachlichen Satzgrenzen.";
    }

    private static String known(String value) { return value.isBlank() ? "unbekannt" : value; }
    private static String known(int value) { return value <= 0 ? "unbekannt" : Integer.toString(value); }

    public static String hex(byte[] bytes, long offset) {
        var ebcdic = Charset.forName("IBM1047");
        StringBuilder out = new StringBuilder("Offset    Hex                                               ASCII             IBM1047\n");
        for (int start = 0; start < bytes.length; start += 16) {
            int end = Math.min(start + 16, bytes.length);
            String h = HexFormat.ofDelimiter(" ").formatHex(bytes, start, end);
            String ascii = printable(new String(bytes, start, end - start, StandardCharsets.ISO_8859_1));
            String ebc = printable(new String(bytes, start, end - start, ebcdic));
            out.append(String.format("%08X  %-47s  %-16s  %s%n", offset + start, h, ascii, ebc));
        }
        return out.toString();
    }

    private static String printable(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(Character.isISOControl(c) || c == '\uFFFD' ? '.' : c);
        }
        return out.toString();
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return diagnostic(call.name()) ? "Untersucht freigegebene MVS-Binärdaten" : base.describe(call);
    }

    @Override public String viewContext() {
        return grant.active() ? "Binärdiagnose für " + grant.path()
                + " freigegeben; nur nutzen, wenn die Diagnosewerkzeuge angeboten werden." : "";
    }
    @Override public String viewIdentity() { return base.viewIdentity(); }
    @Override public void cancel() { cancelled = true; base.cancel(); }
}
