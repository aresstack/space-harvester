package com.aresstack.spaceharvester.app.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaSoundRecorderTest {
    @Test
    void downmixesStereoPcm16WithoutChangingFrameCount() {
        byte[] stereo = {
                0x10, 0x27, (byte) 0xf0, (byte) 0xd8,
                (byte) 0xe8, 0x03, (byte) 0xb8, 0x0b
        };
        assertArrayEquals(new byte[]{0, 0, (byte) 0xd0, 0x07},
                JavaSoundRecorder.downmixStereo(stereo, stereo.length));
    }

    @Test
    void rejectsIncompleteStereoFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> JavaSoundRecorder.downmixStereo(new byte[6], 6));
    }
}
