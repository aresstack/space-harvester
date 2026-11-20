package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.Describable;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Ein Bild der Lese-Ansicht einer Seite, beschrieben mit dem Standard-Prompt. Übersprungen werden Vorschaubilder von
 * draw.io-Diagrammen (die textuelle Quelle gilt, Angelo 16.10.) und SVG.
 *
 * @param attachments die Anhänge der Seite, nur für die Erkennung der Diagrammvorschau und erst bei Bedarf geladen
 */
record ViewImageDescribable(Assets assets, PageKey page, String pageVersion, HarvestSource.ViewImage image,
                            Supplier<List<PageAttachment>> attachments) implements Describable {

    @Override
    public String id() {
        return image.attachmentId();
    }

    @Override
    public String version() {
        return image.version();
    }

    @Override
    public String title() {
        return image.title();
    }

    @Override
    public String filename() {
        return Assets.viewFileName(image);
    }

    @Override
    public String mediaType() {
        return image.mediaType();
    }

    @Override
    public String skipReason() {
        String type = image.mediaType() == null ? "" : image.mediaType().toLowerCase(Locale.ROOT);
        if (filename().toLowerCase(Locale.ROOT).endsWith(".png") && Assets.diagramPreview(attachments.get(), image)) {
            return "diagram-source";
        }
        if (type.startsWith("image/svg") || filename().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            return "vector-source";
        }
        return null;
    }

    @Override
    public Assets.AssetContent content() {
        return assets.viewImage(page.sourceId(), page.pageId(), pageVersion, image);
    }
}
