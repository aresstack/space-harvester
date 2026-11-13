package com.aresstack.spaceharvester.testcorpus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Defekte, leere und verschlüsselte Dateien für den Status am Eintrag (ADR 0026, Punkt 7 und Abnahme 7). Jede Datei hat
 * eine Endung und einen Medientyp, die etwas Lesbares versprechen; lesen lässt sich keine.
 */
public final class DamagedFiles {

    /** Kennwort der verschlüsselten Dateien. */
    public static final String PASSWORD = "geheim";

    private DamagedFiles() {
    }

    /** Das erste Drittel einer gültigen Datei (für Word: ZIP ohne Zentralverzeichnis). */
    public static byte[] truncated(byte[] valid) {
        return Arrays.copyOf(valid, Math.max(1, valid.length / 3));
    }

    /** Ein PDF-Kopf, danach 2 KiB Bytes ohne Sinn. */
    public static byte[] garbagePdf() {
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] content = Arrays.copyOf(head, head.length + 2048);
        for (int i = head.length; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        return content;
    }

    /** Eine leere Datei (0 Bytes). */
    public static byte[] empty() {
        return new byte[0];
    }

    /** Das PDF {@code valid}, mit {@link #PASSWORD} als Benutzerkennwort verschlüsselt (AES 128). */
    public static byte[] encryptedPdf(byte[] valid) {
        try (PDDocument document = Loader.loadPDF(valid); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("eigner-" + PASSWORD, PASSWORD,
                    new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ein Office-Dokument (Word, Excel, PowerPoint im OOXML-Format), mit {@link #PASSWORD} verschlüsselt wie von Office
     * („agile“): ein OLE2-Behälter mit {@code EncryptionInfo} und {@code EncryptedPackage}.
     */
    public static byte[] encryptedOffice(byte[] valid) {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword(PASSWORD);
            try (OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(valid));
                 OutputStream encrypted = encryptor.getDataStream(fs)) {
                opc.save(encrypted);
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException | InvalidFormatException e) {
            throw new IllegalStateException(e);
        }
    }
}
