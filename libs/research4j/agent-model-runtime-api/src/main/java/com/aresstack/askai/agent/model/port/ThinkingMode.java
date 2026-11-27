package com.aresstack.askai.agent.model.port;

/**
 * Whether and how strongly a thinking-capable model reasons before answering. The implementation maps the
 * value onto the provider's wire form verbatim (for Ollama: {@code think} as {@code false}, {@code true}
 * or the level string), and omits it for {@link #DEFAULT}. Models without a thinking mode ignore it;
 * models that only know on/off treat the levels as on.
 */
public enum ThinkingMode {
    /**
     * The caller leaves it to the platform: the slot's setting applies, and the slot's default Auto resolves
     * per model (off for a thinking model that accepts it, low for a level-only model, no field otherwise).
     */
    DEFAULT,
    OFF,
    ON,
    LOW,
    MEDIUM,
    HIGH
}
