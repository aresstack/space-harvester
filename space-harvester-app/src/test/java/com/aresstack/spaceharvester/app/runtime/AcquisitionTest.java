package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AcquisitionTest {

    @Test
    void theStatusOfTheCoreIsTakenOverFieldByField() {
        Instant scan = Instant.parse("2026-10-18T20:00:00Z");
        AcquisitionStatus status = new AcquisitionStatus(true, AcquisitionStatus.Pause.COOLDOWN, 12, 3, 1, 1, 2, 40,
                5, 3, 2, scan, List.of(new AcquisitionStatus.SourceCounts("wiki", 10, 30, 5)));

        assertThat(Acquisition.of(status)).isEqualTo(new Acquisition(true, Acquisition.Pause.COOLDOWN, 12, 3, 1, 1, 2,
                40, 5, 3, 2, scan, List.of(new Acquisition.SourceCounts("wiki", 10, 30, 5))));
    }

    @Test
    void everyPauseOfTheCoreHasAWordInTheApp() {
        for (AcquisitionStatus.Pause pause : AcquisitionStatus.Pause.values()) {
            assertThat(Acquisition.Pause.valueOf(pause.name())).isNotNull();
        }
    }
}
