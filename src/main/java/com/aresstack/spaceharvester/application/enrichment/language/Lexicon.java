package com.aresstack.spaceharvester.application.enrichment.language;

import java.util.Set;

/**
 * Geschlossene Wortklassen des Deutschen, kleingeschrieben. Alles, was hier nicht steht, ordnet {@link GermanText}
 * nur nach Schreibung und Stellung ein. Eine Änderung ändert die Ergebnisse und verlangt darum eine neue Version der
 * Prozessoren.
 */
final class Lexicon {

    /** Artikel, Possessiva, Demonstrativa und Mengenwörter, die eine Nominalgruppe eröffnen. */
    static final Set<String> DETERMINERS = Set.of(
            "der", "die", "das", "den", "dem", "des",
            "ein", "eine", "einen", "einem", "einer", "eines",
            "kein", "keine", "keinen", "keinem", "keiner", "keines",
            "dieser", "diese", "dieses", "diesen", "diesem",
            "jener", "jene", "jenes", "jenen", "jenem",
            "jeder", "jede", "jedes", "jeden", "jedem",
            "welcher", "welche", "welches", "welchen", "welchem",
            "mein", "meine", "meinen", "meinem", "meiner", "meines",
            "dein", "deine", "deinen", "deinem", "deiner", "deines",
            "sein", "seine", "seinen", "seinem", "seiner", "seines",
            "ihr", "ihre", "ihren", "ihrem", "ihrer", "ihres",
            "unser", "unsere", "unseren", "unserem", "unserer", "unseres",
            "euer", "eure", "euren", "eurem", "eurer", "eures",
            "alle", "allen", "aller", "alles", "viele", "vielen", "vieler", "einige", "einigen", "einiger",
            "mehrere", "mehreren", "mehrerer", "weitere", "weiteren", "weiterer", "andere", "anderen", "anderer",
            "beide", "beiden", "beider", "folgende", "folgenden", "folgender", "sämtliche", "sämtlichen",
            "manche", "manchen", "mancher", "solche", "solchen", "solcher", "derselbe", "dieselbe", "dasselbe",
            "denselben", "demselben", "desselben", "derselben");

    /** Pronomen; ihr Bezug wird nie aufgelöst. */
    static final Set<String> PRONOUNS = Set.of(
            "ich", "du", "er", "sie", "es", "wir", "man", "sich", "mich", "dich", "mir", "dir", "ihn", "ihm", "uns",
            "euch", "ihnen", "was", "wer", "wen", "wem", "jemand", "niemand", "etwas", "nichts", "dies", "dessen",
            "deren", "denen", "jedermann");

    /** Präpositionen, auch mit Artikel verschmolzen. */
    static final Set<String> PREPOSITIONS = Set.of(
            "an", "am", "ans", "auf", "aufs", "aus", "bei", "beim", "bis", "durch", "durchs", "für", "fürs", "gegen",
            "hinter", "im", "in", "ins", "mit", "nach", "neben", "ohne", "seit", "über", "übers", "um", "ums",
            "unter", "vom", "von", "vor", "vors", "zu", "zum", "zur", "zwischen", "während", "wegen", "trotz", "per",
            "pro", "laut", "gemäß", "mittels", "via", "innerhalb", "außerhalb", "oberhalb", "unterhalb", "anhand",
            "aufgrund", "statt", "anstatt", "entlang", "gegenüber", "samt", "inklusive", "exklusive", "ab");

    /** Konjunktionen und Subjunktionen. */
    static final Set<String> CONJUNCTIONS = Set.of(
            "und", "oder", "aber", "sondern", "denn", "dass", "ob", "weil", "wenn", "als", "wie", "falls", "sobald",
            "damit", "sodass", "bevor", "nachdem", "obwohl", "sowie", "bzw", "beziehungsweise", "sofern", "soweit",
            "solange", "sowohl", "weder", "noch", "entweder", "wo", "wobei", "indem", "ehe", "seitdem");

    /** Formen von sein, haben und werden. */
    static final Set<String> AUXILIARIES = Set.of(
            "bin", "bist", "ist", "sind", "seid", "war", "warst", "waren", "wart", "gewesen", "sei", "seien", "wäre",
            "wären", "habe", "hast", "hat", "haben", "habt", "hatte", "hattest", "hatten", "hattet", "hätte",
            "hätten", "gehabt", "werde", "wirst", "wird", "werden", "werdet", "wurde", "wurdest", "wurden", "würde",
            "würden", "worden", "geworden", "bleibt", "bleiben", "blieb", "blieben");

    /** Formen von sein und bleiben, nach denen ein Adjektiv eine Eigenschaft aussagt. */
    static final Set<String> COPULAS = Set.of(
            "bin", "bist", "ist", "sind", "seid", "war", "warst", "waren", "wart", "sei", "seien", "wäre", "wären",
            "bleibt", "bleiben", "blieb", "blieben");

    /** Modalverben. */
    static final Set<String> MODALS = Set.of(
            "kann", "kannst", "können", "könnt", "konnte", "konnten", "könnte", "könnten", "muss", "musst",
            "müssen", "müsst", "musste", "mussten", "müsste", "müssten", "soll", "sollst", "sollen", "sollt",
            "sollte", "sollten", "darf", "darfst", "dürfen", "dürft", "durfte", "durften", "dürfte", "dürften",
            "will", "willst", "wollen", "wollt", "wollte", "wollten", "möchte", "möchten", "mag", "mögen");

    /** Verneinungen. */
    static final Set<String> NEGATIONS = Set.of("nicht", "nie", "niemals", "keinesfalls");

    /** Häufige Adverbien und Partikeln, die zwischen Kopula und Eigenschaft stehen oder sonst nichts benennen. */
    static final Set<String> ADVERBS = Set.of(
            "auch", "nur", "noch", "schon", "bereits", "dann", "danach", "anschließend", "zuerst", "zunächst",
            "erst", "sehr", "immer", "stets", "oft", "häufig", "selten", "hier", "dort", "jetzt", "heute", "morgen",
            "gestern", "so", "dabei", "dazu", "dafür", "dadurch", "daher", "deshalb", "deswegen", "außerdem",
            "zudem", "ebenfalls", "ebenso", "wieder", "bitte", "mehr", "weniger", "etwa", "gegebenenfalls", "ggf",
            "jedoch", "allerdings", "also", "nun", "mindestens", "höchstens", "ca", "usw", "etc", "eventuell",
            "evtl", "vielleicht", "wahrscheinlich", "möglichst", "ganz", "gar", "zu", "überhaupt", "eher",
            "bisher", "inzwischen", "weiterhin", "ausschließlich", "lediglich", "vollständig", "teilweise",
            "grundsätzlich", "normalerweise", "standardmäßig", "derzeit", "aktuell", "momentan", "dennoch",
            "trotzdem", "sonst", "z.b", "d.h", "u.a", "bspw", "beispielsweise", "insbesondere", "vor", "allem");

    /** Abkürzungen, nach deren Punkt kein Satz endet. */
    static final Set<String> ABBREVIATIONS = Set.of(
            "z.b", "d.h", "u.a", "o.ä", "u.ä", "v.a", "u.u", "i.d.r", "z.t", "bzw", "ca", "ggf", "evtl", "inkl",
            "exkl", "usw", "etc", "vgl", "nr", "dr", "hr", "fr", "bspw", "sog", "max", "min", "mind", "zzgl", "abs",
            "ff", "e.g", "i.e", "vs", "st", "str", "tel", "jan", "feb", "mär", "apr", "jun", "jul", "aug", "sep",
            "sept", "okt", "nov", "dez", "ggü", "zb", "uvm", "allg", "bzgl", "gem", "lt", "prof", "ing");

    /** Häufige Wörter deutscher Sätze, um fremdsprachige Sätze zu erkennen. */
    static final Set<String> GERMAN_MARKERS = Set.of(
            "der", "die", "das", "und", "ist", "nicht", "mit", "für", "von", "zu", "den", "dem", "ein", "eine",
            "wird", "werden", "auf", "im", "des", "sich", "auch", "bei", "sind", "oder", "kann", "muss", "wenn",
            "dass", "über", "nach", "aus", "zum", "zur", "wurde", "sie", "es", "einen", "einem", "einer");

    /** Häufige Wörter englischer Sätze. */
    static final Set<String> ENGLISH_MARKERS = Set.of(
            "the", "and", "is", "are", "of", "to", "for", "with", "this", "that", "be", "by", "on", "it", "was",
            "were", "will", "can", "must", "should", "from", "or", "an", "not", "you", "we", "they", "has", "have",
            "which", "these", "those", "if", "when");

    /** Abtrennbare Verbpartikeln, z. B. {@code an} in „startet … an“. */
    static final Set<String> VERB_PARTICLES = Set.of(
            "ab", "an", "auf", "aus", "bei", "ein", "fest", "her", "hin", "los", "mit", "nach", "vor", "weg", "weiter",
            "zu", "zurück", "zusammen", "um", "durch", "über", "frei", "hoch", "herunter", "hinzu", "heraus",
            "voran", "vorbei", "wieder", "fort", "dar", "statt", "teil");

    private Lexicon() {
    }

    /** Ein Wort einer geschlossenen Klasse (Artikel, Pronomen, Präposition, …). */
    static boolean isFunctionWord(String lower) {
        return DETERMINERS.contains(lower) || PRONOUNS.contains(lower) || PREPOSITIONS.contains(lower)
                || CONJUNCTIONS.contains(lower) || AUXILIARIES.contains(lower) || MODALS.contains(lower)
                || NEGATIONS.contains(lower) || ADVERBS.contains(lower);
    }
}
