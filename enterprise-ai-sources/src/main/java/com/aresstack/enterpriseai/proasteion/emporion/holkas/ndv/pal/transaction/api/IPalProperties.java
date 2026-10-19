package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.EAttachSessionType;

/**
 * Schnittstelle für die Verbindungs- und Servereigenschaften einer PAL-Sitzung.
 */
public interface IPalProperties {

    String getDefaultCodePage();

    int getNatVersion();

    int getNdvType();

    String getNdvTypeString();

    int getNdvVersion();

    int getPalVersion();

    String getNdvSessionId();

    boolean isMfUnicodeSrcPossible();

    boolean isWebIOServer();

    int getWebioVersion();

    int getLogonCounter();

    boolean isDevEnv();

    String getDevEnvPath();

    String getHostName();

    boolean timeStampCheck();

    String getLogonLibrary();

    EAttachSessionType getAttachSessionType();
}

