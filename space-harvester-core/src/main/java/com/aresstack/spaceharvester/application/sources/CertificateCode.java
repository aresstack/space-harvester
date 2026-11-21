package com.aresstack.spaceharvester.application.sources;

import java.awt.image.BufferedImage;

/**
 * Ein vertrautes Zertifikat einer Quelle als QR-Code zur Anzeige am Bildschirm (Strang ZX).
 *
 * @param name  Anzeigename (CN)
 * @param role  Rolle für Benutzer, etwa „Stammzertifizierungsstelle“
 * @param sha1  SHA-1-Fingerabdruck mit Doppelpunkten, wie Windows ihn zeigt
 * @param image der Code, schwarz auf weiß mit Ruhezone; {@code null}, wenn das Zertifikat nicht in einen Code passt
 */
public record CertificateCode(String name, String role, String sha1, BufferedImage image) {
}
