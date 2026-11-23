package com.aresstack.enterpriseai.proasteion.katagogion;

/**
 * Führt einen Tool-Aufruf aus. Implementierungen liefern Fehler als {@link McpToolResult#error(String)} und
 * werfen nicht über die MCP-Grenze. Eine Runtime fängt dennoch geworfene Exceptions ab und meldet dem Client
 * nur einen generischen Fehler, weil Exception-Meldungen Anfragedaten oder Infrastrukturdetails enthalten können.
 *
 * <p>Nach den Projektregeln rufen Handler ausschließlich Application-Use-Cases auf, nie Adapter.
 *
 * <p>Herkunft: askai-java8 {@code McpToolHandler}.
 */
public interface McpToolHandler {

    McpToolResult invoke(McpToolCall call);
}
