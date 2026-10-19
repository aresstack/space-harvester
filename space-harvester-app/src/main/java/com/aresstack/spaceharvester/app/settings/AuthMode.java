package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;

/**
 * Eine Anmeldeart, die ein Quellentyp anbietet ({@code SourceType.authModes()} des Core, abgebildet in
 * {@link SourceFields}).
 */
public enum AuthMode {
    NONE(SourceSettingsConfig.AuthType.NONE),
    BASIC(SourceSettingsConfig.AuthType.BASIC),
    PAT(SourceSettingsConfig.AuthType.PAT);

    private final SourceSettingsConfig.AuthType authType;

    AuthMode(SourceSettingsConfig.AuthType authType) {
        this.authType = authType;
    }

    /** Der Wert unter {@code auth.type}. */
    public SourceSettingsConfig.AuthType authType() {
        return authType;
    }

    public static AuthMode of(SourceSettingsConfig.AuthType type) {
        return switch (type) {
            case NONE -> NONE;
            case BASIC -> BASIC;
            case PAT -> PAT;
        };
    }
}
