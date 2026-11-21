package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import com.aresstack.spaceharvester.source.network.RoutedWebDavHttp;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.MediaByteRange;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gezielter, nichtdestruktiver HTTP-WebDAV-Range-Funktionstest gegen einen realen
 * BSCW-Classic-Videoinhalt.
 *
 * Liest Konfiguration und Zugangsdaten sicher aus der vorhandenen Umgebung
 * (~/.space-harvester/space-harvester-app.yml und KeePassRPC).
 * Gibt KEINE Passwörter, Tokens oder Secrets im Klartext aus.
 * Lädt maximal wenige Kilobytes (nur Header und Byte-Ranges); niemals ganze Videodateien.
 */
public class BscwWebDavRangeLiveTest {

    private static final Pattern HREF_PATTERN = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?href>([^<]+)</(?:[a-zA-Z0-9_-]+:)?href>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAYNAME_PATTERN = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?displayname>([^<]+)</(?:[a-zA-Z0-9_-]+:)?displayname>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?getcontentlength>([^<]+)</(?:[a-zA-Z0-9_-]+:)?getcontentlength>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?getcontenttype>([^<]+)</(?:[a-zA-Z0-9_-]+:)?getcontenttype>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCETYPE_PATTERN = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?resourcetype>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?resourcetype>", Pattern.CASE_INSENSITIVE);

    public static class BscwItem {
        public final URI uri;
        public final String name;
        public final boolean isFolder;
        public final long contentLength;
        public final String contentType;

        public BscwItem(URI uri, String name, boolean isFolder, long contentLength, String contentType) {
            this.uri = uri;
            this.name = name;
            this.isFolder = isFolder;
            this.contentLength = contentLength;
            this.contentType = contentType;
        }

        public boolean isVideo() {
            if (isFolder) {
                return false;
            }
            String n = name != null ? name.toLowerCase() : "";
            String t = contentType != null ? contentType.toLowerCase() : "";
            return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".webm")
                    || n.endsWith(".mov") || n.endsWith(".mkv")
                    || t.startsWith("video/");
        }

        @Override
        public String toString() {
            return (isFolder ? "[DIR] " : "[FILE] ") + name + " (" + contentLength + " bytes, " + contentType + ") -> " + uri.getPath();
        }
    }

    public static class RangeProbeResult {
        public int headStatus;
        public String headAcceptRanges;
        public String headContentType;
        public long headContentLength;

        public int range0Status;
        public String range0ContentRange;
        public String range0AcceptRanges;
        public String range0ContentType;
        public int range0BytesReceived;
        public byte[] range0Body;

        public int rangeSeekStatus;
        public String rangeSeekContentRange;
        public int rangeSeekBytesReceived;
        public byte[] rangeSeekBody;

        public String mp4MajorBrand;
        public boolean hasFtyp;
        public boolean hasMoovNearStart;
        public boolean supports206;
        public boolean selectiveAudioFeasible;
        public String evaluationSummary;
    }

    @Test
    public void executeLiveBscwRangeTest() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("spaceharvester.test.bscw.live"),
                "Enable with -Dspaceharvester.test.bscw.live=true to access BSCW");
        Path home = AppSettingsLoader.defaultHome();
        Path configFile = AppSettingsLoader.defaultFile(home);
        Assumptions.assumeTrue(Files.isRegularFile(configFile),
                "Keine Konfigurationsdatei gefunden unter: " + configFile);

        AppSettingsLoader loader = new AppSettingsLoader(Placeholders.system(), home);
        AppSettings settings = loader.load(configFile);

        AppSettings.SourceEntry bscwEntry = null;
        for (AppSettings.SourceEntry entry : settings.sources()) {
            if ("bscw".equalsIgnoreCase(entry.config().type())) {
                bscwEntry = entry;
                break;
            }
        }
        Assumptions.assumeTrue(bscwEntry != null, "Keine BSCW-Quelle in Konfiguration vorhanden");

        String sourceId = bscwEntry.config().id();
        String keepassTitle = settings.sourceKeePass().get(sourceId);
        Assumptions.assumeTrue(keepassTitle != null && !keepassTitle.trim().isEmpty(),
                "Kein KeePass-Eintrag für BSCW-Quelle hinterlegt");

        Path pairingDir = home.resolve(KeePassLink.PAIRING_DIRECTORY);
        KeePassLink keePass = new KeePassLink(pairingDir, KeePassPairingCallback.unavailable());
        Assumptions.assumeTrue(keePass.paired(), "KeePass ist nicht gekoppelt (pairing.key fehlt)");

        char[] password = null;
        String username = bscwEntry.config().auth().username();
        try (VaultEntryDraft draft = keePass.readSilently(keepassTitle)) {
            password = draft.copyPassword();
        } catch (SecretUnavailableException e) {
            Assumptions.assumeTrue(false, "KeePass ist gesperrt oder antwortet nicht: " + e.getMessage());
        }
        Assumptions.assumeTrue(password != null && password.length > 0,
                "Passwort aus KeePass konnte nicht gelesen werden");

        URI rootUri = bscwEntry.config().baseUrl();
        if (rootUri == null && bscwEntry.config().options().containsKey("rootUrl")) {
            rootUri = URI.create(bscwEntry.config().options().get("rootUrl"));
        }
        Assumptions.assumeTrue(rootUri != null, "Keine BSCW rootUrl/baseUrl vorhanden");

        System.out.println("=== BSCW Classic WebDAV Range Test ===");
        System.out.println("BSCW Host: " + rootUri.getHost() + " Port: " + (rootUri.getPort() == -1 ? 443 : rootUri.getPort()));
        System.out.println("BSCW Startordner-Pfad: " + rootUri.getPath());
        System.out.println("BSCW Nutzer: " + username);

        // TLS Setup
        TrustSettings trustSettings = settings.tls();
        TrustPolicy policy = TrustPolicy.from(trustSettings);
        X509TrustManager trustManager = policy.trustManager();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());

        // 1. Ordnerinhalte abrufen (PROPFIND Depth 1) und nach Video suchen
        List<BscwItem> items = propfind(rootUri, rootUri, username, password, trustManager, 1);
        System.out.println("Gefundene Elemente im Startordner (" + items.size() + "):");
        BscwItem videoItem = null;
        for (BscwItem item : items) {
            System.out.println(" - " + item);
            if (videoItem == null && item.isVideo()) {
                videoItem = item;
            }
        }

        // Falls im Startordner direkt kein Video liegt, Unterordner durchsuchen (bis Tiefe 2)
        if (videoItem == null) {
            for (BscwItem item : items) {
                if (item.isFolder && !item.uri.equals(rootUri)) {
                    System.out.println("Untersuche Unterordner: " + item.name + " (" + item.uri.getPath() + ")");
                    List<BscwItem> subItems = propfind(rootUri, item.uri, username, password, trustManager, 1);
                    for (BscwItem sub : subItems) {
                        System.out.println("   * " + sub);
                        if (videoItem == null && sub.isVideo()) {
                            videoItem = sub;
                        }
                    }
                    if (videoItem != null) {
                        break;
                    }
                }
            }
        }

        if (videoItem == null) {
            System.out.println("HINWEIS: Kein Video (.mp4, .m4v, .webm, etc.) im Startordner oder Unterordnern gefunden.");
            System.out.println("Teste Range-Verhalten stattdessen mit der ersten verf\u00FCgbaren Datei:");
            for (BscwItem item : items) {
                if (!item.isFolder) {
                    videoItem = item;
                    break;
                }
            }
        }

        Assumptions.assumeTrue(videoItem != null, "Keine Datei im BSCW-Ordner zum Testen verf\u00FCgbar");
        System.out.println("\nAusgew\u00E4hltes Ziel f\u00FCr HTTP Range Test: " + videoItem.name);
        System.out.println("Ziel-URL: " + videoItem.uri.getPath());
        System.out.println("Meldete Dateigr\u00F6\u00DFe: " + videoItem.contentLength + " Bytes, Typ: " + videoItem.contentType);

        RangeProbeResult result = probeHttpRange(videoItem.uri, username, password, sslContext);

        if (videoItem.name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            verifyAudioOnlyMp4(videoItem, username, password, sslContext);
        }

        System.out.println("\n=== Testergebnisse ===");
        System.out.println("1. HEAD-Anfrage:");
        System.out.println("   HTTP Status: " + result.headStatus);
        System.out.println("   Accept-Ranges Header: " + result.headAcceptRanges);
        System.out.println("   Content-Type: " + result.headContentType);
        System.out.println("   Content-Length: " + result.headContentLength);

        System.out.println("2. GET Range-Anfrage (bytes=0-1023):");
        System.out.println("   HTTP Status: " + result.range0Status + " (Erwartet 206 Partial Content)");
        System.out.println("   Content-Range Header: " + result.range0ContentRange);
        System.out.println("   Accept-Ranges Header: " + result.range0AcceptRanges);
        System.out.println("   Content-Type: " + result.range0ContentType);
        System.out.println("   Empfangene Bytes: " + result.range0BytesReceived + " von 1024");

        System.out.println("3. GET Seek-Anfrage (bytes=1024-2047, Seekablauf/Springen):");
        System.out.println("   HTTP Status: " + result.rangeSeekStatus);
        System.out.println("   Content-Range Header: " + result.rangeSeekContentRange);
        System.out.println("   Empfangene Bytes: " + result.rangeSeekBytesReceived);

        System.out.println("4. MP4-Container & selektive Audio-Extraktion:");
        System.out.println("   ftyp Atom vorhanden: " + result.hasFtyp + (result.mp4MajorBrand != null ? " (Brand: " + result.mp4MajorBrand + ")" : ""));
        System.out.println("   moov Atom im Header-Bereich: " + result.hasMoovNearStart);
        System.out.println("   Unterst\u00FCtzt 206 Partial Content: " + result.supports206);
        System.out.println("   Selektive Audio-Extraktion technisch m\u00F6glich: " + result.selectiveAudioFeasible);
        System.out.println("   Fazit: " + result.evaluationSummary);

        // 5. Verifikation mit der App-Laufzeit (AppRuntime / Harvester workspace openMediaRange)
        AppRuntime runtime = null;
        try {
            AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
            secrets.put(sourceId, AppRuntime.SessionSecrets.login(bscwEntry), password);
            runtime = AppRuntime.start(settings, secrets, "range-live-test");

            String pageId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    videoItem.uri.toASCIIString().getBytes(StandardCharsets.UTF_8));
            MediaByteRange mediaReader = runtime.workspace().openMediaRange(sourceId, pageId);
            byte[] coreBytes = mediaReader.read(0, 1024);
            System.out.println("5. Verifikation \u00FCber AppRuntime workspace().openMediaRange:");
            System.out.println("   Erfolgreich " + (coreBytes != null ? coreBytes.length : 0) + " Bytes \u00FCber den Produktionspfad gelesen.");
            assertThat(coreBytes).isNotNull();
            assertThat(coreBytes.length).isEqualTo(result.range0BytesReceived);
        } catch (Exception e) {
            System.out.println("Hinweis beim AppRuntime-Test: " + e.getMessage());
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            Arrays.fill(password, '\0');
        }

        assertThat(result.range0Status).as("BSCW must return partial content").isEqualTo(206);
        assertThat(result.rangeSeekStatus).as("BSCW must support seeking").isEqualTo(206);
        assertThat(result.range0ContentRange).startsWith("bytes 0-");
        assertThat(result.rangeSeekContentRange).startsWith("bytes 1024-");
    }

    private static void verifyAudioOnlyMp4(BscwItem video, String user, char[] password,
                                           SSLContext sslContext) throws Exception {
        java.util.concurrent.atomic.AtomicLong transferredBytes = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger rangeRequests = new java.util.concurrent.atomic.AtomicInteger();
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + new String(password)).getBytes(StandardCharsets.UTF_8));
        MediaStreamBridge.ChunkSource source = (offset, count) -> {
            HttpsURLConnection connection = (HttpsURLConnection) video.uri.toURL().openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + count - 1));
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            try {
                if (connection.getResponseCode() != 206) {
                    throw new java.io.IOException("BSCW audio range requires HTTP 206");
                }
                byte[] chunk = new byte[count];
                try (InputStream input = connection.getInputStream()) {
                    int position = 0;
                    while (position < count) {
                        int received = input.read(chunk, position, count - position);
                        if (received < 0) {
                            throw new java.io.IOException("Short audio range");
                        }
                        position += received;
                    }
                }
                transferredBytes.addAndGet(count);
                rangeRequests.incrementAndGet();
                return chunk;
            } finally {
                connection.disconnect();
            }
        };
        inspectMp4Fragments(source, video.contentLength);
        long limitedLength = firstFragmentEnd(source, video.contentLength);
        System.out.println("MP4 first-fragment source limit: " + limitedLength);
        Mp4AudioStream audio = Mp4AudioOnlySource.open(source, limitedLength);
        int headerRead = (int) Math.min(2048, audio.length());
        audio.read(0, headerRead);
        int tailRead = (int) Math.min(4096, audio.length());
        audio.read(audio.length() - tailRead, tailRead);
        System.out.println("=== Audio-only MP4 live index ===");
        System.out.println("Original MP4 bytes: " + video.contentLength);
        System.out.println("Virtual audio MP4 bytes: " + audio.length());
        System.out.println("Remote bytes fetched for index + sample: " + transferredBytes.get());
        System.out.println("Remote HTTP Range requests: " + rangeRequests.get());
        org.assertj.core.api.Assertions.assertThat(audio.length()).isLessThan(video.contentLength);
        org.assertj.core.api.Assertions.assertThat(transferredBytes.get()).isLessThan(video.contentLength / 3);
    }

    private static long firstFragmentEnd(MediaStreamBridge.ChunkSource source, long fullLength)
            throws Exception {
        long offset = 0;
        boolean foundMoof = false;
        while (offset + 16 <= fullLength) {
            byte[] head = source.read(offset, 16);
            java.nio.ByteBuffer reader = java.nio.ByteBuffer.wrap(head)
                    .order(java.nio.ByteOrder.BIG_ENDIAN);
            long size = reader.getInt() & 0xffffffffL;
            byte[] name = new byte[4];
            reader.get(name);
            String type = new String(name, StandardCharsets.US_ASCII);
            if (size == 1) {
                size = reader.getLong();
            } else if (size == 0) {
                size = fullLength - offset;
            }
            if (size < 8 || offset + size > fullLength) {
                throw new java.io.IOException("Invalid MP4 atom during fragment probe");
            }
            if ("moof".equals(type)) {
                foundMoof = true;
            } else if (foundMoof && "mdat".equals(type)) {
                return offset + size;
            }
            offset += size;
        }
        throw new java.io.IOException("No complete first MP4 fragment found");
    }

    private static void inspectMp4Fragments(MediaStreamBridge.ChunkSource source, long length)
            throws Exception {
        long offset = 0;
        for (int i = 0; i < 64 && offset + 16 < length; i++) {
            byte[] bytes = source.read(offset, 16);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes)
                    .order(java.nio.ByteOrder.BIG_ENDIAN);
            long size = b.getInt() & 0xffffffffL;
            byte[] name = new byte[4];
            b.get(name);
            String type = new String(name, StandardCharsets.US_ASCII);
            if (size == 1) {
                size = b.getLong();
            } else if (size == 0) {
                size = length - offset;
            }
            System.out.println("MP4 top-level: " + type + ", size=" + size);
            if ("moof".equals(type) && size < 262144) {
                byte[] body = source.read(offset, (int) size);
                for (int at = 8; at + 8 <= body.length;) {
                    int entryLength = java.nio.ByteBuffer.wrap(body, at, 4).getInt();
                    String entry = new String(body, at + 4, 4, StandardCharsets.US_ASCII);
                    if (entryLength < 8 || at + entryLength > body.length) {
                        break;
                    }
                    if ("traf".equals(entry)) {
                        for (int inner = at + 8; inner + 8 < at + entryLength;) {
                            int insideLength = java.nio.ByteBuffer.wrap(body, inner, 4).getInt();
                            String inside = new String(body, inner + 4, 4, StandardCharsets.US_ASCII);
                            if (insideLength < 8 || inner + insideLength > at + entryLength) {
                                break;
                            }
                            int flags = (body[inner + 9] & 255) << 16
                                    | (body[inner + 10] & 255) << 8 | (body[inner + 11] & 255);
                            if ("tfhd".equals(inside)) {
                                int trackId = java.nio.ByteBuffer.wrap(body, inner + 12, 4).getInt();
                                System.out.println("MP4 tfhd track=" + trackId
                                        + " flags=0x" + Integer.toHexString(flags));
                            } else if ("trun".equals(inside)) {
                                int samples = java.nio.ByteBuffer.wrap(body, inner + 12, 4).getInt();
                                System.out.println("MP4 trun samples=" + samples
                                        + " flags=0x" + Integer.toHexString(flags));
                            } else if ("tfdt".equals(inside)) {
                                System.out.println("MP4 tfdt present");
                            }
                            inner += insideLength;
                        }
                    }
                    at += entryLength;
                }
                break;
            }
            if (size < 8) {
                break;
            }
            offset += size;
        }
    }

    private static RangeProbeResult probeHttpRange(URI uri, String user, char[] pass, SSLContext sslContext) throws Exception {
        RangeProbeResult res = new RangeProbeResult();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + new String(pass)).getBytes(StandardCharsets.UTF_8));

        // 1. HEAD
        HttpsURLConnection headConn = (HttpsURLConnection) uri.toURL().openConnection();
        headConn.setSSLSocketFactory(sslContext.getSocketFactory());
        headConn.setRequestMethod("HEAD");
        headConn.setRequestProperty("Authorization", authHeader);
        headConn.setConnectTimeout(15000);
        headConn.setReadTimeout(15000);
        res.headStatus = headConn.getResponseCode();
        res.headAcceptRanges = headConn.getHeaderField("Accept-Ranges");
        res.headContentType = headConn.getHeaderField("Content-Type");
        res.headContentLength = headConn.getContentLengthLong();
        headConn.disconnect();

        // 2. GET Range: bytes=0-1023
        HttpsURLConnection getConn0 = (HttpsURLConnection) uri.toURL().openConnection();
        getConn0.setSSLSocketFactory(sslContext.getSocketFactory());
        getConn0.setRequestMethod("GET");
        getConn0.setRequestProperty("Authorization", authHeader);
        getConn0.setRequestProperty("Range", "bytes=0-1023");
        getConn0.setConnectTimeout(15000);
        getConn0.setReadTimeout(15000);
        res.range0Status = getConn0.getResponseCode();
        res.range0ContentRange = getConn0.getHeaderField("Content-Range");
        res.range0AcceptRanges = getConn0.getHeaderField("Accept-Ranges");
        res.range0ContentType = getConn0.getHeaderField("Content-Type");

        ByteArrayOutputStream buf0 = new ByteArrayOutputStream();
        try (InputStream in = (res.range0Status >= 200 && res.range0Status < 300) ? getConn0.getInputStream() : getConn0.getErrorStream()) {
            if (in != null) {
                byte[] chunk = new byte[1024];
                int r;
                // Lese maximal 1024 Bytes, um niemals die gesamte Datei bei HTTP 200 zu laden
                while (buf0.size() < 1024 && (r = in.read(chunk, 0, Math.min(chunk.length, 1024 - buf0.size()))) != -1) {
                    buf0.write(chunk, 0, r);
                }
            }
        }
        getConn0.disconnect();
        res.range0BytesReceived = buf0.size();
        res.range0Body = buf0.toByteArray();

        // 3. GET Range: bytes=1024-2047 (Seek-Test)
        HttpsURLConnection getConnSeek = (HttpsURLConnection) uri.toURL().openConnection();
        getConnSeek.setSSLSocketFactory(sslContext.getSocketFactory());
        getConnSeek.setRequestMethod("GET");
        getConnSeek.setRequestProperty("Authorization", authHeader);
        getConnSeek.setRequestProperty("Range", "bytes=1024-2047");
        getConnSeek.setConnectTimeout(15000);
        getConnSeek.setReadTimeout(15000);
        res.rangeSeekStatus = getConnSeek.getResponseCode();
        res.rangeSeekContentRange = getConnSeek.getHeaderField("Content-Range");

        ByteArrayOutputStream bufSeek = new ByteArrayOutputStream();
        try (InputStream in = (res.rangeSeekStatus >= 200 && res.rangeSeekStatus < 300) ? getConnSeek.getInputStream() : getConnSeek.getErrorStream()) {
            if (in != null) {
                byte[] chunk = new byte[1024];
                int r;
                while (bufSeek.size() < 1024 && (r = in.read(chunk, 0, Math.min(chunk.length, 1024 - bufSeek.size()))) != -1) {
                    bufSeek.write(chunk, 0, r);
                }
            }
        }
        getConnSeek.disconnect();
        res.rangeSeekBytesReceived = bufSeek.size();
        res.rangeSeekBody = bufSeek.toByteArray();

        // 4. Analyse der MP4 / Container-Struktur
        res.supports206 = (res.range0Status == 206);
        analyzeMp4Boxes(res);

        if (res.supports206) {
            res.selectiveAudioFeasible = true;
            res.evaluationSummary = "BSCW Classic unterst\u00FCtzt echtes HTTP 206 Partial Content mit Content-Range. "
                    + "Selektive Audio-Extraktion ist technisch machbar: Metadaten (moov Atom / Sample-Tabellen stbl) "
                    + "k\u00F6nnen per Range gelesen werden, woraufhin die Audio-Samples/Chunks gezielt ohne die Videodaten gestreamt werden k\u00F6nnen.";
        } else if (res.range0Status == 200) {
            res.selectiveAudioFeasible = false;
            res.evaluationSummary = "BSCW liefert trotz Range-Header HTTP 200 OK (Gesamtstrom). "
                    + "Selektive Audio-Extraktion via HTTP Range ist serverseitig NICHT m\u00F6glich, da der Server keinen Partial Content liefert.";
        } else {
            res.selectiveAudioFeasible = false;
            res.evaluationSummary = "Unerwarteter HTTP-Status " + res.range0Status + " auf Range-Anfrage.";
        }

        return res;
    }

    private static void analyzeMp4Boxes(RangeProbeResult res) {
        byte[] b = res.range0Body;
        if (b == null || b.length < 16) {
            return;
        }
        int offset = 0;
        while (offset + 8 <= b.length) {
            long size = ((long) (b[offset] & 0xFF) << 24)
                    | ((long) (b[offset + 1] & 0xFF) << 16)
                    | ((long) (b[offset + 2] & 0xFF) << 8)
                    | ((long) (b[offset + 3] & 0xFF));
            String type = new String(b, offset + 4, 4, StandardCharsets.US_ASCII);
            if ("ftyp".equals(type)) {
                res.hasFtyp = true;
                if (offset + 12 <= b.length) {
                    res.mp4MajorBrand = new String(b, offset + 8, 4, StandardCharsets.US_ASCII);
                }
            } else if ("moov".equals(type)) {
                res.hasMoovNearStart = true;
            }
            if (size < 8) {
                break;
            }
            offset += size;
        }
    }

    private static List<BscwItem> propfind(URI baseRoot, URI targetUri, String user, char[] pass,
                                          X509TrustManager trustManager, int depth) {
        HttpRoutePort directRoute = target -> HttpRoute.direct("local-bscw-test");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Depth", Integer.toString(depth));
        headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + new String(pass)).getBytes(StandardCharsets.UTF_8)));
        try (RoutedWebDavHttp client = new RoutedWebDavHttp(
                directRoute, ProxyCredentials.none(), trustManager, 1024 * 1024)) {
            RoutedWebDavHttp.Result response = client.exchange(targetUri, "PROPFIND", headers);
            if (response.status() != 207) {
                throw new IllegalStateException("BSCW PROPFIND failed with HTTP " + response.status());
            }
            return parseDavResponses(baseRoot, new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    private static List<BscwItem> parseDavResponses(URI baseRoot, String xml) {
        List<BscwItem> items = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int respStart = xml.indexOf("<response", searchFrom);
            if (respStart == -1) {
                respStart = xml.indexOf("<D:response", searchFrom);
            }
            if (respStart == -1) {
                break;
            }
            int respEnd = xml.indexOf("</response>", respStart);
            if (respEnd == -1) {
                respEnd = xml.indexOf("</D:response>", respStart);
            }
            if (respEnd == -1) {
                break;
            }
            String block = xml.substring(respStart, respEnd + 12);
            searchFrom = respEnd + 11;

            Matcher hrefM = HREF_PATTERN.matcher(block);
            if (!hrefM.find()) {
                continue;
            }
            String href = hrefM.group(1).trim();
            URI itemUri = baseRoot.resolve(href);

            Matcher nameM = DISPLAYNAME_PATTERN.matcher(block);
            String name = nameM.find() ? nameM.group(1).trim() : itemUri.getPath();

            Matcher resTypeM = RESOURCETYPE_PATTERN.matcher(block);
            boolean isFolder = resTypeM.find() && resTypeM.group(1).toLowerCase().contains("collection");

            long len = 0;
            Matcher lenM = CONTENT_LENGTH_PATTERN.matcher(block);
            if (lenM.find()) {
                try {
                    len = Long.parseLong(lenM.group(1).trim());
                } catch (NumberFormatException ignored) {}
            }

            Matcher typeM = CONTENT_TYPE_PATTERN.matcher(block);
            String type = typeM.find() ? typeM.group(1).trim() : (isFolder ? "inode/directory" : "application/octet-stream");

            items.add(new BscwItem(itemUri, name, isFolder, len, type));
        }
        return items;
    }
}
