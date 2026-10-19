package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FTP-Sitzung im JES-Modus ({@code SITE FILETYPE=JES}): Jobs auflisten und die gesamte Ausgabe eines Jobs lesen.
 * Übernommen aus MainframeMate {@code JesFtpService} (Anmeldung, {@code JESINTERFACELEVEL=2}, Filter über
 * {@code SITE JESOWNER/JESJOBNAME/JESSTATUS}, Parsen der {@code LIST}-Zeilen, Ausgabe über {@code <jobId>.x}),
 * ohne Löschen, Einstellungs-Singleton und Spool-Proben.
 */
final class JesFtpSession implements FtpSessionPool.Session {

    // Beispiel (JESINTERFACELEVEL 2): JOBNAME  JOB12345 OWNER    OUTPUT  A        RC=0000 3 spool files
    private static final Pattern JOB_LINE = Pattern.compile(
            "^(\\S+)\\s+(JOB\\d+|STC\\d+|TSU\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S*)\\s*(?:RC=(\\S+))?.*?(\\d+)\\s+spool",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_LINE_SIMPLE = Pattern.compile(
            "^(\\S+)\\s+((?:JOB|STC|TSU|J)\\d+)\\s+(\\S+)\\s+(OUTPUT|ACTIVE|INPUT|HELD)",
            Pattern.CASE_INSENSITIVE);

    /** Ein Job aus der Auflistung. */
    static final class JesJob {
        final String jobId;
        final String jobName;
        final String owner;
        final String status;
        final String retCode;

        JesJob(String jobId, String jobName, String owner, String status, String retCode) {
            this.jobId = jobId;
            this.jobName = jobName;
            this.owner = owner;
            this.status = status;
            this.retCode = retCode;
        }
    }

    private final FTPClient ftp = new FTPClient();
    private final FtpConnectionSettings settings;
    private final String user;
    private boolean closed;

    private JesFtpSession(FtpConnectionSettings settings, String user) {
        this.settings = settings;
        this.user = user;
    }

    static JesFtpSession open(FtpConnectionSettings settings, String user, char[] password) throws IOException {
        JesFtpSession session = new JesFtpSession(settings, user);
        try {
            session.connect(password);
            return session;
        } catch (IOException | RuntimeException e) {
            session.close();
            throw e;
        }
    }

    private void connect(char[] password) throws IOException {
        ftp.setControlEncoding(settings.encoding.name());
        ftp.setConnectTimeout(settings.connectTimeoutMillis);
        ftp.setDefaultTimeout(settings.connectTimeoutMillis);
        ftp.connect(settings.host, settings.port);
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            throw new IOException("FTP-Verbindung abgelehnt (" + ftp.getReplyCode() + ")");
        }
        ftp.setSoTimeout(settings.readTimeoutMillis);
        ftp.setDataTimeout(Duration.ofMillis(settings.readTimeoutMillis));
        if (!ftp.login(user, new String(password))) {
            throw new CommonsNetFtpSession.FtpLoginException("FTP-Anmeldung abgelehnt (" + ftp.getReplyCode() + ")");
        }
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.ASCII_FILE_TYPE);
        if (!ftp.sendSiteCommand("FILETYPE=JES")) {
            throw new IOException("Server erlaubt FILETYPE=JES nicht (" + ftp.getReplyCode() + ")");
        }
        // Strukturierte Auflistung anfordern; nicht jeder Server kann das, dann bleibt die einfache Form.
        ftp.sendSiteCommand("JESINTERFACELEVEL=2");
    }

    /**
     * Jobs zu den Filtern.
     *
     * @param ownerFilter Besitzer oder {@code *}; leer = angemeldeter Benutzer
     * @param jobNameFilter Jobname, auch mit {@code *}
     * @param statusFilter {@code ALL}, {@code OUTPUT}, {@code ACTIVE} oder {@code INPUT}
     */
    List<JesJob> listJobs(String ownerFilter, String jobNameFilter, String statusFilter) throws IOException {
        ftp.sendSiteCommand("JESOWNER=" + (ownerFilter == null || ownerFilter.isEmpty() ? user : ownerFilter));
        ftp.sendSiteCommand("JESJOBNAME=" + (jobNameFilter == null || jobNameFilter.isEmpty() ? "*" : jobNameFilter));
        ftp.sendSiteCommand("JESSTATUS=" + (statusFilter == null || statusFilter.isEmpty() ? "ALL" : statusFilter));
        List<JesJob> jobs = new ArrayList<JesJob>();
        FTPFile[] files = ftp.listFiles();
        if (files != null) {
            for (FTPFile file : files) {
                String raw = file == null ? null : file.getRawListing();
                JesJob job = raw == null ? null : parseJobLine(raw.trim());
                if (job != null) {
                    jobs.add(job);
                }
            }
        }
        if (jobs.isEmpty()) {
            String[] names = ftp.listNames();
            if (names != null) {
                for (String name : names) {
                    JesJob job = name == null ? null : parseJobLine(name.trim());
                    if (job != null) {
                        jobs.add(job);
                    }
                }
            }
        }
        return jobs;
    }

    /** Gesamte Ausgabe eines Jobs (alle Spool-Dateien hintereinander, {@code <jobId>.x}). */
    String readOutput(String jobId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        if (!ftp.retrieveFile(jobId + ".x", out)) {
            throw new CommonsNetFtpSession.FtpNotFoundException(
                    "Jobausgabe " + jobId + " nicht abrufbar (" + ftp.getReplyCode() + ")");
        }
        return new String(out.toByteArray(), settings.encoding);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (ftp.isConnected()) {
                try {
                    ftp.logout();
                } finally {
                    ftp.disconnect();
                }
            }
        } catch (IOException e) {
            // Verbindung ist ohnehin weg.
        }
    }

    /** Wie MainframeMate {@code JesFtpService.parseJobLine}: volle Form (Stufe 2), sonst die einfache. */
    static JesJob parseJobLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("JOBNAME") || upper.startsWith("---") || upper.startsWith("OWNER")) {
            return null;
        }
        Matcher m = JOB_LINE.matcher(line);
        if (m.find()) {
            return new JesJob(m.group(2).toUpperCase(Locale.ROOT), m.group(1), m.group(3),
                    m.group(4).toUpperCase(Locale.ROOT), m.group(6));
        }
        Matcher simple = JOB_LINE_SIMPLE.matcher(line);
        if (simple.find()) {
            return new JesJob(simple.group(2).toUpperCase(Locale.ROOT), simple.group(1), simple.group(3),
                    simple.group(4).toUpperCase(Locale.ROOT), null);
        }
        return null;
    }
}
