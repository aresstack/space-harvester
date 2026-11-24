package com.aresstack.spaceharvester.deck.ftp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ein Schein-z/OS-FTP-Server für Tests und Bildschirmfotos: Datasets und Members mit Satzstruktur ({@code STRU R},
 * Satzende {@code FF01}, Dateiende {@code FF02}, FB-Sätze mit Leerzeichen aufgefüllt), JES-Modus mit Jobliste,
 * Spool-Liste, Spool-Abruf, Löschen und Einreichen. Antworten folgen dem z/OS-Communications-Server. Alle Namen und
 * Inhalte sind erfunden.
 */
public final class FakeMvsFtpHost implements AutoCloseable {

    private static final Charset WIRE = StandardCharsets.ISO_8859_1;

    /** Eine Spool-Datei eines Schein-Jobs. */
    public record Spool(String step, String ddName, String text) {
    }

    /** Ein Schein-Job. */
    public record Job(String id, String name, String owner, String status, String jobClass, String returnCode,
                      List<Spool> spool) {
    }

    private final ServerSocket server;
    private final String user;
    private final String password;
    private final Map<String, String> sequential = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> partitioned = new ConcurrentHashMap<>();
    private final List<Job> jobs = new CopyOnWriteArrayList<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<String> submitted = new CopyOnWriteArrayList<>();
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger nextJob = new AtomicInteger(4711);
    private volatile boolean jesInterfaceLevel2 = true;
    /** Wie der echte Host aus #1317: NLST 'PDS' ohne (*) liefert nur das Dataset selbst. */
    private volatile boolean membersWithoutWildcard = false;
    private volatile java.util.concurrent.CountDownLatch jesRetrieveGate;
    private final java.util.concurrent.CountDownLatch jesRetrieveStalled = new java.util.concurrent.CountDownLatch(1);

    public FakeMvsFtpHost(String user, String password) throws IOException {
        this.user = user.toUpperCase(Locale.ROOT);
        this.password = password;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("fake-mvs-ftp").start(this::acceptLoop);
    }

    /** Ein Host mit erfundenem Bestand: Benutzer {@code KKR}, Passwort {@code geheim}. */
    public static FakeMvsFtpHost sample() throws IOException {
        FakeMvsFtpHost host = new FakeMvsFtpHost("KKR", "geheim");
        host.member("KKR.JCL", "BUILD", "//BUILD    JOB (ACCT),'ORBIT',CLASS=A\n//STEP1    EXEC PGM=IEFBR14\n");
        host.member("KKR.JCL", "STERNE", "//STERNE   JOB (ACCT),'STERNE',CLASS=A\n//S1       EXEC PGM=IDCAMS\n");
        host.member("KKR.SRC.COBOL", "KOMET", "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. KOMET.\n");
        host.dataset("KKR.DATA.LOG", "Grüße an Bord\nZeile zwei");
        host.job(new Job("JOB04700", "BUILD", "KKR", "OUTPUT", "A", "0000", List.of(
                new Spool("JES2", "JESMSGLG", "J E S 2  J O B  L O G\n10.00.00 JOB04700 $HASP373 BUILD STARTED"),
                new Spool("JES2", "JESJCL", "1 //BUILD    JOB (ACCT),'ORBIT',CLASS=A\n2 //STEP1    EXEC PGM=IEFBR14"),
                new Spool("STEP1", "SYSPRINT", "IDCAMS  SYSTEM SERVICES\nIDC0001I FUNCTION COMPLETED"))));
        host.job(new Job("JOB04701", "STERNE", "KKR", "OUTPUT", "A", "0012", List.of(
                new Spool("JES2", "JESMSGLG", "J E S 2  J O B  L O G\nIEF450I STERNE S1 - ABEND=S000 U0012"))));
        host.job(new Job("JOB04702", "KOMET", "KKR", "ACTIVE", "B", null, List.of()));
        return host;
    }

    public void member(String pds, String member, String text) {
        partitioned.computeIfAbsent(pds.toUpperCase(Locale.ROOT), key -> new ConcurrentHashMap<>())
                .put(member.toUpperCase(Locale.ROOT), text);
    }

    public void dataset(String name, String text) {
        sequential.put(name.toUpperCase(Locale.ROOT), text);
    }

    public void job(Job job) {
        jobs.add(job);
    }

    /** Wie ein Host ohne JESINTERFACELEVEL=2: keine Spool-Liste, nur die Gesamtausgabe. */
    /** JES-RETR antwortet erst, wenn {@code gate} aufgeht (hängender Host); {@link #awaitStalledRetrieve} meldet es. */
    public void stallJesRetrieve(java.util.concurrent.CountDownLatch gate) {
        jesRetrieveGate = gate;
    }

    public boolean awaitStalledRetrieve(long seconds) throws InterruptedException {
        return jesRetrieveStalled.await(seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** NLST 'PDS' liefert auch ohne (*) die Members (älteres Verhalten des Scheinhosts). */
    public void membersWithoutWildcard() {
        membersWithoutWildcard = true;
    }

    public void jesInterfaceLevel1() {
        jesInterfaceLevel2 = false;
    }

    public int port() {
        return server.getLocalPort();
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    public FtpProfile profile() {
        return new FtpProfile(host(), port(), FtpProfile.DEFAULT_ENCODING, 10);
    }

    /** Der Text eines Members oder sequenziellen Datasets, {@code null} wenn es ihn nicht gibt. */
    public String text(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        int open = upper.indexOf('(');
        if (open > 0) {
            Map<String, String> members = partitioned.get(upper.substring(0, open));
            return members == null ? null : members.get(upper.substring(open + 1, upper.length() - 1));
        }
        return sequential.get(upper);
    }

    public boolean datasetExists(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return partitioned.containsKey(upper) || sequential.containsKey(upper);
    }

    public List<String> commands() {
        return List.copyOf(commands);
    }

    public List<String> submitted() {
        return List.copyOf(submitted);
    }

    public List<Job> jobs() {
        return List.copyOf(jobs);
    }

    public int logins() {
        return logins.get();
    }

    /** Trennt alle Verbindungen wie ein Host, der die Sitzung wegen Untätigkeit beendet. */
    public void dropConnections() {
        for (Socket socket : clients) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // schon zu
            }
        }
        clients.clear();
    }

    @Override
    public void close() throws IOException {
        dropConnections();
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                clients.add(socket);
                Thread.ofPlatform().daemon().name("fake-mvs-ftp-client").start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Zustand je Steuerverbindung. */
    private final class Client {
        String pendingUser;
        boolean loggedIn;
        boolean jes;
        boolean record;
        boolean binary;
        String jesOwner;
        String jesJobName = "*";
        String jesStatus = "ALL";
        ServerSocket passive;
    }

    private void serve(Socket socket) {
        Client client = new Client();
        try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), WIRE));
             PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), WIRE), true)) {
            reply(out, "220-FTPD1 IBM FTP CS V2R5 at FAKEHOST, 10:00:00 on 2026-11-22.");
            reply(out, "220 Connection will close if idle for more than 5 minutes.");
            String line;
            while ((line = in.readLine()) != null) {
                String verb = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
                String arg = line.contains(" ") ? line.substring(line.indexOf(' ') + 1).trim() : "";
                verb = verb.toUpperCase(Locale.ROOT);
                commands.add(verb.equals("PASS") ? "PASS ****" : line);
                if (!handle(client, verb, arg, out)) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // Verbindung weg
        } finally {
            clients.remove(socket);
        }
    }

    private boolean handle(Client client, String verb, String arg, PrintWriter out) throws IOException {
        if (!client.loggedIn && !verb.equals("USER") && !verb.equals("PASS") && !verb.equals("QUIT")) {
            reply(out, "530 Not logged in.");
            return true;
        }
        switch (verb) {
            case "USER" -> {
                client.pendingUser = arg.toUpperCase(Locale.ROOT);
                reply(out, "331 Send password please.");
            }
            case "PASS" -> {
                if (user.equals(client.pendingUser) && password.equals(arg)) {
                    client.loggedIn = true;
                    client.jesOwner = user;
                    logins.incrementAndGet();
                    reply(out, "230 " + user + " is logged on.  Working directory is \"" + user + ".\".");
                } else {
                    reply(out, "530 PASS command failed");
                }
            }
            case "SYST" -> reply(out, "215 MVS is the operating system of this server. FTP Server is running on z/OS.");
            case "TYPE" -> {
                client.binary = arg.toUpperCase(Locale.ROOT).startsWith("I");
                reply(out, "200 Representation type is " + (client.binary ? "Image" : "Ascii NonPrint"));
            }
            case "STRU" -> {
                client.record = arg.toUpperCase(Locale.ROOT).startsWith("R");
                reply(out, "250 Data structure is " + (client.record ? "Record" : "File"));
            }
            case "MODE" -> reply(out, "200 Data transfer mode is Stream");
            case "NOOP" -> reply(out, "200 OK");
            case "PWD" -> reply(out, "257 \"'" + user + ".'\" is working directory.");
            case "PASV" -> {
                if (client.passive != null) {
                    client.passive.close();
                }
                client.passive = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                int port = client.passive.getLocalPort();
                reply(out, "227 Entering Passive Mode (127,0,0,1," + (port >> 8) + "," + (port & 0xFF) + ")");
            }
            case "SITE" -> site(client, arg, out);
            case "NLST" -> nlst(client, arg, out);
            case "LIST" -> list(client, arg, out);
            case "RETR" -> retr(client, arg, out);
            case "STOR" -> stor(client, arg, out);
            case "DELE" -> dele(client, arg, out);
            case "MKD" -> {
                String name = unquote(arg);
                if (datasetExists(name)) {
                    reply(out, "550 Data set " + name + " already exists");
                } else {
                    partitioned.put(name, new ConcurrentHashMap<>());
                    reply(out, "257 \"'" + name + "'\" created.");
                }
            }
            case "RMD" -> reply(out, "550 RMD not supported for MVS data sets");
            case "QUIT" -> {
                reply(out, "221 Quit command received. Goodbye.");
                return false;
            }
            default -> reply(out, "502 Command not implemented: " + verb);
        }
        return true;
    }

    private void site(Client client, String arg, PrintWriter out) {
        String upper = arg.toUpperCase(Locale.ROOT);
        if (upper.startsWith("FILETYPE=")) {
            client.jes = upper.endsWith("JES");
        } else if (upper.startsWith("JESOWNER=")) {
            client.jesOwner = upper.substring("JESOWNER=".length());
        } else if (upper.startsWith("JESJOBNAME=")) {
            client.jesJobName = upper.substring("JESJOBNAME=".length());
        } else if (upper.startsWith("JESSTATUS=")) {
            client.jesStatus = upper.substring("JESSTATUS=".length());
        } else if (upper.startsWith("JESINTERFACELEVEL=") && !jesInterfaceLevel2) {
            reply(out, "501 JESINTERFACELEVEL not supported");
            return;
        }
        reply(out, "200 SITE command was accepted");
    }

    private void nlst(Client client, String arg, PrintWriter out) throws IOException {
        if (client.jes) {
            send(client, out, String.join("\r\n", jobs.stream().map(Job::id).toList()) + "\r\n");
            return;
        }
        String name = unquote(arg);
        List<String> names = new ArrayList<>();
        if (name.endsWith(".*")) {
            String prefix = name.substring(0, name.length() - 1);
            TreeSet<String> all = new TreeSet<>(partitioned.keySet());
            all.addAll(sequential.keySet());
            all.stream().filter(candidate -> candidate.startsWith(prefix)).forEach(names::add);
        } else if (name.endsWith("(*)")) {
            // Wie z/OS: Members nur mit (*); ein sequenzielles Dataset ist dann „not a partitioned data set“.
            String dataset = name.substring(0, name.length() - 3);
            if (sequential.containsKey(dataset)) {
                reply(out, "550 Data set " + dataset + " is not a partitioned data set.");
                return;
            }
            if (partitioned.containsKey(dataset)) {
                if (partitioned.get(dataset).isEmpty()) {
                    reply(out, "550 No members found.");
                    return;
                }
                names.addAll(new TreeMap<>(partitioned.get(dataset)).keySet());
            }
        } else if (partitioned.containsKey(name) && !membersWithoutWildcard) {
            // Manche Hosts listen bei NLST 'PDS' (ohne (*)) nur das Dataset selbst, nicht seine Members.
            names.add(name);
        } else if (partitioned.containsKey(name)) {
            names.addAll(new TreeMap<>(partitioned.get(name)).keySet());
        } else if (sequential.containsKey(name)) {
            names.add(name);
        }
        if (names.isEmpty()) {
            reply(out, "550 No data sets found.");
            return;
        }
        send(client, out, String.join("\r\n", names) + "\r\n");
    }

    private void list(Client client, String arg, PrintWriter out) throws IOException {
        if (!client.jes) {
            listDatasets(client, unquote(arg), out);
            return;
        }
        StringBuilder text = new StringBuilder("JOBNAME  JOBID    OWNER    STATUS CLASS\r\n");
        if (arg.isEmpty()) {
            List<Job> matching = jobs.stream().filter(job -> matches(job.owner(), client.jesOwner))
                    .filter(job -> matches(job.name(), client.jesJobName))
                    .filter(job -> client.jesStatus.equals("ALL") || job.status().equals(client.jesStatus)).toList();
            if (matching.isEmpty()) {
                reply(out, "550 No jobs found on Held queue");
                return;
            }
            matching.forEach(job -> text.append(jobLine(job)).append("\r\n"));
        } else {
            Job job = find(arg);
            if (job == null) {
                reply(out, "550 JESENTRY " + arg + " does not exist");
                return;
            }
            text.append(jobLine(job).replaceAll("\\s+\\d+ spool files$", "")).append("\r\n");
            if (jesInterfaceLevel2) {
                text.append("--------\r\n");
                text.append("         ID  STEPNAME PROCSTEP C DDNAME   BYTE-COUNT\r\n");
                for (int i = 0; i < job.spool().size(); i++) {
                    Spool spool = job.spool().get(i);
                    text.append(String.format(Locale.ROOT, "         %03d %-8s          A %-8s %10d%n", i + 1,
                            spool.step(), spool.ddName(), spool.text().length()).replace("\n", "\r\n"));
                }
                text.append(job.spool().size()).append(" spool files\r\n");
            }
        }
        send(client, out, text.toString());
    }

    /** {@code LIST 'X.*'} wie z/OS: eine Zeile je Dataset mit Dsorg PO (partitioniert) oder PS (sequenziell). */
    private void listDatasets(Client client, String name, PrintWriter out) throws IOException {
        if (partitioned.containsKey(name)) {
            listMembers(client, partitioned.get(name), out);
            return;
        }
        if (!name.endsWith(".*")) {
            reply(out, "550 No data sets found.");
            return;
        }
        String prefix = name.substring(0, name.length() - 1);
        StringBuilder text = new StringBuilder("Volume Unit    Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname\r\n");
        TreeSet<String> all = new TreeSet<>(partitioned.keySet());
        all.addAll(sequential.keySet());
        int count = 0;
        for (String dataset : all) {
            if (dataset.startsWith(prefix)) {
                text.append(String.format(Locale.ROOT, "WRK001 3390   2026/11/20  1   15  FB      80 27920  %-4s %s\r\n",
                        partitioned.containsKey(dataset) ? "PO" : "PS", dataset));
                count++;
            }
        }
        if (count == 0) {
            reply(out, "550 No data sets found.");
            return;
        }
        send(client, out, text.toString());
    }

    /** {@code LIST 'PDS'} wie z/OS mit ISPF-Statistik: Größe = Zeilen, Id = Benutzer. */
    private void listMembers(Client client, Map<String, String> members, PrintWriter out) throws IOException {
        if (members.isEmpty()) {
            reply(out, "550 No members found.");
            return;
        }
        StringBuilder text = new StringBuilder(" Name     VV.MM   Created       Changed      Size  Init   Mod   Id\r\n");
        for (Map.Entry<String, String> member : new TreeMap<>(members).entrySet()) {
            int lines = member.getValue().isEmpty() ? 0 : member.getValue().split("\n", -1).length;
            text.append(String.format(Locale.ROOT, "%-8s  01.00 2026/11/01 2026/11/20 10:15 %5d %5d     0 %s\r\n",
                    member.getKey(), lines, lines, user));
        }
        send(client, out, text.toString());
    }

    private static String jobLine(Job job) {
        String tail = job.status().equals("OUTPUT")
                ? (job.returnCode() == null ? "" : "RC=" + job.returnCode()) + " " + job.spool().size() + " spool files"
                : "";
        return String.format(Locale.ROOT, "%-8s %-8s %-8s %-6s %-8s %s", job.name(), job.id(), job.owner(),
                job.status(), job.jobClass(), tail).stripTrailing();
    }

    private void retr(Client client, String arg, PrintWriter out) throws IOException {
        java.util.concurrent.CountDownLatch gate = jesRetrieveGate;
        if (client.jes && gate != null) {
            jesRetrieveStalled.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (client.jes) {
            int dot = arg.lastIndexOf('.');
            Job job = find(dot > 0 ? arg.substring(0, dot) : arg);
            if (job == null || dot < 0) {
                reply(out, "550 RETR fails: " + arg + " does not exist.");
                return;
            }
            String which = arg.substring(dot + 1);
            String text;
            if (which.equalsIgnoreCase("x")) {
                StringBuilder all = new StringBuilder();
                for (Spool spool : job.spool()) {
                    all.append(spool.text()).append("\r\n !! END OF JES SPOOL FILE !!\r\n");
                }
                text = all.toString();
            } else {
                int index;
                try {
                    index = Integer.parseInt(which) - 1;
                } catch (NumberFormatException e) {
                    index = -1;
                }
                if (index < 0 || index >= job.spool().size()) {
                    reply(out, "550 RETR fails: " + arg + " does not exist.");
                    return;
                }
                text = job.spool().get(index).text().replace("\n", "\r\n") + "\r\n";
            }
            send(client, out, text);
            return;
        }
        String name = unquote(arg);
        String text = text(name);
        if (text == null) {
            reply(out, "550 Data set " + name + " not found");
            return;
        }
        byte[] bytes;
        if (client.binary) {
            bytes = text.getBytes(WIRE);
        } else if (client.record) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            for (String record : body.split("\n", -1)) {
                // FB 80: mit Leerzeichen aufgefüllt, ab und zu ein Füllbyte 00 wie echte Hosts
                encoded.writeBytes(String.format("%-80s", record).getBytes(WIRE));
                encoded.write(0x00);
                encoded.write(0xFF);
                encoded.write(0x01);
            }
            encoded.write(0xFF);
            encoded.write(0x02);
            bytes = encoded.toByteArray();
        } else {
            bytes = text.replace("\n", "\r\n").getBytes(WIRE);
        }
        sendBytes(client, out, bytes);
    }

    private void stor(Client client, String arg, PrintWriter out) throws IOException {
        reply(out, "125 Storing data set " + arg);
        byte[] bytes;
        try (Socket data = client.passive.accept(); InputStream in = data.getInputStream()) {
            bytes = in.readAllBytes();
        }
        client.passive.close();
        client.passive = null;
        if (client.jes) {
            String jcl = new String(bytes, WIRE).replace("\r\n", "\n");
            submitted.add(jcl);
            String id = "JOB0" + nextJob.getAndIncrement();
            String name = jcl.startsWith("//") ? jcl.substring(2, Math.max(2, jcl.indexOf(' '))).trim() : "NONAME";
            jobs.add(new Job(id, name, user, "INPUT", "A", null, List.of()));
            reply(out, "250-It is known to JES as " + id);
            reply(out, "250 Transfer completed successfully.");
            return;
        }
        String name = unquote(arg);
        String text;
        if (client.record) {
            text = new String(bytes, WIRE).replace("ÿ\u0002", "").replace("ÿ\u0001", "\n");
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
        } else {
            text = new String(bytes, WIRE).replace("\r\n", "\n");
        }
        int open = name.indexOf('(');
        if (open > 0) {
            Map<String, String> members = partitioned.get(name.substring(0, open));
            if (members == null) {
                reply(out, "550 Data set " + name.substring(0, open) + " not found");
                return;
            }
            members.put(name.substring(open + 1, name.length() - 1), text);
        } else {
            sequential.put(name, text);
        }
        reply(out, "250 Transfer completed successfully.");
    }

    private void dele(Client client, String arg, PrintWriter out) {
        if (client.jes) {
            Job job = find(arg);
            if (job == null) {
                reply(out, "550 JESENTRY " + arg + " does not exist");
                return;
            }
            jobs.remove(job);
            reply(out, "250 Cancel successful");
            return;
        }
        String name = unquote(arg);
        int open = name.indexOf('(');
        boolean removed;
        if (open > 0) {
            Map<String, String> members = partitioned.get(name.substring(0, open));
            removed = members != null && members.remove(name.substring(open + 1, name.length() - 1)) != null;
        } else {
            removed = sequential.remove(name) != null || partitioned.remove(name) != null;
        }
        reply(out, removed ? "250 " + name + " deleted." : "550 DELE fails: " + name + " does not exist.");
    }

    private Job find(String id) {
        return jobs.stream().filter(job -> job.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    private static boolean matches(String value, String pattern) {
        if (pattern == null || pattern.equals("*")) {
            return true;
        }
        String regex = pattern.toUpperCase(Locale.ROOT).replace("*", ".*").replace("?", ".");
        return value.toUpperCase(Locale.ROOT).matches(regex);
    }

    private void send(Client client, PrintWriter out, String text) throws IOException {
        sendBytes(client, out, text.getBytes(WIRE));
    }

    private void sendBytes(Client client, PrintWriter out, byte[] bytes) throws IOException {
        if (client.passive == null) {
            reply(out, "425 Use PASV first");
            return;
        }
        reply(out, "125 Sending data set");
        try (Socket data = client.passive.accept(); OutputStream stream = data.getOutputStream()) {
            stream.write(bytes);
        }
        client.passive.close();
        client.passive = null;
        reply(out, "250 Transfer completed successfully.");
    }

    private static void reply(PrintWriter out, String line) {
        out.print(line + "\r\n");
        out.flush();
    }

    private static String unquote(String name) {
        String trimmed = name.trim();
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
