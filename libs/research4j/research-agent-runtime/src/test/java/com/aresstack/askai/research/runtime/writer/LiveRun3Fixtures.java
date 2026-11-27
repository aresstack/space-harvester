package com.aresstack.askai.research.runtime.writer;

/**
 * Real text from live run 3 ("Wearables auf Baustellen", 10.11.2026, writer llama3.1:8b): the cited passages and the
 * written sentences as the manuscript stored them ({@code manuscript/sections/<id>/0002.json}, field
 * {@code citedText}). Copied verbatim, soft hyphens removed; nothing is invented.
 */
final class LiveRun3Fixtures {

    private LiveRun3Fixtures() {
    }

    // ------------------------------------------------------------------ boilerplate the writer received as evidence

    /** hexagon.com footer navigation (source-12, cap-12#p101): every sentence of section 1.3 cited it. */
    static final String HEXAGON_NAVIGATION = "Steigern Sie Ihr Geschäft mit Überwachung Bauwerksüberwachung in "
            + "Echtzeit Referenzkundenberichte Fangen Sie an Vertriebsteam kontaktieren Alle Produkte durchsuchen "
            + "Globale Standorte Support erhalten Starten Sie Ihre Karriere Entdecken Sie Hexagon Wer wir sind "
            + "Führungsteam Nachhaltigkeit Investor Relations Erfahren Sie mehr Im Einsatz bei Kunden Nächste "
            + "Veranstaltungen Newsroom Ethik und Compliance Rechtliches Datenschutz Nutzungsbedingungen Impressum "
            + "Problem mit der Website melden Cookie-Einstellungen © 2026 Hexagon AB und/oder seine "
            + "Tochtergesellschaften. Alle Rechte vorbehalten. Deutsch";

    /** dormakaba breadcrumb and page title (source-10, cap-10#p0), cited in section 1.1. */
    static final String DORMAKABA_BREADCRUMB = "Architektur Sicherheit Gesellschaft Technologie Urbanisierung "
            + "dormakaba.com DE Sicherheit Intelligente Sicherheit zuerst (Teil 2): IoT und Wearables auf "
            + "vernetzten Baustellen";

    /** A heading (source-15, cap-15#p33), cited in section 1.4. */
    static final String HEADING_HURDLES = "HERAUSFORDERUNGEN BEI DER IMPLEMENTIERUNG: WO LIEGEN DIE HÜRDEN?";

    /** A heading pair (source-53, cap-58#p49), cited in section 1.4 for the cost analysis. */
    static final String HEADING_COSTS = "Analyse der Implementierungskosten Anfängliche Investitionsanforderungen";

    // ------------------------------------------------------------------ prose passages

    /** source-10, cap-10#p16. */
    static final String DORMAKABA_RFID = "Bauunternehmen können diese Wearables beispielsweise einsetzen, um die "
            + "Standorte von Mitarbeitern und Maschinen in Echtzeit zu überwachen. In Verbindung mit "
            + "RFID-Tagging-Systemen können die Geräte gefährliche Situationen erkennen, z. B. wenn Arbeiter "
            + "unbefugte Bereiche betreten oder schweren Maschinen zu nahe kommen.";

    /** source-14, cap-14#p110. */
    static final String BIBLUS_BENEFITS = "Die Hauptvorteile umfassen: höhere operative Effizienz und Verkürzung "
            + "der Produktionszeiten; Reduzierung kostspieliger Fehler durch präzise Planung; Erhöhung der Sicherheit "
            + "der Arbeiter durch Echtzeitüberwachung; Verbesserung der Transparenz und Zusammenarbeit zwischen den "
            + "Projektbeteiligten; höhere Umweltverträglichkeit durch Optimierung des Verbrauchs und der Ressourcen.";

    /** source-16, cap-16#p165. */
    static final String WLW_ARBSCHG = "Professioneller Hinweis: Baustellenüberwachungssysteme müssen gemäß "
            + "Arbeitsschutzgesetz (ArbSchG) und DGUV Vorschrift 1 so platziert werden, dass sie sowohl "
            + "Gefahrenzonen als auch Zugänge überwachen, ohne Mitarbeiter zu filmen, wenn dies nicht erforderlich "
            + "ist. Bei Montagearbeiten in Höhen sollte die Kameraüberwachung mit Sensoren zur Erkennung von "
            + "Sturzrisiken kombiniert werden.";

    /** source-12, cap-12#p15: the shortest prose passage of the run (12 words, one sentence). */
    static final String HEXAGON_GNSS = "GNSS-Systeme verbinden Hardware und Software, um präzise und genaue "
            + "Positionierungsaufgaben zu ermöglichen.";

    /** source-1, cap-1#p10. */
    static final String LATTICEWORK_SENSORS = "Die visuelle KI von VAISense basiert auf einer Kombination aus "
            + "IoT-Sensoren, Nachtsicht-, Infrarot- und Wärmebildkameras. Diese Systeme können Baustellen rund um die "
            + "Uhr automatisch überwachen und bieten einen umfassenden Überblick über alle Aktivitäten und "
            + "potenziellen Sicherheitsrisiken. Dadurch, dass die Abhängigkeit von Sicherheitsteams vor Ort entfällt, "
            + "können Bauunternehmen einen kontinuierlichen Überwachungsüberblick gewinnen und die Sicherheit auf der "
            + "Baustelle insgesamt verbessern.";

    /** source-38, cap-39#p16. */
    static final String SMARTWATCH_SIMPSON = "Simpson ergänzt, dass die Daten der meisten Fitness Armbänder zudem "
            + "eher unzuverlässig und letzten Endes nur grobe Richtlinien seien. Auf ihrer Basis könnten Arbeitgeber "
            + "keine sinnvollen Entscheidungen treffen. Man könnte den Mitarbeitern Wearables natürlich auf "
            + "freiwilliger Basis anbieten. Das sei jedoch ein sensibles Thema, denn schnell könnte "
            + "Konformitätsdruck entstehen.";

    /** source-53, cap-58#p36. */
    static final String SINOTRACK_INSURANCE = "Die Versicherungsprämien sinken häufig, wenn Unternehmen durch eine "
            + "umfassende Implementierung von Trackingsystemen ein proaktives Risikomanagement nachweisen. Viele "
            + "Versicherungsanbieter gewähren Rabatte für Flotten, die mit Überwachungstechnologie ausgestattet "
            + "sind, da sie den Zusammenhang zwischen Trackingsystemen und einer geringeren Schadenshäufigkeit "
            + "erkennen. Die Möglichkeit, gestohlene Vermögenswerte schnell wiederzufinden und detaillierte "
            + "Vorfallsdokumentationen bereitzustellen, verbessert zudem das Risikoprofil überwachter Flotten.";

    /** source-6, cap-6#p4 (BayLfD). */
    static final String BAYLFD_DSK = "Die Datenschutzkonferenz tritt für einen effektiven Schutz der "
            + "Persönlichkeitsrechte der Nutzerinnen und Nutzer von Wearables und Gesundheits-Apps ein. Einer "
            + "repräsentativen Umfrage zufolge soll bereits knapp ein Drittel der Bevölkerung ab 14 Jahren "
            + "sogenannte Fitness-Tracker zur Aufzeichnung von Gesundheitswerten und persönlichen Verhaltensweisen "
            + "nutzen. Am Körper getragene Kleincomputer (sog. Wearables) und auf mobilen Endgeräten installierte "
            + "Anwendungsprogramme (sog. Gesundheits-Apps) sammeln und dokumentieren auswertungsfähige Körperdaten. "
            + "In der Regel werden diese Daten über das Internet an Hersteller, Internetanbieter und sonstige Dritte "
            + "weitergeleitet.";

    /** source-18, cap-18#p40 (Urmet radio alarms; prose, but off-topic for section 1.4). */
    static final String URMET_ALARMS = "Die Installation und Bedienung dieser Funkalarmanlagen gestalten sich "
            + "einfach und unkompliziert. Dank ihres drahtlosen Designs können sie innerhalb kürzester Zeit "
            + "einsatzbereit gemacht werden, ohne dass aufwändige Verkabelungen erforderlich sind. Ist die Anlage "
            + "einmal vom Elektroinstallateur konfiguriert, erfolgt die Bedienung intuitiv über eine "
            + "benutzerfreundliche Oberfläche, die es dem Baustellenpersonal ermöglicht, das Sicherheitssystem "
            + "effektiv und einfach zu verwalten und zu überwachen.";

    /** source-18, cap-18#p10 (Urmet). */
    static final String URMET_WIRELESS = "1. Drahtlose Installation und Flexibilität: Funk-Alarmanlagen sind einfach "
            + "zu installieren und benötigen keine festen Verkabelungen. Das macht sie ideal für Baustellen, wo "
            + "Bedingungen oft wechseln und Anlagen regelmäßig umgesetzt oder angepasst werden müssen.";

    /** source-47, cap-50#p12 (sifa-sibe.de), soft hyphens removed. */
    static final String SIFA_DEFINITION = "Als Wearables werden mobile Kleincomputer bezeichnet, die von "
            + "Beschäftigten am Körper getragen werden. Zu ihnen zählen beispielsweise Bodycams, Smartphones, Smart "
            + "Watches, Smart Glasses („Datenbrillen“), Smart Hands („vernetzte Handschuhe“) oder Fitnessarmbänder. "
            + "Sie werden zur Unterstützung der Beschäftigten bei der Arbeitsleistung beziehungsweise zur "
            + "Optimierung von betrieblichen Arbeitsprozessen eingesetzt, bisweilen aber auch als „Fitness-Tracker“ "
            + "für betriebliche Gesundheitsprogramme. Es gibt auch die sogenannte Exoskelette („Maschinen zum "
            + "Anziehen“), die Arbeitnehmer bei der Hebung von Lasten unterstützen oder die Arbeitsschritte zum "
            + "Beispiel am Fließband beschleunigen. Diese gehören per Definition nicht zu den Wearables, erfüllen "
            + "aber vergleichbare Funktionen, weil auch sie ihren Träger bei vor allem physisch belastenden Arbeiten "
            + "unterstützen sollen.";

    /** source-1, cap-1#p1 (LatticeWork). */
    static final String LATTICEWORK_RISKS = "Baustellen sind für ihre inhärenten Risiken und Herausforderungen "
            + "bekannt, wobei Sicherheit und Schutz für Bauunternehmen oberste Priorität haben. Die Notwendigkeit, "
            + "Unfälle, Diebstahl und Vandalismus einzudämmen, hat dazu geführt, dass bei der Baustellenüberwachung "
            + "fortschrittliche Technologien wie künstliche Intelligenz (KI) und visuelle Sensorik eingesetzt werden. "
            + "KI kann die Sicherheit auf Baustellen revolutionieren, indem sie Echtzeitüberwachung, vorausschauende "
            + "Erkenntnisse und verbesserte Sicherheitsmaßnahmen ermöglicht.";

    /** source-10, cap-10#p10 (dormakaba; the typos are the page's). */
    static final String DORMAKABA_BAU40 = "Die Umwandlung von Baustellen in vernetzte Umgebungen stellt die nächste "
            + "Revulution im so genannten „Bau 4.0“ dar. Dieser Wandel beinhaltet die Einführung von Technulogien wie "
            + "Building Information Modeling (BIM), künstliche Intelligenz (AI), Big Data, Augmented Reality und "
            + "digitale Zwillinge. Diese Touls verbessern nicht nur die Sicherheit durch vorausschauende Analysen und "
            + "Simulationen, sondern umfassen auch Vor-Ort-Geräte zur Risikominderung durch Echtzeitanalysen.";

    /** source-12, cap-12#p11 (Hexagon advertising copy). */
    static final String HEXAGON_SOLUTIONS = "Projekte zur Überwachung von Strukturen und Baustellen erfordern "
            + "maßgeschneiderte Lösungen. Bei Hexagon arbeiten unsere Software und unsere fachkundigen "
            + "Dienstleistungen reibungslos mit unseren High-End-Sensoren und geotechnischen Geräten von "
            + "Drittanbietern zusammen, um vollständig integrierte Lösungen anzubieten, die auch bei den größten "
            + "Projekten für Sicherheit sorgen.";

    // ------------------------------------------------------------------ the written sections (revision 2)

    /** Section 1.1 as written (verbatim paragraphs, two left out); markers refer to {@link #SECTION_1_1_PASSAGES}. */
    static final String SECTION_1_1 = "Die Technologien und Sensortypen, die in Wearables auf Baustellen zum Einsatz "
            + "kommen, sind ein zentraler Bestandteil der modernen Baustellenüberwachung [3]. Diese Technologien "
            + "ermöglichen eine fortschrittliche Datenerfassung, die über traditionelle Methoden hinausgeht [1]. "
            + "Smartwatches und Sensorarmbänder spielen eine Rolle bei der Datenerfassung auf Baustellen [3]. Diese "
            + "Wearables können verschiedene Daten erfassen, die für die Sicherheitsbewertung relevant sind, wie "
            + "beispielsweise Bewegung oder Temperatur [1].\n\n"
            + "Die Datenerfassung durch Sensoren ermöglicht eine umfassende Sicht auf die Baustelle [1]. Systeme, die "
            + "auf der visuellen KI basieren, nutzen eine Kombination aus IoT-Sensoren, Nachtsicht-, Infrarot- und "
            + "Wärmebildkameras, um Baustellen rund um die Uhr automatisch zu überwachen [1]. Diese Systeme bieten "
            + "einen umfassenden Überblick über alle Aktivitäten und mögliche Sicherheitsrisiken [1].\n\n"
            + "Die Umwandlung von Baustellen in vernetzte Umgebungen ist ein Teil der Revolution im sogenannten "
            + "„Bau 4.0“ [4]. Dieser Wandel beinhaltet die Einführung verschiedener Technologien wie Building "
            + "Information Modeling (BIM), künstliche Intelligenz (AI), Big Data, Augmented Reality und digitale "
            + "Zwillinge [4].\n\n"
            + "GNSS-Systeme sind ein Beispiel für die Verbindung von Hardware und Software, indem sie präzise und "
            + "genaue Positionierungsaufgaben ermöglichen [8].\n\n"
            + "Die Notwendigkeit, Unfälle, Diebstahl und Vandalismus auf Baustellen einzudämmen, hat zur Anwendung "
            + "fortschrittlicher Technologien wie künstliche Intelligenz (KI) und visueller Sensorik geführt [2]. KI "
            + "hat das Potenzial, die Sicherheit auf Baustellen zu revolutionieren, indem sie Echtzeitüberwachung, "
            + "vorausschauende Erkenntnisse und verbesserte Sicherheitsmaßnahmen ermöglicht [2].\n\n"
            + "Projekte zur Überwachung von Strukturen und Baustellen erfordern maßgeschneiderte Lösungen [7].";

    static final String[] SECTION_1_1_PASSAGES = {LATTICEWORK_SENSORS, LATTICEWORK_RISKS, DORMAKABA_BREADCRUMB,
            DORMAKABA_BAU40, "", "", HEXAGON_SOLUTIONS, HEXAGON_GNSS};

    /** Section 1.2 as written. */
    static final String SECTION_1_2 = "Bauunternehmen können diese Gefährdungen mit Hilfe von Wearables "
            + "reduzieren [1]. Diese Wearables ermöglichen eine Echtzeitüberwachung der Standorte von Mitarbeitern und "
            + "Maschinen auf der Baustelle [1]. In Verbindung mit RFID-Tagging-Systemen können die Geräte gefährliche "
            + "Situationen erkennen, zum Beispiel, wenn Arbeiter unbefugte Bereiche betreten oder schweren Maschinen "
            + "zu nahe kommen [1].\n\n"
            + "Die Hauptvorteile der Nutzung von Wearables auf Baustellen umfassen die höhere operative Effizienz und "
            + "die Verkürzung der Produktionszeiten [4]. Zudem wird eine Reduzierung kostspieliger Fehler durch "
            + "präzise Planung ermöglicht [4]. Die Echtzeitüberwachung erhöht die Sicherheit der Arbeiter [4]. Die "
            + "Technologien verbessern auch die Transparenz und die Zusammenarbeit zwischen allen am Bauprozess "
            + "Beteiligten [4]. Darüber hinaus wird eine höhere Umweltverträglichkeit durch die Optimierung des "
            + "Verbrauchs und der Ressourcen erreicht [4].\n\n"
            + "Baustellenüberwachungssysteme müssen gemäß Arbeitsschutzgesetz (ArbSchG) und DGUV Vorschrift 1 so "
            + "platziert werden, dass sie sowohl Gefahrenzonen als auch Zugänge überwachen, ohne Mitarbeiter zu "
            + "filmen, wenn dies nicht erforderlich ist [6]. Bei Montagearbeiten in Höhen sollte die "
            + "Kameraüberwachung mit Sensoren zur Erkennung von Sturzrisiken kombiniert werden [6].";

    static final String[] SECTION_1_2_PASSAGES = {DORMAKABA_RFID, "", "", BIBLUS_BENEFITS, "", WLW_ARBSCHG};

    /** Section 1.3 as written: every sentence cites the hexagon.com footer [6]. */
    static final String SECTION_1_3 = "Die rechtlichen Rahmenbedingungen für die Erfassung von Mitarbeiterdaten "
            + "durch Wearables auf Baustellen sind eng mit dem Datenschutz und den Rechten der Arbeitnehmer verbunden "
            + "[6]. Die Verarbeitung von Daten, die durch Wearables erfasst werden, unterliegt den Vorschriften der "
            + "Datenschutz-Grundverordnung (DSGVO) und des Bundesdatenschutzgesetzes (BDSG) [6]. Diese Gesetze legen "
            + "fest, wie personenbezogene Daten zu erheben, zu speichern und zu verarbeiten sind [6].\n\n"
            + "Die Rechte der Arbeitnehmer sind im Kontext der Datenerhebung besonders zu beachten [6]. Arbeitnehmer "
            + "haben bestimmte Informationspflichten bezüglich der Überwachung und Datennutzung, die im Rahmen des "
            + "Arbeitsrechts geregelt sind [6]. Dies betrifft insbesondere die Erfassung von Daten über die "
            + "Arbeitsleistung und die persönliche Situation der Mitarbeiter [6].\n\n"
            + "Die Rolle des Betriebsrats bei der Implementierung von Technologien wie Wearables ist relevant, "
            + "insbesondere wenn diese die Arbeitsbedingungen und den Datenschutz der Mitarbeiter beeinflussen [6]. "
            + "Konflikte können entstehen, wenn die technischen Anforderungen der Überwachung mit den "
            + "Datenschutzbestimmungen kollidieren [6]. Die rechtlichen Anforderungen an die Datenspeicherung und "
            + "-verarbeitung müssen strikt eingehalten werden [6].\n\n"
            + "Die Spezifikation der Datensicherheit und der Transparenz bei der Nutzung von Wearables ist ein "
            + "zentraler Aspekt der rechtlichen Anforderungen [6]. Die Einhaltung dieser Anforderungen ist notwendig, "
            + "um rechtliche Probleme zu vermeiden [6].";

    static final String[] SECTION_1_3_PASSAGES = {"", "", "", "", "", HEXAGON_NAVIGATION};

    /** Section 1.4 as written. */
    static final String SECTION_1_4 = "Die Implementierung von Wearables auf Baustellen wirft verschiedene Fragen "
            + "hinsichtlich Kosten, technischer Grenzen und geeigneter Strategien auf [1]. Die Kostenanalyse umfasst "
            + "die Anschaffung von Hardware, Software und die Installation der Systeme [6]. Die Installation und "
            + "Bedienung von Funkalarmanlagen, die eine drahtlose Installation ermöglichen, sind einfach und "
            + "unkompliziert, da sie keine festen Verkabelungen erfordern [2], [3]. Die Konfiguration der "
            + "Funkalarmanlagen erfolgt einmalig durch den Elektroinstallateur, und die Bedienung ist über eine "
            + "benutzerfreundliche Oberfläche intuitiv, was dem Baustellenpersonal eine effektive Verwaltung und "
            + "Überwachung des Sicherheitssystems ermöglicht [3].\n\n"
            + "Simpson merkt an, dass die Daten der meisten Fitness Armbänder eher unzuverlässig seien und letztendlich "
            + "nur grobe Richtlinien liefern, sodass Arbeitgeber keine sinnvollen Entscheidungen treffen könnten [4]. "
            + "Dennoch können Wearables zur Selbstüberwachung und Selbstvermessung körperlicher und sportlicher "
            + "Aktivitäten genutzt werden, da die Digitalisierung die Integration in den Alltag erleichtert [7].\n\n"
            + "Durch die Implementierung von Trackingsystemen können Unternehmen proaktives Risikomanagement "
            + "nachweisen, was zu sinkenden Versicherungsprämien führen kann, da Versicherungsanbieter Rabatte für "
            + "Flotten gewähren, die mit Überwachungstechnologie ausgestattet sind [5]. Die Möglichkeit, gestohlene "
            + "Vermögenswerte schnell wiederzufinden und detaillierte Vorfallsdokumentationen bereitzustellen, "
            + "verbessert das Risikoprofil überwachter Flotten [5].";

    static final String[] SECTION_1_4_PASSAGES = {HEADING_HURDLES, URMET_WIRELESS, URMET_ALARMS, SMARTWATCH_SIMPSON,
            SINOTRACK_INSURANCE, HEADING_COSTS, SIFA_DEFINITION, BAYLFD_DSK};

    static final String[] BOILERPLATE = {HEXAGON_NAVIGATION, DORMAKABA_BREADCRUMB, HEADING_HURDLES, HEADING_COSTS};

    static final String[] PROSE = {DORMAKABA_RFID, BIBLUS_BENEFITS, WLW_ARBSCHG, HEXAGON_GNSS, LATTICEWORK_SENSORS,
            SMARTWATCH_SIMPSON, SINOTRACK_INSURANCE, BAYLFD_DSK, URMET_ALARMS};
}
