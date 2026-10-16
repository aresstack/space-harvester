/**
 * Neutraler Secret-Port (Strang F, AP13).
 *
 * <p>{@link com.aresstack.enterpriseai.astu.security.SecretRef} (domain, loggbar) wird von Konfiguration und
 * Use Cases getragen; {@link com.aresstack.enterpriseai.adyton.SecretProvider} löst ihn in kurzlebiges
 * {@link com.aresstack.enterpriseai.adyton.SecretMaterial} auf, das nur Adapter sehen. Keine
 * KeePass-, HTTP- oder Swing-Typen in diesem Paket.
 */
package com.aresstack.enterpriseai.adyton;
