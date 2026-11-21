package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.VideoSettings;
import com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase;
import com.aresstack.spaceharvester.app.transcription.TranscriptSegment;
import com.aresstack.spaceharvester.app.transcription.TranscriptionStatusListener;
import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.presentation.TranscriptSections;
import com.aresstack.spaceharvester.application.presentation.VideoMedia;
import com.aresstack.spaceharvester.application.sources.MediaByteRange;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Whisper und Standbilder eines geöffneten MP4-Videos für die Aufträge von „In PPTX umwandeln“: dieselben
 * Bereichsabrufe, dieselbe Transkription und dieselben Standbilder wie Player, Reiter „Text“ und „Anhänge“. Läuft im
 * Auftrag, nie auf dem EDT.
 */
public final class VideoPresentationMedia implements VideoMedia {

    /** Endet das Transkript höchstens so kurz vor dem Ende des Videos, gilt es als vollständig. */
    static final long COMPLETE_TOLERANCE_MILLIS = 2_000;

    private final MediaByteRange remote;
    private final long size;
    private final String mediaType;
    private final VideoSettings settings;
    private final TranscribeAudioUseCase transcription;
    /** Das Whisper-Modell dieser Transkription (Angelo 20.11.2026: je Modell ein Original). */
    private final com.aresstack.spaceharvester.app.transcription.WhisperModel whisper;

    /** Die Länge der Videodatei laut Quelle ({@code dav.contentLength}); {@code -1} ohne brauchbare Angabe. */
    public static long size(com.aresstack.spaceharvester.application.sources.PageMetadata metadata) {
        try {
            return Long.parseLong(metadata.attributes().getOrDefault("dav.contentLength", "-1"));
        } catch (NumberFormatException unavailable) {
            return -1;
        }
    }

    /**
     * Der Inhaltstyp des Videos: aus der Quelle (auch für Titel ohne Endung, etwa bei BSCW), sonst aus dem
     * Dateinamen.
     */
    public static String mediaType(com.aresstack.spaceharvester.application.sources.PageMetadata metadata) {
        String reported = metadata.attributes().get(
                com.aresstack.spaceharvester.application.sources.PageMetadata.REMOTE_MEDIA_TYPE);
        if (reported != null) {
            return reported;
        }
        String file = metadata.title().toLowerCase(java.util.Locale.ROOT);
        return file.endsWith(".webm") ? "video/webm"
                : file.endsWith(".mov") ? "video/quicktime"
                : file.endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
    }

    /**
     * @param remote    Bereichsabrufe des Videos
     * @param size      Länge der Datei in Bytes
     * @param mediaType Inhaltstyp, etwa {@code video/mp4}
     */
    public VideoPresentationMedia(MediaByteRange remote, long size, String mediaType, VideoSettings settings,
                                  TranscribeAudioUseCase transcription) {
        this(remote, size, mediaType, settings, transcription, WhisperModelLocation.selected());
    }

    /**
     * @param whisper das Whisper-Modell für ein neues Transkript, etwa das in der Transkriptansicht gewählte
     */
    public VideoPresentationMedia(MediaByteRange remote, long size, String mediaType, VideoSettings settings,
                                  TranscribeAudioUseCase transcription,
                                  com.aresstack.spaceharvester.app.transcription.WhisperModel whisper) {
        this.remote = Objects.requireNonNull(remote, "remote");
        if (size < 1) {
            throw new IllegalArgumentException("Video ohne Länge");
        }
        this.size = size;
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transcription = Objects.requireNonNull(transcription, "transcription");
        this.whisper = whisper == null ? WhisperModelLocation.selected() : whisper;
    }

    @Override
    public String whisper() {
        return whisper.id();
    }

    /** Mit dem Modell eines vorhandenen Originals; ein unbekanntes Modell lässt das eigene stehen. */
    @Override
    public VideoMedia withWhisper(String id) {
        com.aresstack.spaceharvester.app.transcription.WhisperModel model =
                com.aresstack.spaceharvester.app.transcription.WhisperModel.of(id);
        return model == null || model == whisper ? this
                : new VideoPresentationMedia(remote, size, mediaType, settings, transcription, model);
    }

    /** Die Folienwechsel aus dem MP4-Index, wie früher der Player beim Öffnen; andere Formate haben keine. */
    @Override
    public List<Long> slideChanges() throws Exception {
        boolean mp4 = "video/mp4".equalsIgnoreCase(mediaType);
        if (!mp4 || size < 16) {
            return List.of();
        }
        return SlideChanges.load(remote::read, size, settings);
    }

    @Override
    public String transcribe(String existing, Consumer<String> partial, Progress progress, BooleanSupplier cancelled)
            throws Exception {
        // Das vorhandene Transkript bleibt wörtlich stehen; nur ein letzter Abschnitt ohne Ende (ältere Transkripte)
        // wird von seinem Anfang an neu erkannt. Neues kommt dahinter (Angelo 20.11.2026: wiederverwenden, nur
        // vervollständigen).
        String kept = TranscriptSections.completedPart(existing);
        StringBuilder text = new StringBuilder(kept);
        // Vom ganzen Transkript bestimmt: Ohne Ende reicht ein Abschnitt bis zum Anfang des nächsten, der eben
        // weggefallene letzte gibt also die Stelle an, ab der neu erkannt wird.
        long resume = TranscriptSections.reach(existing);
        long duration = SlideChanges.durationMillis(remote::read, size);
        if (duration > 0) {
            progress.duration(duration);
        }
        progress.reached(resume);
        // Vollständig, wenn der fertige Teil (fast) bis zum Ende des Videos reicht; sonst wird der Rest erkannt, auch
        // ein letztes, unvollendetes Fenster.
        if (!kept.isEmpty() && kept.strip().equals(existing.strip()) && duration > 0
                && resume >= duration - COMPLETE_TOLERANCE_MILLIS) {
            return existing;
        }
        try (VideoTranscriptSource source = new VideoTranscriptSource(size, remote, settings)) {
            VideoTranscriptSource.AudioOnly audio = source.openAudioOnly(resume);
            if (cancelled.getAsBoolean()) {
                return existing == null ? text.toString() : existing;
            }
            transcription.execute(audio.address(), audio.startMillis(), Math.max(resume, audio.startMillis()),
                    WhisperModelLocation.find(whisper).orElseThrow(() ->
                            new com.aresstack.spaceharvester.app.transcription.WhisperModelMissingException(whisper)),
                    cancelled, segment -> {
                        synchronized (text) {
                            text.append(line(segment));
                            partial.accept(text.toString());
                        }
                        progress.reached(segment.endMillis());
                    }, new TranscriptionStatusListener() {
                        @Override
                        public void onDecodedSeconds(long seconds) {
                        }

                        @Override
                        public void onRecognitionStarted() {
                        }
                    });
        }
        synchronized (text) {
            return text.toString();
        }
    }

    /** Die Zeile eines Abschnitts wie im Reiter „Text“. */
    static String line(TranscriptSegment segment) {
        return "[" + segment.rangeLabel() + "] " + segment.text() + "\n\n";
    }

    @Override
    public void frames(List<Long> millis, BiConsumer<Long, byte[]> found, BooleanSupplier cancelled)
            throws Exception {
        if (millis.isEmpty()) {
            return;
        }
        try (VideoFrameGrabber.Session session = VideoFrameGrabber.Session.open(remote::read, size, mediaType,
                cancelled, settings)) {
            for (long at : millis) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                Assets.VideoFrame frame = session.frame(at);
                if (frame != null) {
                    found.accept(at, frame.png());
                }
            }
        } catch (java.util.concurrent.CancellationException stopped) {
            // Abgebrochen oder angehalten: gezogene Standbilder bleiben abgelegt.
        }
    }
}
