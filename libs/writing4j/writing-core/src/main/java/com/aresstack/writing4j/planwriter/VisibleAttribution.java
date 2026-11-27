package com.aresstack.writing4j.planwriter;

import com.aresstack.knowledge4j.source.domain.DomainIdentity;
import com.aresstack.knowledge4j.source.domain.HostKind;
import com.aresstack.knowledge4j.source.domain.PublicSuffixDomainKeyResolver;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a written sentence names who speaks for a cited passage: "nach Angaben des Herstellers", "laut einem
 * Anbieter", "dem Verband zufolge", "so der Hersteller", "wie ein Anbieter angibt", "der Verband betont, dass", or the
 * source's own name in such a frame. The sentence check (component 11) asks the model whether the claim of a maker, a
 * seller or an association is stated as a fact; in c10 it said so also for sentences that open with "laut Angaben des
 * Herstellers". A claim whose part of the sentence names its speaker is not stated as a fact, whatever the model said.
 * <p>
 * The rule only clears a claim; it never makes one a fact. So it errs on one side: a frame it misses costs a mark or an
 * inserted speaker, a frame it wrongly accepts hides a claim stated as a fact (the review of 22.11.2026: "empfiehlt dem
 * Händler", "Viele Händler schätzen die Kamera", "laut und spart Unternehmen" all counted). Every frame is therefore a
 * reporting construction in which the speaker is the source or the subject: laut, gemäß, nach Angaben, zufolge, aus
 * Sicht, heißt es bei; a tag (", so der Hersteller", ", wie der Verband betont"); or a speaker as the subject of a verb
 * of saying that opens what it says (", dass", ":", a comma), of a recommendation ("der Verband empfiehlt …"), or of
 * the separable "gibt … an". The speaker must be of the source's kind: a maker or seller for {@code HERSTELLER_HANDEL},
 * an association for {@code VERBAND} (the industry and its lobby count for both). A speaker word counts as a whole word
 * only (compounds count: "Softwareanbieter"), German ones as nouns; the site of the source counts by its name.
 * <p>
 * The part of the sentence that carries a passage runs from the marker before (or the sentence's start) to the marker
 * that cites it, and only after the last ";" in between; a tag right after a marker (", so der Hersteller,") belongs to
 * that marker. A sentence that opens with one frame, names no other speaker and keeps to one clause attributes every
 * claim it cites ("Nach Angaben des Herstellers erkennt das System Fußgänger [1] und Radfahrer [2].").
 * <p>
 * The writer puts a speaker in ({@link #withSpeaker}) only where it sees no other attribution: not in a sentence that
 * reports what anyone says, as far as {@link #reported} knows the construction ("laut einer Quelle", "nach einer
 * Studie", "wie Tests zeigen", "Die Polizei meldet"); not in one that names a speaker word or the source anywhere ("Der
 * Hersteller nennt …", "Sturzwacht beziffert …"); not where the clause also cites another source (it would read as the
 * speaker of both); and at most once in a sentence (the reviews of c10b and of 22.11.2026). A construction it does not
 * know still gets the phrase.
 */
final class VisibleAttribution {

    /** A marker group as the writer cites: {@code [2]}, {@code [3, 5]}. */
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");

    /** German words of a maker or a seller. */
    private static final String MAKER_DE = "hersteller|anbieter|händler|haendler|verkäufer|verkaeufer|vertreiber"
            + "|vertrieb|unternehmen|firma|firmen|konzern|produzent|entwickler|dienstleister|berater";
    /** German words of an association. */
    private static final String ASSOCIATION_DE = "verband|verbände|verbaende|vereinigung|innung";
    /** German words that speak for makers and associations alike: the industry and its lobby. */
    private static final String SHARED_DE = "interessenvertreter|interessenvertretung|lobby|branche";
    private static final String MAKER_EN = "manufacturers?|makers?|vendors?|sellers?|suppliers?|retailers?"
            + "|compan(?:y|ies)|providers?|developers?|consultants?|firms?";
    private static final String ASSOCIATION_EN = "associations?|federations?";
    private static final String SHARED_EN = "industry|industries|lobby|lobbies";
    /** The endings a German speaker word takes ("Herstellern", "Verbandes", "Anbieterinnen"). */
    private static final String ENDINGS_DE = "(?:s|es|e|en|n|in|innen)?";

    /** German articles and determiners before a noun, in any case. */
    private static final String ART_DE = "(?iu:der|die|das|dem|den|des|ein|eine|einem|einen|einer|eines|dieser|diese"
            + "|diesem|diesen|dieses|viele|vielen|mehrere|mehreren|einige|einigen|manche|manchen|zahlreiche|zahlreichen"
            + "|verschiedene|verschiedenen|beide|beiden|alle|allen|zwei|drei|vier|fünf|seinem|seinen|seiner|seines"
            + "|ihrem|ihren|ihrer|ihres)";
    /** German determiners of a subject: no dative or accusative ("empfiehlt dem Händler" names no speaker). */
    private static final String SUBJECT_DE = "(?iu:der|die|das|ein|eine|einige|viele|mehrere|manche|zahlreiche"
            + "|verschiedene|beide|alle|zwei|drei|vier|fünf)";
    /** Lowercase words with an adjective's ending that are no adjective between an article and its noun. */
    private static final String STOP_DE = "(?iu:der|die|das|dem|den|des|eine|einem|einen|einer|eines|keine|keinem"
            + "|keinen|keiner|keines|sie|ihre|ihrem|ihren|ihrer|ihres|seine|seinem|seinen|seiner|seines|diese|dieser"
            + "|diesem|diesen|dieses|jene|jener|jenem|jenen|jenes|welche|welcher|welchem|welchen|welches|über"
            + "|gegenüber|unter|hinter|oder|aber|wieder|immer|eher|lieber|später|früher|außer|innen|außen|unten|oben"
            + "|hinten|vorne|gerne|selten|offen|ohne|gegen|wegen|eben|daneben|dagegen|deswegen|zudem|außerdem"
            + "|trotzdem|seitdem|nachdem|indem|ebenso|weiter|sowie|beim|vom|zum|zur)";
    /** A German adjective between an article and its noun: a declined lowercase word ("großen", "deutschen"). */
    private static final String ADJ_DE = "(?!" + STOP_DE + "(?![\\p{L}]))\\p{Ll}[\\p{L}-]*(?:e|en|em|er|es)"
            + "(?![\\p{L}])";
    /** A German noun. */
    private static final String NOUN_DE = "\\p{Lu}[\\p{L}-]*";
    /** What ties a speaker to the noun before it: "ein Sprecher des Herstellers", "eine Studie von Anbietern". */
    private static final String OF_DE = "(?:des|der|eines|einer|vom|seitens|von(?:\\s+(?:dem|den|der|einem|einer))?)";
    /** Nouns of what a speaker said, for "nach Angaben des Herstellers". */
    private static final String SAID_DE = "(?=\\p{Lu})[\\p{L}-]{0,40}(?iu:angaben|aussage|aussagen|auskunft|auskünften"
            + "|darstellung|einschätzung|ansicht|auffassung|meinung|worten|informationen|mitteilung|schätzung"
            + "|schätzungen|berechnung|berechnungen)(?![\\p{L}])";
    /** The second part of a compound with a speaker: "Herstellerangaben", "Anbieterseite". */
    private static final String SAID_IN_COMPOUND_DE = "(?iu:angaben|aussagen|darstellung|informationen|auskunft"
            + "|auskünften|website|webseite|homepage|seite|datenblatt|datenblättern|broschüre|katalog|prospekt"
            + "|mitteilung|pressemitteilung)(?![\\p{L}])";
    /** Nouns of a report, a study or data: "nach einer Studie", "einer Quelle nach", "die Daten zeigen". */
    private static final String REPORT_DE = "(?=\\p{Lu})[\\p{L}-]{0,40}(?<!(?iu:dreh|stück|post|leit|licht|gefahren"
            + "|lärm|fehler|energie|strom|wärme|uhr|zeit))(?iu:studie|studien|umfrage|umfragen|untersuchung"
            + "|untersuchungen|erhebung|erhebungen|analyse|analysen|auswertung|auswertungen|bericht|berichte|berichten"
            + "|berichts|erkenntnissen|daten|zahlen|statistik|statistiken|messungen|tests|quelle|quellen|datenblatt"
            + "|datenblättern)(?![\\p{L}])";
    /** More verbs of stating, finite or as a participle: "nennt", "beziffert", "warnt vor", "angegeben". */
    private static final String STATE_PERSON_DE = "beziffert|beziffern|bezifferte|bezifferten|bewirbt|bewerben|bewarb"
            + "|bewarben|verweist|verweisen|verwies|verwiesen|plädiert|plädieren|plädierte|plädierten|zitiert|zitieren"
            + "|zitierte|zitierten|hervorgeht|hervorgehen|angegeben|genannt|beworben|versprochen"
            + "|(?:spricht|sprechen|sprach|sprachen)\\s+von|zu\\s+entnehmen|(?:fand|fanden|findet|finden)\\s+heraus";
    /** Verbs of stating a device or a product may have as its subject too ("Die App meldet Blitzer"). */
    private static final String STATE_DEVICE_DE = "(?:nennt|nennen|nannte|nannten)(?!\\s+man(?![\\p{L}]))"
            + "|garantiert|garantieren|garantierte|garantierten|meldet|melden|meldete|meldeten"
            + "|(?:warnt|warnen|warnte|warnten)\\s+vor|(?:rechnet|rechnen|rechnete|rechneten)\\s+mit";
    private static final String STATE_DE = STATE_PERSON_DE + "|" + STATE_DEVICE_DE;
    /** Separable verbs of saying, up to eight words between verb and particle: "teilt auf seiner Website mit". */
    private static final String SEPARABLE_PERSON_DE = "(?:teilt|teilen|teilte|teilten)\\s+(?:\\S+\\s+){0,8}?mit"
            + "|(?:stellt|stellen|stellte|stellten)\\s+(?:\\S+\\s+){0,8}?in\\s+Aussicht"
            + "|(?:geht|gehen|ging|gingen)\\s+(?:\\S+\\s+){0,4}?davon\\s+aus"
            + "|(?:geht|gehen|ging|gingen)\\s+(?:\\S+\\s+){0,8}?hervor"
            + "|(?:mahnt|mahnen|mahnte|mahnten)\\s+(?:\\S+\\s+){0,8}?an"
            + "|(?:räumt|räumen|räumte|räumten)\\s+(?:\\S+\\s+){0,8}?ein"
            + "|(?:spricht|sprechen|sprach|sprachen)\\s+sich\\s+(?:\\S+\\s+){0,8}?aus"
            + "|(?:setzt|setzen|setzte|setzten)\\s+sich\\s+(?:\\S+\\s+){0,8}?ein";
    /** Separable verbs a device or a product may have as its subject too ("Das System weist den Fahrer hin"). */
    private static final String SEPARABLE_DEVICE_DE = "(?:gibt|geben|gab|gaben)\\s+(?:\\S+\\s+){0,8}?an"
            + "|(?:hebt|heben|hob|hoben)\\s+(?:\\S+\\s+){0,8}?hervor"
            + "|(?:weist|weisen|wies|wiesen)\\s+(?:\\S+\\s+){0,8}?hin"
            + "|(?:stellt|stellen|stellte|stellten)\\s+(?:\\S+\\s+){0,8}?fest"
            + "|(?:führt|führen|führte|führten)\\s+(?:\\S+\\s+){0,8}?aus"
            + "|(?:kündigt|kündigen|kündigte|kündigten)\\s+(?:\\S+\\s+){0,8}?an";
    private static final String SEPARABLE_DE = SEPARABLE_PERSON_DE + "|" + SEPARABLE_DEVICE_DE;
    /** Verbs of saying, finite, with the speaker as subject; the separable ones with their particle. */
    private static final String SAY_PERSON_DE = "betont|betonen|betonte|betonten|erklärt|erklären|erklärte|erklärten"
            + "|berichtet|berichten|berichtete|berichteten|schreibt|schreiben|schrieb|schrieben|beschreibt|beschreiben"
            + "|beschrieb|beschrieben|sagt|sagen|sagte|sagten|versichert|versichern|versicherte|versicherten"
            + "|verspricht|versprechen|versprach|versprachen|behauptet|behaupten|behauptete|behaupteten"
            + "|unterstreicht|unterstreichen|unterstrich|erläutert|erläutern|erläuterte|bestätigt|bestätigen"
            + "|bestätigte|argumentiert|argumentieren|argumentierte|meint|meinen|meinte|angibt|angeben|mitteilt"
            + "|mitteilen|hervorhebt|hervorheben|hinweist|hinweisen|feststellt|feststellen|ausführt|ausführen"
            + "|teilt\\s+mit|teilen\\s+mit|teilte\\s+mit|weist\\s+darauf\\s+hin|weisen\\s+darauf\\s+hin"
            + "|wies\\s+darauf\\s+hin|geht\\s+davon\\s+aus|gehen\\s+davon\\s+aus";
    /** Verbs of saying a device may have as its subject too; the reporting check guards them (DEVICE_PRONE). */
    private static final String SAY_DEVICE_DE = "gibt\\s+an|geben\\s+an|gab\\s+an|gaben\\s+an|hebt\\s+hervor"
            + "|heben\\s+hervor|hob\\s+hervor|führt\\s+aus|führen\\s+aus|stellt\\s+fest|stellen\\s+fest";
    private static final String SAY_DE = SAY_PERSON_DE + "|" + SAY_DEVICE_DE;
    /** The same verbs at the end of a "wie" clause ("wie der Verband betont", "wie ein Anbieter angibt"). */
    private static final String SAY_LAST_DE = "angibt|angeben|angab|angaben|betont|betonen|betonte|erklärt|erklären"
            + "|erklärte|berichtet|berichten|berichtete|schreibt|schreiben|schrieb|beschreibt|beschreiben|beschrieb"
            + "|mitteilt|mitteilen|mitteilte|sagt|sagen|sagte|versichert|versichern|verspricht|versprechen|behauptet"
            + "|behaupten|hervorhebt|hervorheben|unterstreicht|unterstreichen|erläutert|erläutern|bestätigt|bestätigen"
            + "|hinweist|hinweisen|argumentiert|argumentieren|darstellt|darstellen|ausführt|ausführen|feststellt"
            + "|feststellen|empfiehlt|empfehlen|fordert|fordern|wirbt|werben|meint|meinen";
    /** Verbs whose object is the claim itself: a recommendation, a demand. */
    private static final String ASK_DE = "empfiehlt|empfehlen|empfahl|empfahlen|fordert|fordern|forderte|forderten"
            + "|rät|raten|riet|rieten";
    /** Adverbs between a speaker and its verb. */
    private static final String ADV_DE = "(?:auch|zudem|selbst|ebenfalls|dazu|ausdrücklich|stets|jedoch|allerdings"
            + "|weiterhin|nun|inzwischen|mittlerweile|immer\\s+wieder)";
    /** Words of degree before the adjective "laut" (loud): "kaum laut", "sehr laut". */
    private static final String DEGREE_DE = "(?<!(?iu:sehr|zu|kaum|recht|ziemlich|so|nicht|besonders|extrem|relativ"
            + "|eher|weniger|wenig|viel|ganz|richtig|ungewöhnlich|ausgesprochen|unangenehm|angenehm|genauso|ebenso)"
            + "\\s)";

    private static final String ART_EN = "(?iu:the|a|an|its|their|one|another|several|many|some|most|two|three|all"
            + "|both|various|numerous)";
    private static final String STOP_EN = "(?iu:the|a|an|and|or|but|not|no|that|which|who|whose|this|these|those"
            + "|it|they|its|their|of|for|with|by|to|in|on|at|from|over|under|against|without|than|then|so|as|also"
            + "|very|too|only|just|even|more|most|less|least|many|some|several|one|two|three|all|both|each|every"
            + "|any|other|another|such|there|here|now|when|while|where|because|although|if|is|are|was|were|be"
            + "|been|has|have|had|do|does|did|will|would|can|could|may|might|must|should)";
    /** An English adjective: a lowercase word, no plural noun ("according to experts many companies" names none). */
    private static final String ADJ_EN = "(?!" + STOP_EN + "(?![\\p{L}]))\\p{Ll}[\\p{L}-]*[\\p{L}&&[^s]](?![\\p{L}])";
    private static final String SAY_EN = "says|said|states|stated|reports|reported|claims|claimed|explains|explained"
            + "|notes|noted|writes|wrote|describes|described|stresses|stressed|emphasizes|emphasized|emphasises"
            + "|emphasised|points\\s+out|pointed\\s+out|argues|argued|adds|added|confirms|confirmed|promises|promised"
            + "|advertises|maintains|insists|believes|expects|estimates|puts\\s+it";
    private static final String ASK_EN = "recommends|recommended|advises|advised|calls\\s+for|called\\s+for|demands"
            + "|demanded|urges|urged";
    private static final String ADV_EN = "(?:also|too|further|explicitly|repeatedly|now|however)";

    /** What opens the words a speaker says: a comma, a colon, "dass", "that". */
    private static final String OPENS = "\\s*(?:,|:|\\s+(?:dass|that)(?![\\p{L}]))";

    /** Where a clause may start: the start of the text (after punctuation there), after ; : ( – — or a comma. */
    private static final Pattern CLAUSE_START = Pattern.compile("^[\\s„“\"‚‘'«»(–—,;:-]*|[;:(–—,]\\s*");
    /** A conjunction at the start of a clause, before its subject. */
    private static final Pattern CONJUNCTION = Pattern.compile("(?iu:und|aber|doch|denn|sondern|zudem|außerdem"
            + "|jedoch|während|wobei|weil|da|obwohl|wenn|nachdem|zumal|and|but|while|whereas|although|because"
            + "|since)\\s+");
    /** A speaker named only to be set against the claim: "Anders als die Hersteller behaupten, …". */
    private static final Pattern CONTRAST = Pattern.compile("(?iu:anders\\s+als|entgegen|im\\s+gegensatz\\s+zu"
            + "|contrary\\s+to|unlike|in\\s+contrast\\s+to)(?![\\p{L}])");
    /** Where a sentence starts a new clause, so an opening frame does not reach further. */
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,:]\\s*(?iu:und|aber|doch|jedoch|sondern|denn"
            + "|während|wobei|weil|da|obwohl|wenn|als|tatsächlich|allerdings|zudem|außerdem|dennoch|trotzdem"
            + "|gleichzeitig|zugleich|hingegen|dagegen|and|but|while|whereas|although|because|however|yet|which"
            + "|who)(?![\\p{L}])|\\]\\s*(?iu:und|aber|doch|sondern|oder|and|but|or)\\s+(?iu:der|die|das|ein|eine|er"
            + "|sie|es|wir|man|the|a|an|it|they|this|these)(?![\\p{L}])");
    /**
     * A tag right after a marker group that refers back to it (", so der Hersteller", "(wie der Verband betont)"),
     * up to a comma, a semicolon, a bracket or the sentence's end; never up to the next marker.
     */
    private static final Pattern TAG = Pattern.compile("\\s*[,(–—]\\s*(?:so|wie|as|laut|according\\s+to|says|said"
            + "|states)(?![\\p{L}])[^\\[\\],;()]*?(?=[,;)]|[.!?…]?\\s*$)");
    /** After a marker group: the sentence goes on with a word, so an inserted clause needs a comma after it. */
    private static final Pattern GOES_ON = Pattern.compile("\\s+[\\p{L}\\d]");
    /** What may stand right before an inserted speaker: the end of a word, a number, a unit. */
    private static final String WORD_END = "%€$°²³";
    /** The public suffixes, from the bundled list of publicsuffix.org: "bbc.co.uk", "unsw.edu.au", "x.blogspot.com". */
    private static final PublicSuffixDomainKeyResolver SUFFIXES = new PublicSuffixDomainKeyResolver();
    /**
     * Sites that host many others under their own names and that the public suffix list does not know as such: the
     * site's name is the label before them ("osha.europa.eu", "bsi.bund.de"); the site itself has none.
     */
    private static final List<String> UMBRELLAS = Arrays.asList("europa.eu", "bund.de", "admin.ch", "wordpress.com",
            "substack.com");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    /** An abbreviation whose full stop ends the words before a marker ("usw. [2]", "& Co. [2]"): no sentence end. */
    private static final Pattern ABBREVIATION_END = Pattern.compile("(?<![\\p{L}\\d])(?iu:usw|etc|bzw|ggf|evtl|inkl"
            + "|zzgl|vgl|co|inc|ltd|corp|u\\.\\s?a|u\\.\\s?ä|o\\.\\s?ä|u\\.\\s?v\\.\\s?m|z\\.\\s?b|d\\.\\s?h|\\p{L}|ca"
            + "|dr|nr|mio|mrd|tsd|jh|std|min|sek|abb|kap|tab|abs|art|hrsg|ff|chr|bspw|insb|mwst|lt|gem|bzgl|max"
            + "|vs)\\.$");
    /** A word after "laut", "gemäß" or "so" that names who speaks: a noun or a site ("laut conrad.example"). */
    private static final String WHO = "(?:\\p{Lu}|[\\p{L}\\d-]+\\.\\p{L}{2,}(?![\\p{L}]))";
    /** Verbs whose subject shows what it says: "Studien zeigen, dass …", "wie Tests belegen". */
    private static final String SHOW_DE = "zeigt|zeigen|zeigte|zeigten|belegt|belegen|belegte|belegten|ergab|ergaben"
            + "|ergibt|ergeben|nahelegt|nahelegen|schätzt|schätzen|warnt|warnen|kritisiert|kritisieren|heißt|hieß";
    private static final String SHOW_EN = "shows?|showed|shown|found|finds|suggests?|suggested|indicates?|indicated";
    /** English verbs of saying in the plural ("as researchers report"). */
    private static final String SAY_EN_PLURAL = "say|state|report|claim|explain|note|write|describe|stress"
            + "|emphasi[sz]e|argue|add|confirm|promise|recommend|advise|warn|believe|expect|estimate|point\\s+out";
    /** English devices in the plural that may "warn" or "report" without speaking ("Sensors warn the driver"). */
    private static final String DEVICES_EN = "(?iu:sensors|systems|cameras|devices|lights|lamps|apps|vehicles|cars"
            + "|models|radars|helmets|brakes|displays|tools|alarms|signals)(?![\\p{L}])";
    /** A device or product as a German noun or the end of a compound: "Der Totwinkelassistent warnt vor …". */
    private static final Pattern DEVICE_DE = Pattern.compile("(?<![\\p{L}])\\p{L}{0,30}?(?iu:system|systeme|assistent"
            + "|assistenten|app|apps|kamera|kameras|sensor|sensoren|gerät|geräte|display|displays|software|leuchte"
            + "|leuchten|licht|lichter|signalton|warnton|beschichtung|folie|helm|helme|fahrzeug|fahrzeuge|modell"
            + "|modelle|funktion|funktionen|radar|akku|motor|bremse|bremsen|reifen|lampe|lampen|anzeige|steuerung"
            + "|modul|navi|navigation|ampel|ampeln|blitzer|programm|algorithmus|tool|chip|alarm|signal)"
            + "(?:s|es|e|en|n)?(?![\\p{L}])");
    /**
     * Verbs of stating a device may have as its subject too ({@link #STATE_DEVICE_DE}, {@link #SEPARABLE_DEVICE_DE}):
     * they count as reporting only in a clause without a device noun (the review of 22.11.2026: "Die App meldet
     * Blitzer in Echtzeit", "Das System weist den Fahrer auf Gefahren hin" name no speaker).
     */
    private static final Pattern DEVICE_PRONE = Pattern.compile("(?<![\\p{L}])(?:" + STATE_DEVICE_DE + ")(?![\\p{L}])"
            + "|(?<![\\p{L}])(?:" + SEPARABLE_DEVICE_DE + ")(?=\\s*[,.;:!?]|\\s*$)");
    /**
     * A reporting construction, whoever speaks, as far as it knows one: no speaker is put in beside one (the review of
     * c10b: ", wie ein Anbieter angibt" beside "laut einer Quelle"; the review of 22.11.2026: "nach einer Studie", "wie
     * aus einer Studie hervorgeht", "Die Polizei meldet", "Experts say"). It never clears a claim, so it may take a
     * sentence for reported that is none ("Die Leitstelle erklärt den Ablauf", "Die Kamera meldet einen Fehler"): that
     * costs only the inserted speaker.
     */
    private static final Pattern REPORTED = Pattern.compile(String.join("|",
            // laut einer Quelle, laut einer 2023 veröffentlichten Studie, gemäß „Auto heute“, laut Experten, lt.
            // Hersteller; not the adjective ("ist laut [2].", "laut und deutlich")
            // ("laut über den Asphalt", "laut im Stadtverkehr"): a lowercase word only as an adjective before a noun
            DEGREE_DE + "(?<![\\p{L}])(?:[Ll]aut|[Ll]t\\.|[Gg]emäß|[Gg]em\\.)\\s+(?:" + ART_DE + "\\s+)?(?:" + ADJ_DE
                    + "\\s+){0,3}(?:\\p{Lu}|\\d|[„“\"‚'«»§]|[\\p{L}\\d-]+\\.\\p{L}{2,}(?![\\p{L}]))",
            // nach Angaben, nach eigenen Angaben, laut Herstellerangaben, nach Einschätzung von Fachleuten
            "(?<![\\p{L}])(?:[Nn]ach|[Ll]aut|[Gg]emäß)\\s+(?:\\p{Ll}+\\s+)?" + SAID_DE,
            // nach einer Studie, nach neuen Medienberichten, einer Quelle nach, seiner Einschätzung nach, demnach; not
            // "Unfallzahlen nach Bundesländern", "Messungen nach WLTP"
            "(?<![\\p{L}])[Nn]ach\\s+(?:" + ART_DE + "\\s+)?(?:" + ADJ_DE + "\\s+){0,3}" + REPORT_DE,
            "(?<![\\p{L}])(?:" + REPORT_DE + "|" + SAID_DE + ")\\s+nach(?=\\s*[,;:.!?]|\\s+\\p{Ll}|\\s*$)",
            "(?<![\\p{L}])[Dd]emnach(?![\\p{L}])",
            "(?<![\\p{L}])" + REPORT_DE + "\\s+(?:\\S+\\s+){0,3}?(?:" + SHOW_DE + "|nennt|nennen|nannte|nannten"
                    + "|meldet|melden|meldete|meldeten)(?![\\p{L}])",
            // einer Studie zufolge, Berichten zufolge
            "(?<![\\p{L}])zufolge(?![\\p{L}])",
            // aus Sicht der Forscher, aus ihrer Sicht
            "(?<![\\p{L}])[Aa]us\\s+(?:\\p{Ll}+\\s+){0,2}Sicht(?![\\p{L}])",
            // heißt es in der Studie; ist von … die Rede
            "(?<![\\p{L}])(?:heißt|hieß)\\s+es(?![\\p{L}])",
            "(?<![\\p{L}])(?:ist|war|wird)\\s+(?:\\S+\\s+){0,4}?die\\s+Rede(?![\\p{L}])",
            // wie Forscher berichten, wie eine Studie zeigt, wie es heißt
            "(?<![\\p{L}])[Ww]ie\\s+(?:[^\\s,;:.!?()\\[\\]]+\\s+){1,6}?(?:" + SAY_LAST_DE + "|" + SHOW_DE
                    + ")(?![\\p{L}])",
            // any "wie" clause after a comma up to the next stop: "…, wie aus einer Studie hervorgeht", "…, wie im
            // Datenblatt steht"; also a list of examples (", wie Lkw und Busse"), which costs only the phrase
            "(?:^|[,(–—])\\s*[Ww]ie\\s+(?:[^\\s,;:.!?()\\[\\]]+\\s+){1,7}?[^\\s,;:.!?()\\[\\]]+\\s*"
                    + "(?=[,;:.!?)–—]|$)",
            // …, so die Autoren.
            "(?:^|[,;:(–—])\\s*so\\s+(?:[\\p{L}.-]+\\s+){0,8}?" + WHO + "[\\p{L}\\d.-]*(?:\\s+weiter)?\\s*"
                    + "(?=[,.;:!?)–—]|$)",
            // a verb of saying, stating, a recommendation or a promise anywhere: "Experten betonen …", "Der
            // Hersteller verspricht acht Stunden", "empfiehlt der Verband", "Die Polizei meldet", "warnen vor"
            "(?<![\\p{L}])(?:" + SAY_PERSON_DE + "|" + SAY_LAST_DE + "|" + ASK_DE + "|" + STATE_PERSON_DE
                    + ")(?![\\p{L}])",
            // Der Hersteller gibt eine Laufzeit von acht Stunden an; teilt auf seiner Website mit; spricht sich für
            // mehr Kontrollen aus
            "(?<![\\p{L}])(?:" + SEPARABLE_PERSON_DE + ")(?=\\s*[,.;:!?]|\\s*$)",
            // Studien zeigen, dass …; Die Daten belegen: …
            "(?<![\\p{L}])(?:" + SHOW_DE + ")(?:\\s+[\\p{L}-]+){0,5}?\\s*(?::|,?\\s+dass(?![\\p{L}]))",
            "(?<![\\p{L}])(?:[Aa]ngeblich|[Mm]utmaßlich)(?![\\p{L}])",
            // according to, reportedly
            "(?<![\\p{L}])(?iu:according\\s+to|reportedly|allegedly|purportedly|supposedly)(?![\\p{L}])",
            // as researchers report, as a study shows
            "(?<![\\p{L}])[Aa]s\\s+(?:[^\\s,;:.!?()\\[\\]]+\\s+){1,6}?(?:" + SAY_EN + "|" + ASK_EN + "|" + SHOW_EN
                    + "|" + SAY_EN_PLURAL + ")(?![\\p{L}])",
            "(?<![\\p{L}])(?:" + SAY_EN + "|" + ASK_EN + "|quotes|quoted)(?![\\p{L}])",
            "(?<![\\p{L}])(?:" + SHOW_EN + ")\\s+that(?![\\p{L}])",
            // Experts say, Police report, Critics argue: a plural subject that opens a clause
            "(?:^|[,;:]\\s*)(?:(?iu:the|many|some|most)\\s+)?(?:(?!" + DEVICES_EN + ")\\p{L}+s|[Pp]olice|[Pp]eople"
                    + "|[Tt]hey)\\s+(?:"
                    + ADV_EN + "\\s+)?(?:" + SAY_EN_PLURAL + ")(?![\\p{L}])",
            // A study found, Studies suggest
            "(?<![\\p{L}])(?iu:study|studies|survey|surveys|report|reports|data|test|tests|research|analysis"
                    + "|analyses)\\s+(?:\\S+\\s+){0,3}?(?:" + SHOW_EN + ")(?![\\p{L}])",
            // per the manufacturer; is rated at, is given as, is said to; not "is given to the driver"
            "(?<![\\p{L}])[Pp]er\\s+(?:the|its|their)\\s",
            "(?<![\\p{L}])(?iu:is|are|was|were)\\s+(?:(?iu:rated|listed|quoted)\\s+(?iu:at|as)|(?iu:given)\\s+"
                    + "(?iu:as)|(?iu:put|estimated)\\s+(?iu:at)|(?iu:estimated|believed|said|reported|claimed"
                    + "|thought)\\s+(?iu:to))(?![\\p{L}])"));
    private static final Map<String, Frames> FRAMES = new ConcurrentHashMap<String, Frames>();

    private VisibleAttribution() {
    }

    /** Whether the sentence names a speaker of any kind for every place where it cites passage {@code number}. */
    static boolean attributes(String sentence, int number, Collection<String> names) {
        return attributes(sentence, number, names, "");
    }

    /**
     * Whether the sentence names the speaker for every place where it cites passage {@code number}.
     *
     * @param sentence   the sentence with its markers
     * @param number     the passage number the claim cites
     * @param names      names of the passage's source the sentence may use (its site, e.g. {@code example.com})
     * @param sourceKind the kind of the passage's source (component 16): a maker or seller for
     *                   {@code HERSTELLER_HANDEL}, an association for {@code VERBAND}, any speaker for another kind
     */
    static boolean attributes(String sentence, int number, Collection<String> names, String sourceKind) {
        String text = sentence == null ? "" : sentence;
        Frames frames = Frames.of(Kind.of(sourceKind), names);
        Groups groups = new Groups(text);
        boolean found = false;
        for (int i = 0; i < groups.size(); i++) {
            if (!groups.cites(i, number)) {
                continue;
            }
            found = true;
            if (!frames.in(groups.part(i)) && !opens(text, groups, i, frames, names)) {
                return false;
            }
        }
        return found || frames.in(MARKER.matcher(text).replaceAll(" "));
    }

    /** {@link #withSpeaker(String, int, Collection, String, Collection)}; any other passage: another source. */
    static String withSpeaker(String sentence, int number, Collection<String> names, String phrase) {
        return withSpeaker(sentence, number, names, phrase, Collections.singleton(number));
    }

    /**
     * The sentence with {@code phrase} (", wie ein Anbieter angibt") put before the marker group citing passage
     * {@code number}; a comma closes the phrase when the sentence goes on with a word after the group. A marker written
     * after the full stop ("… Kennzeichen. [2]") gets the phrase before the full stop. The sentence as it is when:
     * <ul>
     * <li>it reports what anyone says, as far as {@link #reported} knows the construction: no second attribution beside
     * one, also not beside "laut einer Quelle" (the review of c10b, 22.11.2026);</li>
     * <li>it names a speaker word of any kind ("der Hersteller", "Herstellerangaben", "Branchenkenner") or a name of
     * the source ("Sturzwacht nennt …", "VDB-Angaben") anywhere: the phrase would name a speaker twice or beside
     * another (the review of 22.11.2026); a source whose name is a common noun ("Blitzer") thus gets none;</li>
     * <li>the clause of the group (up to the ";" around it) cites a passage of another source: the phrase would read as
     * the speaker of both (c10b: "… [15] … , wie ein Anbieter angibt [12]");</li>
     * <li>more than one group cites the passage: one phrase per sentence;</li>
     * <li>a group has no words before it, a comma, a colon, a quote or a bracket stands right before it, or the full
     * stop before it belongs to an abbreviation ("usw. [2]"): no place where the phrase reads as one sentence.</li>
     * </ul>
     *
     * @param own the numbers of the passages of the same source as {@code number}, itself among them
     */
    static String withSpeaker(String sentence, int number, Collection<String> names, String phrase,
                              Collection<Integer> own) {
        String text = sentence == null ? "" : sentence;
        if (reported(text) || Frames.of(Kind.ANY, names).names(MARKER.matcher(text).replaceAll(" "))) {
            return text;
        }
        Groups groups = new Groups(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (!groups.cites(i, number)) {
                continue;
            }
            if (copied > 0 || !groups.onlyOwn(i, own)) {
                return text;
            }
            int start = groups.start(i);
            int end = start;
            while (end > copied && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            int from = i == 0 ? 0 : groups.end(i - 1);
            if (end <= copied || text.substring(from, end).trim().isEmpty()) {
                return text;
            }
            int at = end;
            while (at > copied && ".!?…".indexOf(text.charAt(at - 1)) >= 0) {
                at--;
            }
            if (at <= copied || !endsAWord(text.charAt(at - 1))
                    || at < end && ABBREVIATION_END.matcher(text.substring(copied, end)).find()) {
                return text;
            }
            if (at == end) {
                out.append(text, copied, end).append(phrase).append(' ').append(text, start, groups.end(i));
            } else {
                out.append(text, copied, at).append(phrase).append(text, at, groups.end(i));
            }
            copied = groups.end(i);
            if (at == end && GOES_ON.matcher(text.substring(copied)).lookingAt()) {
                out.append(',');
            }
        }
        return copied == 0 ? text : out.append(text.substring(copied)).toString();
    }

    /** Whether the sentence, without its markers, reports what anyone says ({@link #REPORTED}). */
    static boolean reported(String sentence) {
        if (sentence == null) {
            return false;
        }
        String text = MARKER.matcher(sentence).replaceAll(" ");
        if (REPORTED.matcher(text).find()) {
            return true;
        }
        Matcher verb = DEVICE_PRONE.matcher(text);
        while (verb.find()) {
            int from = verb.start();
            while (from > 0 && ",;:".indexOf(text.charAt(from - 1)) < 0) {
                from--;
            }
            int to = verb.end();
            while (to < text.length() && ",;:.!?".indexOf(text.charAt(to)) < 0) {
                to++;
            }
            if (!DEVICE_DE.matcher(text.substring(from, to)).find()) {
                return true;
            }
        }
        return false;
    }

    /** Whether one marker group of the sentence cites both passages. */
    static boolean shareAGroup(String sentence, int a, int b) {
        Groups groups = new Groups(sentence == null ? "" : sentence);
        for (int i = 0; i < groups.size(); i++) {
            if (groups.cites(i, a) && groups.cites(i, b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsAWord(char c) {
        return Character.isLetterOrDigit(c) || WORD_END.indexOf(c) >= 0;
    }

    /**
     * Whether group {@code i} takes its speaker from the frame that opens the sentence: the only frame of the sentence
     * (of any kind), at most three words after its start, before the first marker, of the source's kind, and no new
     * clause, no ";" and no contrast between it and the group.
     */
    private static boolean opens(String text, Groups groups, int i, Frames frames, Collection<String> names) {
        List<int[]> any = Frames.of(Kind.ANY, names).spans(text);
        if (any.size() != 1) {
            return false;
        }
        int[] frame = any.get(0);
        if (groups.size() > 0 && groups.start(0) < frame[0]) {
            return false;
        }
        String before = text.substring(0, frame[0]).trim();
        if (!before.isEmpty() && before.split("\\s+").length > 3) {
            return false;
        }
        List<int[]> own = frames.spans(text);
        if (own.size() != 1 || own.get(0)[0] != frame[0]) {
            return false;
        }
        String between = text.substring(Math.min(frame[1], groups.start(i)), groups.start(i));
        return between.indexOf(';') < 0 && !CLAUSE_BREAK.matcher(between).find()
                && !CONTRAST.matcher(between).find();
    }

    /** The marker groups of a sentence: where each stands and the passage numbers it cites. */
    private static final class Groups {
        private final String text;
        private final List<int[]> spans = new ArrayList<int[]>();
        private final List<Set<Integer>> numbers = new ArrayList<Set<Integer>>();

        Groups(String text) {
            this.text = text;
            Matcher marker = MARKER.matcher(text);
            while (marker.find()) {
                spans.add(new int[]{marker.start(), marker.end()});
                Set<Integer> cited = new LinkedHashSet<Integer>();
                for (String n : marker.group(1).split("[,;]")) {
                    cited.add(Integer.parseInt(n.trim()));
                }
                numbers.add(cited);
            }
        }

        int size() {
            return spans.size();
        }

        int start(int i) {
            return spans.get(i)[0];
        }

        int end(int i) {
            return spans.get(i)[1];
        }

        boolean cites(int i, int number) {
            return numbers.get(i).contains(number);
        }

        /**
         * Whether every group in the clause of group {@code i} cites only passages of {@code own}: the clause runs from
         * the ";" before the group to the ";" after it, a ";" inside a marker group ("[2; 3]") not counted.
         */
        boolean onlyOwn(int i, Collection<Integer> own) {
            char[] masked = text.toCharArray();
            for (int[] span : spans) {
                Arrays.fill(masked, span[0], span[1], ' ');
            }
            String clauses = new String(masked);
            int from = clauses.lastIndexOf(';', start(i)) + 1;
            int to = clauses.indexOf(';', end(i));
            to = to < 0 ? text.length() : to;
            for (int j = 0; j < size(); j++) {
                if (start(j) >= from && end(j) <= to && !own.containsAll(numbers.get(j))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The words group {@code i} carries: from the group before (after its tag) or the start to it, only after the
         * last ";" in between, and the tag right after it.
         */
        String part(int i) {
            int from = i == 0 ? 0 : tagEnd(i - 1);
            String part = from < start(i) ? text.substring(from, start(i)) : "";
            int semicolon = part.lastIndexOf(';');
            if (semicolon >= 0) {
                part = part.substring(semicolon + 1);
            }
            return part + " " + text.substring(end(i), tagEnd(i));
        }

        /** Where the tag right after group {@code i} ends; the group's end when it has none. */
        int tagEnd(int i) {
            Matcher tag = TAG.matcher(text);
            tag.useAnchoringBounds(false);
            tag.useTransparentBounds(true);
            tag.region(end(i), i + 1 < size() ? start(i + 1) : text.length());
            return tag.lookingAt() ? tag.end() : end(i);
        }
    }

    /**
     * The site of a source as a reader may see it named: the host without {@code www.} (also decoded from punycode,
     * "münchen.example"), and its own name ({@link #nameOf}). A name of up to three letters counts only as
     * {@link #nameOf} writes it, in capitals ("VDB"), so a short word does not count.
     */
    static List<String> namesOf(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<String>();
        names.add(host);
        String unicode = IDN.toUnicode(host);
        if (!unicode.equals(host)) {
            names.add(unicode);
        }
        String label = labelOf(host);
        if (label.length() >= 4) {
            names.add(label);
        } else if (label.length() >= 2 && label.indexOf('-') < 0) {
            names.add(label.toUpperCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * The name the writer's prompt gives a source's site: its own name without its ending and subdomains, as a reader
     * knows it ("Sturzwacht" of source.sturzwacht.example, "Verkehrstechnik-Nord", "UNSW" of unsw.edu.au), a name of up
     * to four letters in capitals ("VDB", "ADFC"); the host when the site has no such name (an address, a public suffix
     * or a site hosting others itself: "gov.uk", "bund.de"); {@code ""} for no site. The review of c10b found 54
     * sentences with a domain as the speaker ("Der Hersteller conrad.at").
     */
    static String nameOf(String url) {
        String host = hostOf(url);
        String label = host.isEmpty() ? "" : labelOf(host);
        if (label.length() < 2 || label.startsWith("-") || label.endsWith("-") || label.contains("--")) {
            return host;
        }
        if (label.length() <= 4) {
            return label.indexOf('-') < 0 ? label.toUpperCase(Locale.ROOT) : host;
        }
        StringBuilder name = new StringBuilder();
        for (String part : label.split("-")) {
            name.append(name.length() == 0 ? "" : "-").append(part.substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(part.substring(1));
        }
        return name.toString();
    }

    /**
     * The site's own label, decoded from punycode: the one before its public suffix ("android" of source.android.com,
     * "unsw" of unsw.edu.au, "verkehr" of verkehr.blogspot.com) or before a site hosting others ("osha" of
     * osha.europa.eu); {@code ""} for an address, a public suffix or a site hosting others itself ("gov.uk", "bund.de")
     * and a label without a letter ("24").
     */
    private static String labelOf(String host) {
        DomainIdentity identity = SUFFIXES.resolve(host);
        if (identity.getHostKind() != HostKind.REGISTERED_NAME || UMBRELLAS.contains(host)
                || SUFFIXES.resolve("x." + host).getRegistrableDomain().equals("x." + host)) {
            return "";
        }
        String domain = identity.getRegistrableDomain();
        String label = domain.substring(0, Math.max(0, domain.indexOf('.')));
        for (String umbrella : UMBRELLAS) {
            if (host.endsWith("." + umbrella)) {
                String rest = host.substring(0, host.length() - umbrella.length() - 1);
                label = rest.substring(rest.lastIndexOf('.') + 1);
            }
        }
        String decoded = IDN.toUnicode(label);
        return decoded.startsWith("xn--") || !LETTER.matcher(decoded).find() ? "" : decoded;
    }

    /** The registrable domain of a host by the public suffix list (adac.de of presse.adac.de); {@code ""} for none. */
    static String registrableOf(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        DomainIdentity identity = SUFFIXES.resolve(host);
        return identity.getHostKind() != HostKind.REGISTERED_NAME ? "" : identity.getRegistrableDomain();
    }

    /** The host of a URL or of a bare domain ({@code example.com/x}), lowercase, without {@code www.}. */
    static String hostOf(String url) {
        String t = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int scheme = t.indexOf("://");
        if (scheme >= 0) {
            t = t.substring(scheme + 3);
        } else if (t.startsWith("//")) {
            t = t.substring(2);
        }
        int end = t.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int at = t.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        t = t.substring(0, end);
        int user = t.lastIndexOf('@');
        if (user >= 0) {
            t = t.substring(user + 1);
        }
        int port = t.indexOf(':');
        if (port >= 0) {
            t = t.substring(0, port);
        }
        if (t.startsWith("www.")) {
            t = t.substring(4);
        }
        return t.matches("[\\p{L}\\d-]+(?:\\.[\\p{L}\\d-]+)+") ? t : "";
    }

    /** The kinds of speaker: makers and sellers, associations, or any of them. */
    enum Kind {
        MAKER(MAKER_DE + "|" + SHARED_DE, MAKER_EN + "|" + SHARED_EN),
        ASSOCIATION(ASSOCIATION_DE + "|" + SHARED_DE, ASSOCIATION_EN + "|" + SHARED_EN),
        ANY(MAKER_DE + "|" + ASSOCIATION_DE + "|" + SHARED_DE, MAKER_EN + "|" + ASSOCIATION_EN + "|" + SHARED_EN);

        private final String german;
        private final String english;

        Kind(String german, String english) {
            this.german = german;
            this.english = english;
        }

        /** The kind of speaker of a source's kind (component 16). */
        static Kind of(String sourceKind) {
            if ("HERSTELLER_HANDEL".equals(sourceKind)) {
                return MAKER;
            }
            if ("VERBAND".equals(sourceKind)) {
                return ASSOCIATION;
            }
            return ANY;
        }
    }

    /** The frames for one kind of speaker and the names of one source. */
    static final class Frames {
        private final Pattern anywhere;
        private final Pattern clause;
        private final Pattern named;

        private Frames(Kind kind, Collection<String> names) {
            String speaker = speaker(kind, names);
            String name = nameCore(names);
            String capitals = capitalsCore(names);
            String stem = "(?=\\p{Lu})[\\p{L}-]{0,40}?(?iu:" + kind.german + ")";
            String npAny = "(?:" + ART_DE + "\\s+)?(?:" + ADJ_DE + "\\s+){0,3}" + speaker;
            String npOf = "(?:" + ART_DE + "\\s+)?(?:" + ADJ_DE + "\\s+){0,3}" + NOUN_DE + "\\s+" + OF_DE + "\\s+(?:"
                    + ADJ_DE + "\\s+){0,3}" + speaker;
            String npPlain = "(?:" + SUBJECT_DE + "\\s+)?(?:" + ADJ_DE + "\\s+){0,3}" + speaker;
            String npSubject = "(?:" + npPlain + "|(?:" + SUBJECT_DE + "\\s+)?(?:" + ADJ_DE + "\\s+){0,3}" + NOUN_DE
                    + "\\s+" + OF_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3}" + speaker + ")";
            String npEnglish = "(?:" + ART_EN + "\\s+)?(?:" + ADJ_EN + "\\s+){0,2}" + speaker;
            String[] anywhereFrames = {
                    // laut dem Hersteller, laut einem großen Anbieter, laut Branchenportalen, laut einem Sprecher des
                    // Herstellers; not the adjective ("kaum laut und spart Unternehmen")
                    DEGREE_DE + "(?<![\\p{L}])(?:[Ll]aut|lt\\.|[Gg]emäß)\\s+(?:" + npAny + "|" + npOf + ")",
                    // according to the manufacturer, according to the company's website
                    "(?<![\\p{L}])[Aa]ccording\\s+to\\s+" + npEnglish,
                    // nach Angaben des Herstellers, laut den Aussagen eines Verbands, nach Angaben eines Sprechers des
                    // Verbands
                    "(?<![\\p{L}])(?:[Ll]aut|lt\\.|[Nn]ach|[Gg]emäß)\\s+(?:(?:den|eigenen|offiziellen|aktuellen|neuen"
                            + "|jüngsten)\\s+)?" + SAID_DE + "\\s+" + OF_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3}(?:"
                            + NOUN_DE + "\\s+" + OF_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3})?" + speaker,
                    // nach Herstellerangaben, laut Anbieterseite
                    "(?<![\\p{L}])(?:[Ll]aut|lt\\.|[Nn]ach|[Gg]emäß)\\s+(?:(?:den|der|dem)\\s+)?(?:" + ADJ_DE
                            + "\\s+)?" + stem + "[\\p{L}-]{0,40}?" + SAID_IN_COMPOUND_DE,
                    // dem Hersteller zufolge, Angaben des Verbands zufolge
                    "(?<![\\p{L}])(?:" + npAny + "|" + npOf + ")\\s+zufolge(?![\\p{L}])",
                    // …, so der Hersteller; …, so ein Sprecher des Verbands
                    "(?:^|[,;:(–—])\\s*so\\s+" + npSubject + "(?:\\s+weiter)?\\s*(?=\\[|[,.;:!?)–—]|$)",
                    // …, says the company
                    "(?:^|[,;:(–—])\\s*(?:says|said|states|stated|writes|wrote|explains|explained|notes|noted|adds"
                            + "|added|claims|claimed|reports|reported)\\s+" + npEnglish
                            + "\\s*(?=\\[|[,.;:!?)–—]|$)",
                    // wie der Verband betont, wie ein Anbieter angibt
                    "(?<![\\p{L}])[Ww]ie\\s+(?:es\\s+)?" + npSubject + "\\s+(?:" + ADV_DE + "\\s+)?(?:" + SAY_LAST_DE
                            + ")(?![\\p{L}])",
                    // as a vendor states
                    "(?<![\\p{L}])[Aa]s\\s+" + npEnglish + "\\s+(?:" + ADV_EN + "\\s+)?(?:" + SAY_EN + "|" + ASK_EN
                            + ")(?![\\p{L}])",
                    // heißt es beim Hersteller, heißt es aus der Branche
                    "(?<![\\p{L}])heißt\\s+es\\s+(?:beim|bei|vom|von|seitens|aus)\\s+(?:" + ART_DE + "\\s+)?(?:"
                            + ADJ_DE + "\\s+){0,3}" + speaker,
                    // aus Sicht des Verbands
                    "(?<![\\p{L}])[Aa]us\\s+(?:der\\s+)?Sicht\\s+" + OF_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3}"
                            + speaker,
                    // Dabei betont der Verband, dass …; not "empfiehlt dem Händler" (no subject)
                    "(?<![\\p{L}])(?:" + SAY_DE + ")\\s+" + SUBJECT_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3}" + speaker
                            + OPENS,
                    // Dabei gibt der Hersteller an, dass …; weist der Verband darauf hin, dass …
                    "(?<![\\p{L}])(?:gibt|geben|gab|gaben|teilt|teilen|teilte|hebt|heben|hob|weist|weisen|wies|führt"
                            + "|führen|stellt|stellen|geht|gehen)\\s+" + SUBJECT_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3}"
                            + speaker + "\\s+(?:an|mit|hervor|darauf\\s+hin|aus|fest|davon\\s+aus)" + OPENS,
                    // doch empfiehlt ein Verband mehr Zonen
                    "(?<![\\p{L}])(?:" + ASK_DE + ")\\s+" + SUBJECT_DE + "\\s+(?:" + ADJ_DE + "\\s+){0,3}" + speaker,
                    // Nach eigenen Angaben liefert der Hersteller …
                    "(?<![\\p{L}])(?:[Nn]ach\\s+eigenen\\s+Angaben|[Ee]igenen\\s+Angaben\\s+zufolge|[Nn]ach\\s+eigener"
                            + "\\s+(?:Aussage|Darstellung))\\s+\\p{Ll}+\\s+" + npSubject,
                    // per the manufacturer, per Sturzwacht; not "20 vehicles per company" (the review of 22.11.2026)
                    "(?<![\\p{L}])[Pp]er\\s+(?:(?iu:the|its|their)\\s+(?:" + ADJ_EN + "\\s+){0,2}" + speaker
                            + (name.isEmpty() ? "" : "|(?<![\\p{L}\\d-])" + name + "(?![\\p{L}\\d-])") + ")"};
            List<String> anywhereList = new ArrayList<String>(Arrays.asList(anywhereFrames));
            if (!capitals.isEmpty()) {
                // nach VDB-Angaben, laut ADAC-Website, VDB-Angaben zufolge: a name in capitals, only as written
                anywhereList.add("(?<![\\p{L}])(?:[Ll]aut|lt\\.|[Nn]ach|[Gg]emäß)\\s+(?:(?:den|der|dem)\\s+)?"
                        + "(?<![\\p{L}\\d-])" + capitals + "-(?:" + SAID_DE + "|" + SAID_IN_COMPOUND_DE + ")");
                anywhereList.add("(?<![\\p{L}\\d-])" + capitals + "-" + SAID_DE + "\\s+zufolge(?![\\p{L}])");
            }
            if (!name.isEmpty()) {
                // laut Sturzwachts Angaben, Sturzwachts Angaben zufolge
                anywhereList.add("(?<![\\p{L}])(?:[Ll]aut|lt\\.|[Nn]ach|[Gg]emäß)\\s+(?<![\\p{L}\\d-])" + name
                        + "(?:s|['’]s?)\\s+" + SAID_DE);
                anywhereList.add("(?<![\\p{L}\\d-])" + name + "(?:s|['’]s?)\\s+" + SAID_DE
                        + "\\s+zufolge(?![\\p{L}])");
            }
            String[] clauseFrames = {
                    // Ein großer Anbieter beschreibt, dass …; der Verband betont: …
                    npSubject + "\\s+(?:" + ADV_DE + "\\s+)?(?:" + SAY_DE + ")(?:\\s+" + ADV_DE + ")?" + OPENS,
                    // Der Verband empfiehlt mehr Zonen
                    npPlain + "\\s+(?:" + ADV_DE + "\\s+)?(?:" + ASK_DE + ")(?![\\p{L}])",
                    // Der Hersteller gibt eine Laufzeit von acht Stunden an
                    npSubject + "\\s+(?:gibt|geben|gab|gaben)\\s+(?:\\S+\\s+){0,8}?an(?=\\s*\\[|\\s*[,.;:!?]|\\s*$)",
                    // Der Hersteller liefert nach eigenen Angaben …
                    npSubject + "\\s+\\p{Ll}+\\s+(?:nach\\s+eigenen\\s+Angaben|eigenen\\s+Angaben\\s+zufolge)"
                            + "(?![\\p{L}])",
                    // The vendor says that …
                    npEnglish + "\\s+(?:" + ADV_EN + "\\s+)?(?:" + SAY_EN + ")(?:\\s+" + ADV_EN + ")?" + OPENS,
                    // The association recommends …
                    npEnglish + "\\s+(?:" + ADV_EN + "\\s+)?(?:" + ASK_EN + ")(?![\\p{L}])"};
            this.anywhere = Pattern.compile(String.join("|", wrap(anywhereList.toArray(new String[0]))));
            this.clause = Pattern.compile(String.join("|", wrap(clauseFrames)));
            // a speaker word, a compound with one ("Herstellerangaben"), a name of the source, also before a hyphen
            // or with a genitive s ("VDB-Angaben", "Sturzwachts")
            this.named = Pattern.compile(words(kind) + "|(?<![\\p{L}\\d])" + stem + "[\\p{L}-]{0,40}?"
                    + SAID_IN_COMPOUND_DE + (name.isEmpty() ? "" : "|(?<![\\p{L}\\d-])" + name
                    + "(?:s|['’]s?)?(?![\\p{L}\\d])"));
        }

        static Frames of(Kind kind, Collection<String> names) {
            String key = kind + "\u0000" + (names == null ? "" : String.join("\u0000", names));
            if (FRAMES.size() > 2000) {
                FRAMES.clear();
            }
            return FRAMES.computeIfAbsent(key, k -> new Frames(kind, names));
        }

        /** Whether the text holds a frame. */
        boolean in(String text) {
            return !spans(text).isEmpty();
        }

        /** Whether the text names a speaker word of the kind or the source anywhere, in a frame or not. */
        boolean names(String text) {
            return text != null && named.matcher(text).find();
        }

        /**
         * Every frame in the text, where it starts and ends, overlapping ones as one; none that a contrast before it
         * turns against the speaker ("Anders als die Hersteller behaupten").
         */
        List<int[]> spans(String text) {
            List<int[]> found = new ArrayList<int[]>();
            Matcher m = anywhere.matcher(text);
            while (m.find()) {
                if (m.end() > m.start()) {
                    found.add(new int[]{m.start(), m.end()});
                }
            }
            Matcher start = CLAUSE_START.matcher(text);
            Matcher c = clause.matcher(text);
            Matcher conjunction = CONJUNCTION.matcher(text);
            while (start.find()) {
                int at = start.end();
                List<Integer> from = new ArrayList<Integer>();
                from.add(at);
                conjunction.region(at, text.length());
                if (conjunction.lookingAt()) {
                    from.add(conjunction.end());
                }
                for (int position : from) {
                    c.region(position, text.length());
                    if (c.lookingAt() && c.end() > c.start()) {
                        found.add(new int[]{c.start(), c.end()});
                        break;
                    }
                }
                if (start.end() == start.start() && start.end() >= text.length()) {
                    break;
                }
            }
            found.sort(Comparator.comparingInt((int[] s) -> s[0]).thenComparingInt(s -> s[1]));
            List<int[]> spans = new ArrayList<int[]>();
            for (int[] span : found) {
                if (CONTRAST.matcher(text.substring(0, span[0])).find()) {
                    continue;
                }
                int[] last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
                if (last != null && span[0] < last[1]) {
                    last[1] = Math.max(last[1], span[1]);
                } else {
                    spans.add(new int[]{span[0], span[1]});
                }
            }
            return spans;
        }

        private static String[] wrap(String[] frames) {
            String[] out = new String[frames.length];
            for (int i = 0; i < frames.length; i++) {
                out[i] = "(?:" + frames[i] + ")";
            }
            return out;
        }

        /** A word of a speaker of the kind ("Hersteller", "Softwareanbieter", "Branchenkenner", "vendor"). */
        private static String words(Kind kind) {
            String german = "(?<![\\p{L}\\d])(?=\\p{Lu})[\\p{L}-]{0,40}?(?iu:" + kind.german + ")" + ENDINGS_DE
                    + "(?![\\p{L}\\d])";
            String industry = "(?<![\\p{L}\\d])Branchen\\p{Ll}[\\p{L}-]*";
            String english = "(?<![\\p{L}\\d-])(?iu:" + kind.english + ")(?:['’]s)?(?![\\p{L}\\d])";
            return "(?:" + german + "|" + industry + "|" + english + ")";
        }

        /**
         * A word of a speaker of the kind, also with the source's name after it ("der Hersteller Sturzwacht", "der
         * Verband VDB": the review of 22.11.2026), or a name of the source.
         */
        private static String speaker(Kind kind, Collection<String> names) {
            String core = nameCore(names);
            if (core.isEmpty()) {
                return words(kind);
            }
            String name = "(?<![\\p{L}\\d-])" + core + "(?![\\p{L}\\d-])";
            return "(?:" + words(kind) + "(?:\\s+" + name + ")?|" + name + ")";
        }

        /**
         * The source's names, without what may stand around them: a name of four characters or more in any case, a
         * shorter one only as written ("VDB", the source's own "GdP"), since "die" or "app" in a frame names no
         * source; {@code ""} for none.
         */
        private static String nameCore(Collection<String> names) {
            List<String> quoted = new ArrayList<String>();
            List<String> exact = new ArrayList<String>();
            for (String name : names == null ? Collections.<String>emptyList() : names) {
                String n = name == null ? "" : name.trim();
                if (n.length() <= 3 && !n.isEmpty()) {
                    exact.add(Pattern.quote(n));
                } else if (!n.isEmpty()) {
                    quoted.add(Pattern.quote(n.toLowerCase(Locale.ROOT)));
                }
            }
            List<String> parts = new ArrayList<String>();
            if (!quoted.isEmpty()) {
                parts.add("(?iu:" + String.join("|", quoted) + ")");
            }
            if (!exact.isEmpty()) {
                parts.add("(?:" + String.join("|", exact) + ")");
            }
            return parts.isEmpty() ? "" : "(?:" + String.join("|", parts) + ")";
        }

        /**
         * The source's names of two to four letters in capitals, as {@link #nameOf} writes them ("VDB", "ADAC"), and as
         * the source writes them ("BASt", "Bahn"), to be matched only so: "Blitzer-Angaben" are no source's statement;
         * {@code ""} for none.
         */
        private static String capitalsCore(Collection<String> names) {
            List<String> capitals = new ArrayList<String>();
            for (String name : names == null ? Collections.<String>emptyList() : names) {
                String n = name == null ? "" : name.trim();
                if (n.length() >= 2 && n.length() <= 4 && n.matches("\\p{L}+")) {
                    for (String form : new String[]{Pattern.quote(n.toUpperCase(Locale.ROOT)), Pattern.quote(n)}) {
                        if (!capitals.contains(form)) {
                            capitals.add(form);
                        }
                    }
                }
            }
            return capitals.isEmpty() ? "" : "(?:" + String.join("|", capitals) + ")";
        }
    }
}
