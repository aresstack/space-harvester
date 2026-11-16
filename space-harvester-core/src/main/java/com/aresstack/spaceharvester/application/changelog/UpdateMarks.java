package com.aresstack.spaceharvester.application.changelog;

import java.util.List;

/** Ablage der Update-Marken (ADR 0030), im Cache neben dem Änderungsprotokoll. */
public interface UpdateMarks {

    /** Die Marken einer Quelle, älteste zuerst. */
    List<UpdateMark> marks(String sourceId);

    /** Legt eine Marke ab. */
    void mark(UpdateMark mark);
}
