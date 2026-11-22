package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import java.io.IOException;
import java.time.Duration;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

/** Aufbau und Abbau der Commons-Net-Verbindung, wie in MainframeMate (passiv, Text, Zeitlimits aus dem Profil). */
final class Clients {

    private Clients() {
    }

    static FTPClient connect(FtpProfile profile, String user, char[] password) throws IOException {
        FTPClient ftp = new FTPClient();
        ftp.setControlEncoding(profile.encoding());
        if (profile.timeoutSeconds() > 0) {
            Duration timeout = Duration.ofSeconds(profile.timeoutSeconds());
            ftp.setDefaultTimeout((int) timeout.toMillis());
            ftp.setConnectTimeout((int) timeout.toMillis());
            ftp.setDataTimeout(timeout);
        }
        try {
            ftp.connect(profile.host(), profile.port());
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IOException("Verbindung zu " + profile.label() + " abgelehnt: " + reply(ftp));
            }
            if (profile.timeoutSeconds() > 0) {
                ftp.setSoTimeout(profile.timeoutSeconds() * 1000);
            }
            if (!ftp.login(user, new String(password))) {
                throw new FtpReplyException("Anmeldung als " + user + " abgelehnt: " + reply(ftp),
                        ftp.getReplyCode());
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.ASCII_FILE_TYPE);
            return ftp;
        } catch (IOException | RuntimeException e) {
            close(ftp);
            throw e;
        }
    }

    static String reply(FTPClient ftp) {
        String reply = ftp.getReplyString();
        return reply == null || reply.isBlank() ? "(keine Antwort)" : reply.trim().replace("\r", "").replace('\n', ' ');
    }

    /** Trennt sofort (ohne auf eine Abmelde-Antwort zu warten); wirft nie. Trennt auch, wenn gerade ein anderer Thread überträgt. */
    static void close(FTPClient ftp) {
        try {
            if (ftp.isConnected()) {
                ftp.disconnect();
            }
        } catch (IOException ignored) {
            // beim Trennen ist nichts mehr zu retten
        }
    }
}
