package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import com.aresstack.spaceharvester.deck.ftp.jes.JesConnection;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JesListing;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import com.aresstack.spaceharvester.deck.ftp.jes.SubmittedJob;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 * Jobs und Spool über eine Verbindung im JES-Modus, übernommen aus MainframeMate ({@code JesFtpService},
 * {@code JesFtpJobSubmitter}): {@code SITE FILETYPE=JES}, nach Möglichkeit {@code JESINTERFACELEVEL=2}, Filter über
 * {@code SITE JESOWNER/JESJOBNAME/JESSTATUS}, Spool über {@code RETR jobId.n} bzw. {@code jobId.x}, Einreichen über
 * {@code STOR}.
 */
final class CommonsNetJes implements JesConnection {

    private final FTPClient ftp;
    private final Charset charset;
    private final String user;

    CommonsNetJes(FtpProfile profile, String user, char[] password) throws IOException {
        this.ftp = Clients.connect(profile, user, password);
        this.charset = profile.charset();
        this.user = user;
        try {
            if (!ftp.sendSiteCommand("FILETYPE=JES")) {
                throw new FtpReplyException("Der Host erlaubt FILETYPE=JES nicht: " + Clients.reply(ftp),
                        ftp.getReplyCode());
            }
            // Gegliederte Spool-Listen; nicht jeder Host kann das, dann hilft die Gesamtausgabe (JobOutput).
            ftp.sendSiteCommand("JESINTERFACELEVEL=2");
            ftp.setParserFactory(new RawLines());
        } catch (IOException | RuntimeException e) {
            Clients.close(ftp);
            throw e;
        }
    }

    @Override
    public String user() {
        return user;
    }

    @Override
    public List<JesJob> jobs(JobFilter filter) throws IOException {
        ftp.sendSiteCommand("JESOWNER=" + filter.ownerOr(user));
        ftp.sendSiteCommand("JESJOBNAME=" + filter.jobName());
        ftp.sendSiteCommand("JESSTATUS=" + filter.status().name());
        List<JesJob> jobs = new ArrayList<>();
        for (String line : lines(ftp.listFiles())) {
            JesJob job = JesListing.parseJob(line);
            if (job != null) {
                jobs.add(job);
            }
        }
        if (jobs.isEmpty() && FTPReply.isNegativePermanent(ftp.getReplyCode()) && ftp.getReplyCode() != 550) {
            throw new FtpReplyException("Jobliste abgelehnt: " + Clients.reply(ftp), ftp.getReplyCode());
        }
        return jobs;
    }

    @Override
    public List<SpoolFile> spoolFiles(String jobId) throws IOException {
        List<SpoolFile> files = new ArrayList<>();
        for (String line : lines(ftp.listFiles(jobId))) {
            SpoolFile file = JesListing.parseSpool(line);
            if (file != null) {
                files.add(file);
            }
        }
        if (files.isEmpty()) {
            String[] names = ftp.listNames(jobId);
            if (names != null) {
                for (String name : names) {
                    SpoolFile file = JesListing.parseSpool(name);
                    if (file != null) {
                        files.add(file);
                    }
                }
            }
        }
        return files;
    }

    @Override
    public String spool(String jobId, int spoolId) throws IOException {
        return retrieve(jobId + "." + spoolId);
    }

    @Override
    public String allSpool(String jobId) throws IOException {
        return retrieve(jobId + ".x");
    }

    private String retrieve(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        if (!ftp.retrieveFile(name, out)) {
            throw new FtpReplyException("Abruf von " + name + " abgelehnt: " + Clients.reply(ftp), ftp.getReplyCode());
        }
        return out.toString(charset);
    }

    @Override
    public void delete(String jobId) throws IOException {
        if (!ftp.deleteFile(jobId)) {
            throw new FtpReplyException("Löschen von " + jobId + " abgelehnt: " + Clients.reply(ftp),
                    ftp.getReplyCode());
        }
    }

    @Override
    public SubmittedJob submit(String jcl) throws IOException {
        if (jcl == null || jcl.isBlank()) {
            throw new IllegalArgumentException("Die JCL ist leer");
        }
        boolean stored = ftp.storeFile("JCL", new ByteArrayInputStream(jcl.getBytes(charset)));
        String reply = ftp.getReplyString();
        if (!stored || !FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            throw new FtpReplyException("Einreichen abgelehnt (Code " + ftp.getReplyCode() + "): " + Clients.reply(ftp),
                    ftp.getReplyCode());
        }
        return new SubmittedJob(JesListing.submittedJobId(reply), JesListing.jobName(jcl), reply);
    }

    @Override
    public boolean alive() {
        return ftp.isConnected();
    }

    @Override
    public void close() {
        Clients.logoutAndClose(ftp);
    }

    private static List<String> lines(FTPFile[] files) {
        List<String> lines = new ArrayList<>();
        if (files != null) {
            for (FTPFile file : files) {
                if (file != null && file.getRawListing() != null && !file.getRawListing().isBlank()) {
                    lines.add(file.getRawListing());
                }
            }
        }
        return lines;
    }
}
