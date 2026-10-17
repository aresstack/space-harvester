package com.aresstack.spaceharvester.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ergebnis einer Sammellöschung.
 *
 * @param deleted Anzahl gelöschter Aufträge oder Ereignisse
 */
@Schema(description = "Ergebnis einer Sammellöschung")
public record DeletedResponse(int deleted) {
}
