# Chat dictation: audio DSP and bounded microphone capture

The Chat tab uses the local Whisper model chosen under Einstellungen › Modelle (default Tiny).
The model is not bundled; it is downloaded once from Hugging Face after the user confirms
(see DESKTOP_APP.md, "Whisper-Modell"). The recorder and inference pipeline run on
the client, without sending audio to an external service.

## Signal path

1. Capture signed **16-bit little-endian PCM** through Java Sound. Negotiate
   16, 32, 44.1, or 48 kHz mono; accept stereo at 44.1/48 kHz when necessary,
   downmixing to mono **during capture**.
2. Accumulate raw mono PCM in **two-minute chunks**, without a recording-time
   limit. Keep the completed previous chunk and the current filling chunk in RAM.
   **Only when the next complete two-minute chunk fills**, spill the older full
   chunk into a temporary `.pcm` file.
3. On Stop, seal the recording and expose a `WhisperAudioSource` that reads
   independent 16-kHz mono float windows. Resample each window with the published
   `WhisperAudioResampler`; do not load the entire recording.
4. `AskAiWhisperPipeline` from
   `com.aresstack:directml-whisper-audio:0.3.1` applies the published
   Ask AI PCM16 processing and deterministic Java VAD. Its default profile uses
   DC removal, an **80 Hz Butterworth high-pass**, a 7.2 kHz low-pass,
   noise gate, compressor and limiter. VAD skips long speech pauses before
   Whisper decodes the remaining speech.
5. Insert recognized text at the caret in the chat editor. Do not send the
   message automatically. Keep the previous microphone meter, cancel/retry
   buttons, and transcript insertion semantics.

## Memory and temporary files

The recorder retains at most one complete previous two-minute PCM chunk and
one current chunk in RAM. Its PCM storage is therefore bounded independently
of the total recording length. Temporary files belong to one recording and
are deleted when the transcript succeeds, the user discards recording/retry,
or an obsolete recognition result finishes. When recognition fails, retain
the sealed recording for the existing **Erneut erkennen** action; delete it
after success or discard.

Capture bit depth (**16 bit**) and target sample rate (**16 kHz**) are
different properties. The original device sample rate is preserved in the
chunk store; conversion to Whisper's required rate happens only when needed.
Whisper and the VAD execute sequentially on the existing background worker.
There is no Silero, Python, or ONNX Runtime dependency.

The Chat tab depends on `directml-inference:0.3.1` and
`directml-whisper-audio:0.3.1`; the latter brings `audio-dsp:0.3.1`
transitively.

Source of the delayed chunk algorithm:
`aresstack/win-directml-java`,
`directml-workbench/.../whisper/ChunkedPcmAudioSource.java`
(reused as an application-private class).


## Experimental live preview (opt-in)

Enable **Einstellungen › Modelle › Spracheingabe › Live-Transkription (experimentell)**.
The local application-state preference defaults to off and is sampled at recording start.
The existing incremental recognition of completed 30-second windows remains the default.
When enabled, the same background worker periodically freezes a short rolling audio window
(4–12 seconds, at least 2 seconds of new audio). The normal guarded Whisper path handles
short final windows using the model's padded encoder input. This preview is replaceable,
is never appended to the editor and is cleared at stop, cancellation or failure.
The final transcript is inserted only once, using the complete incremental result or the
ordinary full-recording fallback. Preview failures disable the experiment for that recording.
Actual latency and accuracy depend on model, CPU and speech; this is not token-by-token streaming.
