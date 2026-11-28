package com.aresstack.spaceharvester.app.ui.theme;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Farben der App-Flächen im Vorgabe-Theme {@code classic}: jeder Eintrag ist der Wert, den die Stelle vor der
 * Theme-Mechanik fest eingebaut hatte (ARGB). Schlüssel: {@code app.<Klasse>.<Rolle>}; der Name sagt, welche Fläche gemeint
 * ist (z. B. {@code shadow}, {@code nebulaBlue}), nie die Methode der Stelle und nie eine Laufnummer. Weitere Themes sind
 * reine Farbtabellen mit denselben Schlüsseln und werden über
 * {@code AskAiTheme.registerTheme} angemeldet; fehlt ein Schlüssel, gilt der Wert von {@code classic}.
 */
final class AppColorTokens {

    private AppColorTokens() {
    }

    /** Alle App-Token des Vorgabe-Themes in Tabellenreihenfolge. */
    static Map<String, Color> classic() {
        Map<String, Color> t = new LinkedHashMap<String, Color>();
        put(t, "app.MermaidDiagramPanel.overviewEdge", 0xB4A0A0A0L);
        put(t, "app.MermaidDiagramPanel.overviewBorder", 0xFFD2D2D2L);
        put(t, "app.MermaidDiagramPanel.overviewPaper", 0xFFFCFCFCL);
        put(t, "app.MermaidDiagramPanel.nodeOlive", 0xFF6B8E23L);
        put(t, "app.MermaidDiagramPanel.nodePink", 0xFFFF69B4L);
        put(t, "app.MermaidDiagramPanel.nodeTeal", 0xFF008B8BL);
        put(t, "app.MermaidDiagramPanel.nodeRed", 0xFFDC143CL);
        put(t, "app.MermaidDiagramPanel.nodePurple", 0xFF9370DBL);
        put(t, "app.MermaidDiagramPanel.nodeOrange", 0xFFFFA500L);
        put(t, "app.MermaidDiagramPanel.nodeGreen", 0xFF3CB371L);
        put(t, "app.MermaidDiagramPanel.nodeBlue", 0xFF6495EDL);
        put(t, "app.MermaidDiagramPanel.tileLoadingText", 0xFF969696L);
        put(t, "app.MermaidDiagramPanel.tileGrid", 0xFFDCDCDCL);
        put(t, "app.MermaidDiagramPanel.tilePlaceholder", 0xFFF5F5F5L);
        put(t, "app.MermaidDiagramPanel.dragLineFree", 0xB40064FFL);
        put(t, "app.MermaidDiagramPanel.dragLineEdge", 0xC8FF8C00L);
        put(t, "app.MermaidDiagramPanel.searchCurrentEdge", 0xC8FFB400L);
        put(t, "app.MermaidDiagramPanel.searchCurrent", 0x64FFFF00L);
        put(t, "app.MermaidDiagramPanel.searchHit", 0x32FFDC64L);
        put(t, "app.MermaidDiagramPanel.mutedText", 0xFF808080L);
        put(t, "app.MermaidDiagramPanel.editBackground", 0xFFEBFFEBL);
        put(t, "app.MermaidDiagramPanel.detailBackground", 0xFFFFFFE6L);
        put(t, "app.FilesIcons.kindArchive", 0xFF8D6E63L);
        put(t, "app.FilesIcons.kindImage", 0xFF8E44ADL);
        put(t, "app.FilesIcons.kindMail", 0xFF7B5EA7L);
        put(t, "app.FilesIcons.kindSlides", 0xFFD24726L);
        put(t, "app.FilesIcons.kindTable", 0xFF217346L);
        put(t, "app.FilesIcons.kindWord", 0xFF2B579AL);
        put(t, "app.FilesIcons.kindPdf", 0xFFC62828L);
        put(t, "app.FilesIcons.kindCode", 0xFF2F6DB5L);
        put(t, "app.FilesIcons.kindMainframe", 0xFF1F7A8CL);
        put(t, "app.MermaidViewerPanel.buttonRing", 0xFF252525L);
        put(t, "app.MermaidViewerPanel.busyText", 0xC8464646L);
        put(t, "app.MermaidViewerDialog.canvasDark", 0xFF1E1E1EL);
        put(t, "app.CosmicClockPanel.horizon", 0xFF080C18L);
        put(t, "app.Starfield.captionMuted", 0xFFC9D4F2L);
        put(t, "app.Starfield.captionInk", 0xFFFFFFFFL);
        put(t, "app.Starfield.captionShadow", 0x8C000000L);
        put(t, "app.Starfield.lampBlue", 0xFF5FA8FFL);
        put(t, "app.Starfield.starBlue", 0xFFA9C6FFL);
        put(t, "app.Starfield.nebulaViolet", 0x165A328CL);
        put(t, "app.Starfield.nebulaBlue", 0x3C465AAAL);
        put(t, "app.Starfield.starWhite", 0xFFFFFFFFL);
        put(t, "app.ShipArchive.dimVeil", 0x6E040812L);
        put(t, "app.ShipArchive.windowLight", 0xFFFFB45FL);
        put(t, "app.ShipArchive.hullBottom", 0xFF0E1F33L);
        put(t, "app.ShipArchive.hullTop", 0xFF1B3A55L);
        put(t, "app.ReadAloudDial.faceFill", 0xEBFFFFFFL);
        put(t, "app.PerspectiveSwitcher.plateBottom", 0xFF6E4A2AL);
        put(t, "app.PerspectiveSwitcher.plateTop", 0xFFB07A45L);
        put(t, "app.InfoPopup.shadow", 0x0A000000L);
        put(t, "app.FunctionKeyBar.neutralKey", 0xFFC8C8C8L);
        put(t, "app.FunctionKeyBar.blueTarget", 0xFF3C50B4L);
        put(t, "app.FunctionKeyBar.redTarget", 0xFFB43232L);
        put(t, "app.FunctionKeyBar.barBackground", 0xFFF0F0F0L);
        put(t, "app.VideoViewerPanel.audioTitle", 0xFFD6E8F6L);
        put(t, "app.VideoViewerPanel.messageText", 0xFFE6EDF4L);
        put(t, "app.VideoViewerPanel.stage", 0xFF171E27L);
        put(t, "app.VideoSeekBar.knobEdge", 0xFF15212AL);
        put(t, "app.VideoSeekBar.knobRing", 0xFF748494L);
        put(t, "app.VideoSkipIcon.disabledGlyph", 0xFF73818CL);
        put(t, "app.VideoSkipIcon.glyph", 0xFFE5F2FAL);
        put(t, "app.VideoFullScreenIcon.disabledGlyph", 0xFF73818CL);
        put(t, "app.VideoFullScreenIcon.glyph", 0xFFE5F2FAL);
        put(t, "app.VideoAudioModeIcon.idleGlyph", 0xFFE7EEF6L);
        put(t, "app.VideoAudioModeIcon.activeGlyph", 0xFF83D4F5L);
        put(t, "app.FileFormatIcon.disabledSheet", 0xFF94A3B8L);
        put(t, "app.AttachmentViewerPanel.busyText", 0xD2FFFFFFL);
        put(t, "app.ReadingPane.placeholderBorder", 0xFFD8D8D8L);
        put(t, "app.ReadingPane.placeholderFill", 0xFFEEEEEEL);
        put(t, "app.SourcesView.chosenBorder", 0xFFB5892DL);
        put(t, "app.Comic.tableGrid", 0xFFE6E8ECL);
        put(t, "app.MermaidViewerPanel.dimVeil", 0xFF000000L);
        put(t, "app.MermaidViewerPanel.paper", 0xFFFFFFFFL);
        put(t, "app.MermaidViewerDialog.dimVeil", 0xFF000000L);
        put(t, "app.MarkdownTheme.fallbackInk", 0xFF000000L);
        put(t, "app.MarkdownTheme.fallbackText", 0xFF404040L);
        put(t, "app.TiledExporter.paper", 0xFFFFFFFFL);
        put(t, "app.MermaidExportDialog.progressText", 0xFF808080L);
        put(t, "app.CosmicClockPanel.space", 0xFF000000L);
        put(t, "app.PictureStage.paper", 0xFFFFFFFFL);
        put(t, "app.ImageViewer.paper", 0xFFFFFFFFL);
        put(t, "app.FunctionKeyBar.darkInk", 0xFF000000L);
        put(t, "app.VideoFullScreenController.stage", 0xFF000000L);
        put(t, "app.PresentationPages.paper", 0xFFFFFFFFL);
        put(t, "app.FileFormatIcon.paper", 0xFFFFFFFFL);
        put(t, "app.AttachmentViewerPanel.dimVeil", 0xFF000000L);
        put(t, "app.QrCodesView.quietZone", 0xFFFFFFFFL);
        put(t, "app.Comic.notice", 0xFFFFF6D5L);
        put(t, "app.Comic.changeAdded", 0xFFDFF5E1L);
        put(t, "app.Comic.changeChanged", 0xFFDCEBFBL);
        put(t, "app.Comic.changeRemoved", 0xFFFADBD8L);
        put(t, "app.Comic.podcastGap", 0xFFE6E8EBL);
        put(t, "app.Comic.standingCurrent", 0xFF2E7D4FL);
        put(t, "app.Comic.standingPlanned", 0xFF2F6DB5L);
        put(t, "app.Comic.standingUnclear", 0xFFA86A12L);
        put(t, "app.Comic.standingOutdated", 0xFF8A8F98L);
        put(t, "app.Glyph.favorite", 0xFFFFC800L);
        put(t, "app.Glyph.favoriteEdge", 0xFFC99A00L);
        put(t, "app.ReaderFind.hit", 0xFFFFF59DL);
        put(t, "app.ReaderFind.currentHit", 0xFFFFB74DL);
        put(t, "app.RoundHover.fill", 0xAAFFFFFFL);
        put(t, "app.TermDirectory.rejectedBackground", 0xFFECEDEFL);
        put(t, "app.ArtifactView.changed", 0xFFF2C94CL);
        put(t, "app.PagePodcastPictures.mark", 0xFFFFF19EL);
        put(t, "app.ReaderDocument.unknownMark", 0x3C9E9E9EL);
        put(t, "app.ReadingPane.marker", 0xFFC0392BL);
        put(t, "app.StatementsTab.modelBanner", 0xFFFFF7E6L);
        put(t, "app.SummaryReport.podcastMark", 0xFFFFF59DL);
        put(t, "app.AttachmentViewerPanel.canvas", 0xFF1E1E1EL);
        put(t, "app.AttachmentViewerPanel.viewerButton", 0xDCFFFFFFL);
        put(t, "app.FileFormatIcon.sheet", 0xFFF8FAFCL);
        put(t, "app.FileFormatIcon.fold", 0xFFCBD5E1L);
        put(t, "app.FileFormatIcon.disabled", 0xFF64748BL);
        put(t, "app.FileFormatIcon.powerPoint", 0xFFD24726L);
        put(t, "app.FileFormatIcon.pdf", 0xFFE02424L);
        put(t, "app.Passages.mark", 0x6EFFAA00L);
        put(t, "app.VideoSeekBar.track", 0xFF303C4BL);
        put(t, "app.VideoSeekBar.played", 0xFF68BEDDL);
        put(t, "app.VideoSeekBar.label", 0xFFC9D6E1L);
        put(t, "app.VideoSeekBar.slide", 0xFFF4D35EL);
        put(t, "app.VideoSeekBar.lightTrack", 0xFFD5DCE4L);
        put(t, "app.VideoSeekBar.lightLabel", 0xFF5B6876L);
        put(t, "app.VideoSeekBar.lightSlide", 0xFFE0A800L);
        put(t, "app.VideoTransportBar.background", 0xFF171E27L);
        put(t, "app.VideoTransportBar.button", 0xFF253241L);
        put(t, "app.VideoTransportBar.foreground", 0xFFF1F5F9L);
        put(t, "app.VideoTransportBar.iconButton", 0xFF788796L);
        put(t, "app.BackButton.bottom", 0xFFDCE1E8L);
        put(t, "app.BackButton.shadowColor", 0x2E000000L);
        put(t, "app.BackButton.disabledFill", 0xFFF1F3F6L);
        put(t, "app.BusyOverlay.stopMuted", 0x9680848CL);
        put(t, "app.BusyOverlay.stopLit", 0xFFE02020L);
        put(t, "app.BusyOverlay.highlight", 0xFF3C8CE6L);
        put(t, "app.BusyOverlay.veilColor", 0xFFC0C0C0L);
        put(t, "app.BusyOverlay.ink", 0xFF000000L);
        put(t, "app.CenterOffer.rest", 0xFFECEDEFL);
        put(t, "app.CenterOffer.hover", 0xFFDDDFE3L);
        put(t, "app.FunctionKeyBar.yellow", 0xFFFFE650L);
        put(t, "app.FunctionKeyBar.orange", 0xFFFFA532L);
        put(t, "app.FunctionKeyBar.red", 0xFFDC463CL);
        put(t, "app.FunctionKeyBar.green", 0xFF5AC85AL);
        put(t, "app.FunctionKeyBar.teal", 0xFF3CBEBEL);
        put(t, "app.FunctionKeyBar.blue", 0xFF5078D2L);
        put(t, "app.FunctionKeyBar.keyCap", 0xFFA0A0A0L);
        put(t, "app.InputFrame.icon", 0xFF666666L);
        put(t, "app.MarkedTextRenderer.fill", 0xFFEEF1F5L);
        put(t, "app.PodcastOrb.failed", 0xFFF2B8B5L);
        put(t, "app.PodcastOrb.failedEdge", 0xFFD9534FL);
        put(t, "app.PodcastOrb.flash", 0xFFB3261EL);
        put(t, "app.SantaHat.fur", 0xFFFFFFFFL);
        put(t, "app.SearchModeIcon.icon", 0xFF666666L);
        put(t, "app.SearchModeIcon.letter", 0xFF777777L);
        put(t, "app.SelectionGlow.sheen", 0xFFFFFFFFL);
        put(t, "app.ShipArchive.glow", 0xFF5FD4FFL);
        put(t, "app.ShipArchive.text", 0xFFD8E6F5L);
        put(t, "app.ShipArchive.dim", 0xFF7F93ADL);
        put(t, "app.Snow.mound", 0xFFFFFFFFL);
        put(t, "app.Starfield.spaceTop", 0xFF060A18L);
        put(t, "app.Starfield.spaceBottom", 0xFF141C3AL);
        put(t, "app.Starfield.hull", 0xFF1B2233L);
        put(t, "app.FilesIcons.folderFill", 0xFFFFE08AL);
        put(t, "app.FilesIcons.folderEdge", 0xFFC99A00L);
        put(t, "app.FilesIcons.paperEdge", 0xFF8A8F98L);
        put(t, "app.FilesIcons.unsaved", 0xFFE08A00L);
        put(t, "app.DocumentEditor.findMark", 0xFFFFE066L);
        put(t, "app.DocumentEditor.findCurrent", 0xFFFFA94DL);
        put(t, "app.RelationsPanel.natural", 0xFF228B22L);
        put(t, "app.RelationsPanel.systemFunction", 0xFF0064C8L);
        put(t, "app.ConstellationCatalog.rose", 0x64FFA0B4L);
        put(t, "app.ConstellationCatalog.turquoise", 0x6464E6DCL);
        put(t, "app.ConstellationCatalog.lightYellow", 0x64FFFFA0L);
        put(t, "app.ConstellationCatalog.lightOrange", 0x64FFC882L);
        put(t, "app.ConstellationCatalog.lightBlue", 0x648CB4FFL);
        put(t, "app.ConstellationCatalog.lightGreen", 0x64A0FFB4L);
        put(t, "app.ConstellationCatalog.lavender", 0x64C8AAFFL);
        put(t, "app.ConstellationCatalog.coral", 0x64FF968CL);
        put(t, "app.ConstellationCatalog.mint", 0x6496FFD2L);
        put(t, "app.ConstellationCatalog.peach", 0x64FFD2AAL);
        put(t, "app.CosmicClockPanel.horizonGrad", 0xFF03050CL);
        put(t, "app.MermaidViewerDialog.overlayButton", 0xD2FFFFFFL);
        put(t, "app.MermaidViewerPanel.viewerButton", 0xD2FFFFFFL);
        return Collections.unmodifiableMap(t);
    }

    private static void put(Map<String, Color> table, String key, long argb) {
        table.put(key, new Color((int) argb, true));
    }
}
