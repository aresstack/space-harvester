/**
 * Adapter: Natural Development Server (NDV) als Wissensquelle für Natural-Quellen.
 *
 * <p>Öffentlich ist nur der Quelltyp ({@link com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider}); die
 * Connector-Registry (holkas) bedient das Schema {@code ndv} über den Quellen-Port, die Oberfläche sieht nur
 * „+ Quelle“. Zugangsdaten kommen nur beim Verbindungsaufbau über den Security-Port.
 *
 * <p>Herkunft: Das NATSPOD-/PAL-Protokoll unter {@code pal} ist das Modul {@code ndv} aus MainframeMate (nur
 * Paketname angepasst, Konsolausgaben durch {@code java.util.logging} ersetzt); {@code NdvClient},
 * {@code NdvObjectInfo} und die Objektauswahl stammen aus MainframeMate ({@code NdvClient}, {@code NdvService},
 * {@code NdvSourceScanner}).
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;
