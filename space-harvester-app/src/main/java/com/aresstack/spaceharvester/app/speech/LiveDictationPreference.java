package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.ui.WindowSizes;

/** Persist the optional local dictation experiment in the application state. */
public final class LiveDictationPreference {
    private static final String KEY = "speech.experimental-live-preview";

    private LiveDictationPreference() { }

    public static boolean enabled() {
        return WindowSizes.recallInt(KEY, 0) == 1;
    }

    public static void setEnabled(boolean enabled) {
        WindowSizes.rememberInt(KEY, enabled ? 1 : 0);
    }
}
