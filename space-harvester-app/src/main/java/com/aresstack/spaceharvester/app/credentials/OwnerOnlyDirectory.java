package com.aresstack.spaceharvester.app.credentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ein Verzeichnis, das nur das eigene Konto lesen darf: unter Windows eine ACL mit genau einem Eintrag (Besitzer,
 * Vollzugriff, an Dateien und Unterordner vererbt), unter POSIX {@code rwx------}. Reines Java, ohne JNI. Dateien darin
 * erben die Rechte schon beim Anlegen, also bevor ein Byte geschrieben wird. Für eine schon vorhandene Datei setzt
 * {@link #secureFile} dieselben Rechte ohne Vererbung ({@code rw-------}), etwa für die Dateien des Tresors.
 */
public final class OwnerOnlyDirectory {

    private OwnerOnlyDirectory() {
    }

    /** Legt das Verzeichnis an und schränkt es ein; {@code false}, wenn das nicht nachweislich gelungen ist. */
    public static boolean secure(Path directory) {
        try {
            Files.createDirectories(directory);
            AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                acl.setAcl(List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        .build()));
                return acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner));
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
            if (posix != null) {
                Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
                posix.setPermissions(ownerOnly);
                return posix.readAttributes().permissions().equals(ownerOnly);
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Beschränkt eine vorhandene Datei auf das eigene Konto; {@code false}, wenn das nicht nachweislich gelungen ist. */
    public static boolean secureFile(Path file) {
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                acl.setAcl(List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build()));
                return acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner));
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
                posix.setPermissions(ownerOnly);
                return posix.readAttributes().permissions().equals(ownerOnly);
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
