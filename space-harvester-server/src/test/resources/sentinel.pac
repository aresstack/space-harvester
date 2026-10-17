// PAC für den Test des ausführbaren JARs (bootJarPacCheck, #34): shExpMatch braucht GraalJS mit Regex-Sprache.
function FindProxyForURL(url, host) {
  if (shExpMatch(host, "*.sentinel.test")) return "PROXY 192.0.2.123:18080";
  return "DIRECT";
}
