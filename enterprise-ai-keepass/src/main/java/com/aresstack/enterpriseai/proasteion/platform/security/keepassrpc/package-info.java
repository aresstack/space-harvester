/**
 * Adapter: KeePassRPC hinter dem Security-Port (Strang F, AP14).
 *
 * <p>Öffentlich sind nur {@link com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcSecretProvider}, seine
 * Konfiguration ({@link com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcConfig}), die zwei Naht-Interfaces
 * für die Composition Root ({@link com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback},
 * {@link com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingKeyStore}) und dessen Standard-Implementierung
 * {@link com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.InMemoryPairingKeyStore}. WebSocket, JSON und die KeePassRPC-Kryptografie bleiben paketintern.
 */
package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;
