package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.FileConnection;
import com.aresstack.spaceharvester.deck.ftp.FtpConnector;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.jes.JesConnection;
import java.io.IOException;

/** Adapter: FTP über Apache Commons Net (dieselbe Bibliothek wie MainframeMate und die Wissensquellen). */
public final class CommonsNetFtpConnector implements FtpConnector {

    @Override
    public FileConnection files(FtpProfile profile, String user, char[] password) throws IOException {
        return new CommonsNetFiles(profile, user, password);
    }

    @Override
    public JesConnection jes(FtpProfile profile, String user, char[] password) throws IOException {
        return new CommonsNetJes(profile, user, password);
    }
}
