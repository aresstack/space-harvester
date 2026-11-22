package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPFileEntryParser;
import org.apache.commons.net.ftp.parser.FTPFileEntryParserFactory;

/**
 * Liefert die Zeilen einer JES-Liste unverändert. Der MVS-Parser von Commons Net verwirft die erste Zeile und alle
 * Jobs, die nicht OUTPUT sind; die Zeilen liest {@link com.aresstack.spaceharvester.deck.ftp.jes.JesListing}.
 */
final class RawLines implements FTPFileEntryParser, FTPFileEntryParserFactory {

    @Override
    public FTPFile parseFTPEntry(String listEntry) {
        FTPFile file = new FTPFile();
        file.setRawListing(listEntry);
        file.setName(listEntry.trim());
        return file;
    }

    @Override
    public String readNextEntry(BufferedReader reader) throws IOException {
        return reader.readLine();
    }

    @Override
    public List<String> preParse(List<String> original) {
        return original;
    }

    @Override
    public FTPFileEntryParser createFileEntryParser(String key) {
        return this;
    }

    @Override
    public FTPFileEntryParser createFileEntryParser(FTPClientConfig config) {
        return this;
    }
}
