package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.FakeModelPort;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.team.ModelCallMetrics;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SectionWriterTest {

    /** A scripted transport that records every request. */
    static final class ScriptedChat implements MainModelChat {
        final Deque<MainModelChatResult> answers = new ArrayDeque<MainModelChatResult>();
        final List<List<ChatMessage>> requests = new ArrayList<List<ChatMessage>>();
        final List<Integer> budgets = new ArrayList<Integer>();

        ScriptedChat answer(String text) {
            answers.add(MainModelChatResult.ok(text, ModelCallMetrics.builder().modelLabel("gemma4:e2b")
                    .doneReason("stop").build()));
            return this;
        }

        ScriptedChat cutOff(String text) {
            answers.add(MainModelChatResult.ok(text, ModelCallMetrics.builder().modelLabel("gemma4:e2b")
                    .doneReason("length").build()));
            return this;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            requests.add(new ArrayList<ChatMessage>(messages));
            budgets.add(maxOutputTokens);
            return answers.isEmpty() ? MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no scripted answer") : answers.poll();
        }

        public String modelName() {
            return "slot phase.draft.writer";
        }
    }

    static List<EvidencePassage> pack(int k) {
        List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
        for (int i = 1; i <= k; i++) {
            passages.add(new EvidencePassage(i, "src-" + i, "sha-" + i, "p-" + i,
                    "Passage " + i + " sagt: Die Rate stieg auf 15,5 % im Jahr 2020 weiter, danach kam ein "
                            + "Rückgang (Teil eins und Teil zwei)."));
        }
        return passages;
    }

    static SectionAssignment assignment(int k) {
        return SectionAssignment.builder("s1").title("Rates").brief("How the rate developed")
                .keyPoints(Arrays.asList("growth")).passages(pack(k)).bookLanguage("German")
                .wordsPerCall(450).answerTokens(1200).build();
    }

    @Test
    public void unknownMarkerIsStrippedAndLogged() {
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg auf 15,5 % [1][9].");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(6));
        assertTrue(draft.isOk());
        assertEquals(Arrays.asList("[9]"), draft.getStrippedMarkers());
        assertFalse(draft.getText().contains("[9]"));
        assertTrue(draft.getText().contains("[1]"));
        assertTrue(log.toString(), log.toString().contains("stripped unknown marker [9]"));
        assertEquals(1, chat.requests.size());
        assertEquals("sha-1", draft.getCitations().get(1).getSourceChecksum());
    }

    @Test
    public void replyWithoutCitationsGetsOneRepairWithoutTheFailedAnswerAndIsStoredFlagged() {
        String failed = "Die Rate stieg deutlich an. Das war wichtig.";
        ScriptedChat chat = new ScriptedChat().answer(failed).answer("Die Rate stieg weiter an. Ohne Beleg.");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> repair = chat.requests.get(1);
        for (ChatMessage m : repair) {
            assertFalse("the failed answer is never sent back", m.getRole() == ChatMessage.Role.ASSISTANT);
        }
        String last = repair.get(repair.size() - 1).getContent();
        assertTrue(last.contains("uncited sentence"));
        assertTrue(draft.isOk());
        assertEquals(2, draft.flagsOf(ClaimFlag.Kind.UNCITED_SENTENCE).size());
        assertEquals(1, draft.flagsOf(ClaimFlag.Kind.UNCITED_PARAGRAPH).size());
        assertTrue(draft.getText().contains("weiter"));
    }

    @Test
    public void cutOffAnswerGetsExactlyOneContinuationWithoutDuplicatedSentence() {
        ScriptedChat chat = new ScriptedChat()
                .cutOff("Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 war das so [2]. Danach kam ein")
                .answer("Im Jahr 2020 war das so [2]. Danach kam ein Rückgang [3].");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> continuation = chat.requests.get(1);
        List<ChatMessage> first = chat.requests.get(0);
        assertEquals(first.size() + 2, continuation.size());
        assertEquals("Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 war das so [2].",
                continuation.get(first.size()).getContent());
        assertEquals("Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 war das so [2]. Danach kam ein Rückgang [3].",
                draft.getText());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    @Test
    public void cutOffContinuationIsNotRepeated() {
        ScriptedChat chat = new ScriptedChat().cutOff("Die Rate stieg [1]. Und").cutOff("Weiter [2]. Und")
                .answer("never asked");
        new SectionWriter(chat, null).write(assignment(6));
        assertEquals(2, chat.requests.size());
    }

    @Test
    public void promptNamesBookLanguageAtStartAndEndAndFitsSmallPreset() {
        SectionAssignment a = SectionAssignment.builder("s1").title("Titel").brief(repeat("Brief ", 40))
                .keyPoints(Arrays.asList("a", "b", "c")).passages(longPack(6, 250))
                .synopsis(repeat("Synopsis wort ", 200)).previousDigest(repeat("Digest wort ", 200))
                .glossary(Arrays.asList(repeat("Begriff ", 100).split(" "))).bookLanguage("German")
                .wordsPerCall(450).answerTokens(1200).build();
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        String user = first.get(1).getContent();
        assertTrue(user.startsWith("Book language: German."));
        assertTrue(user.endsWith("Write in German."));
        assertTrue("small preset: " + SectionWriterPrompt.estimatedTokens(first),
                SectionWriterPrompt.estimatedTokens(first) <= 3400);
        SectionAssignment large = SectionAssignment.builder("s1").title("Titel").brief(repeat("Brief ", 40))
                .keyPoints(Arrays.asList("a", "b", "c")).passages(longPack(8, 300))
                .synopsis(repeat("Synopsis wort ", 200)).previousDigest(repeat("Digest wort ", 200))
                .bookLanguage("German").wordsPerCall(1100).answerTokens(3072).build();
        assertTrue("large preset: " + SectionWriterPrompt.estimatedTokens(SectionWriterPrompt.first(large)),
                SectionWriterPrompt.estimatedTokens(SectionWriterPrompt.first(large)) <= 4300);
    }

    @Test
    public void continuationAddsOnlyTheCutOffAnswer() {
        SectionAssignment a = assignment(6);
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        List<ChatMessage> next = SectionWriterPrompt.continuation(a, "Abc [1].");
        assertEquals(first.get(1).getContent(), next.get(1).getContent());
        assertEquals("Abc [1].", next.get(2).getContent());
        assertTrue(SectionWriterPrompt.estimatedTokens(next) - SectionWriterPrompt.estimatedTokens(first) < 100);
    }

    @Test
    public void rewriteSendsCommentAndCurrentTextButContinuationOmitsTheOldText() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T").passages(pack(6))
                .rewriteComment("kürzer").currentText("Alter Text des Teils [1].").bookLanguage("German").build();
        ScriptedChat chat = new ScriptedChat().cutOff("Neu [1]. Abgeschn").answer("Weiter [2].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertTrue(chat.requests.get(0).get(1).getContent().contains("Alter Text des Teils."));
        assertFalse("the old text's markers numbered an earlier pack",
                chat.requests.get(0).get(1).getContent().contains("Teils [1]"));
        assertTrue(chat.requests.get(0).get(1).getContent().contains("kürzer"));
        assertFalse(chat.requests.get(1).get(1).getContent().contains("Alter Text des Teils"));
        assertTrue(chat.requests.get(1).get(1).getContent().contains("kürzer"));
        assertEquals("Neu [1]. Weiter [2].", draft.getText());
    }

    /** Live run 4: every part of a rewrite got the whole old section and copied sentences of the other parts. */
    @Test
    public void eachPartOfARewriteGetsItsShareOfTheCurrentText() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T")
                .keyPoints(Arrays.asList("k1", "k2", "k3", "k4")).passages(pack(6)).wordsPerCall(450)
                .sectionWords(900).answerTokens(1200)
                .currentText("Erster alter Satz des Abschnitts [1]. Zweiter alter Satz des Abschnitts [2].\n\n"
                        + "Dritter alter Satz des Abschnitts [3]. Vierter alter Satz des Abschnitts [4].")
                .build();
        ScriptedChat chat = new ScriptedChat().answer("Teil eins [1].").answer("Teil zwei [2].");
        new SectionWriter(chat, null).write(a);
        String first = chat.requests.get(0).get(1).getContent();
        String second = chat.requests.get(1).get(1).getContent();
        assertTrue(first, first.contains("CURRENT TEXT OF THIS PART"));
        assertTrue(first.contains("Erster alter Satz des Abschnitts. Zweiter alter Satz des Abschnitts."));
        assertFalse(first.contains("Dritter alter Satz"));
        assertTrue(second.contains("Dritter alter Satz des Abschnitts. Vierter alter Satz des Abschnitts."));
        assertFalse(second.contains("Zweiter alter Satz"));
    }

    @Test
    public void theCurrentTextIsSharedInOrderAndCappedAtThePartTarget() {
        String text = "Eins zwei drei vier [1]. Fünf sechs sieben acht.\n\n"
                + "Neun zehn elf zwölf [2]. Dreizehn vierzehn fünfzehn sechzehn.";
        assertEquals(Arrays.asList("Eins zwei drei vier [1]. Fünf sechs sieben acht.",
                "Neun zehn elf zwölf [2]. Dreizehn vierzehn fünfzehn sechzehn."),
                SectionWriter.currentTextShares(text, 2, 50));
        assertEquals("paragraph breaks stay within a share",
                Arrays.asList("Eins zwei drei vier [1]. Fünf sechs sieben acht.\n\nNeun zehn elf zwölf [2]. "
                        + "Dreizehn vierzehn fünfzehn sechzehn."), SectionWriter.currentTextShares(text, 1, 50));
        assertEquals("no sentence beyond the part's target",
                Arrays.asList("Eins zwei drei vier [1].", "Neun zehn elf zwölf [2]."),
                SectionWriter.currentTextShares(text, 2, 6));
        assertEquals("a first draft has empty shares", Arrays.asList("", ""),
                SectionWriter.currentTextShares("", 2, 50));
        assertEquals("more parts than sentences: a sentence goes to the part its middle lies in, others get none",
                Arrays.asList("", "Eins zwei drei vier.", ""),
                SectionWriter.currentTextShares("Eins zwei drei vier.", 3, 50));
    }

    /**
     * Live run 4, book 6: "Wearables wie smarte Helme, Sicherheitswesten und Handschuhe … [2]" stood four times in
     * sections 1.2 and 1.3. The writer now checks every answer against the other written sections of the book.
     */
    @Test
    public void aSentenceAnotherSectionAlreadySaysLeavesTheSection() {
        String other = "Wearables wie smarte Helme, Sicherheitswesten und Handschuhe sind mit Sensoren ausgestattet, "
                + "die die Vitalwerte und Bewegungen der Arbeiter überwachen [4]. Die Rate stieg auf 15,5 % [2].";
        SectionAssignment a = assignment(6).toBuilder().writtenSections(Arrays.asList(other)).build();
        ScriptedChat chat = new ScriptedChat().answer("Wearables wie smarte Helme, Sicherheitswesten und Handschuhe "
                + "sind mit Sensoren ausgestattet, die die Vitalwerte und Bewegungen der Arbeiter laufend überwachen "
                + "[1]. Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 kam ein Rückgang [2].");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(a);
        assertEquals("a near repeat and an exact (short) repeat of the other section leave",
                "Im Jahr 2020 kam ein Rückgang [2].", draft.getText());
        assertTrue(log.toString(), log.toString().contains("repeat check against 2 sentences of 1 other written"));
        assertTrue(log.toString(), log.toString().contains("2 repeated sentences"));
    }

    @Test
    public void laterPartsAreCheckedAgainstTheBookAndTheEarlierParts() {
        String other = "Bei einem Sturz oder ungewöhnlichen Gesundheitswerten sendet das Wearable sofort einen Alarm "
                + "an die Bauleitung und Rettungsteams [3].";
        List<EvidencePassage> pack = new ArrayList<EvidencePassage>();
        for (int i = 1; i <= 6; i++) {
            pack.add(new EvidencePassage(i, "src-" + i, "sha-" + i, "p-" + i, "Exoskelette entlasten nachweislich "
                    + "den Rücken beim Heben schwerer Lasten auf der Baustelle und auf Baustellen. Bei einem Sturz "
                    + "oder ungewöhnlichen Gesundheitswerten sendet das Wearable sofort einen Alarm an die "
                    + "Bauleitung, die dann eingreift. Die Rate stieg auf 15,5 %."));
        }
        SectionAssignment a = SectionAssignment.builder("s1").title("T")
                .keyPoints(Arrays.asList("k1", "k2", "k3", "k4")).passages(pack).wordsPerCall(450)
                .sectionWords(900).answerTokens(1200).writtenSections(Arrays.asList(other))
                .repeats(new RepeatCheck.Criteria(6, 0.75)).build();
        ScriptedChat chat = new ScriptedChat()
                .answer("Exoskelette entlasten den Rücken beim Heben schwerer Lasten auf der Baustelle [1].")
                .answer("Exoskelette entlasten nachweislich den Rücken beim Heben schwerer Lasten auf Baustellen [2]. "
                        + "Bei einem Sturz oder ungewöhnlichen Gesundheitswerten sendet das Wearable sofort einen "
                        + "Alarm an die Bauleitung, die dann eingreift [2]. Die Rate stieg auf 15,5 % [2].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertEquals("Exoskelette entlasten den Rücken beim Heben schwerer Lasten auf der Baustelle [1].\n\n"
                + "Die Rate stieg auf 15,5 % [2].", draft.getText());
    }

    /** Live run 4, book 6: nearly every paragraph retold one passage; the prompt now asks for synthesis. */
    @Test
    public void thePromptAsksEachPartToCombineSourcesAndNamesTheSourceOfEachPassage() {
        List<EvidencePassage> pack = Arrays.asList(
                new EvidencePassage(1, "dguv", "c1", "p1", "Helme mit Sensoren melden Stürze."),
                new EvidencePassage(2, "baua", "c2", "p2", "Die Gefährdungsbeurteilung nennt Sturzgefahren."),
                new EvidencePassage(3, "dguv", "c1", "p3", "Sensoren erfassen auch Hitze."));
        SectionAssignment a = assignment(1).toBuilder().passages(pack)
                .synthesis(new SynthesisCriteria(3, 2, 2, 0.5)).build();
        String prompt = SectionWriterPrompt.userPrompt(a);
        assertTrue(prompt, prompt.contains("SYNTHESIS: write at least 2 paragraphs that each combine passages of at "
                + "least 2 different sources"));
        assertTrue("capped at the sources the part has", prompt.contains("[1] \"\"\"Helme mit Sensoren melden "
                + "Stürze.\"\"\" (source A)"));
        assertTrue(prompt.contains("(source B)"));
        assertTrue(prompt.contains("[3] \"\"\"Sensoren erfassen auch Hitze.\"\"\" (source A)"));
        assertTrue("the repair carries it too", SectionWriterPrompt.repair(a, new ArrayList<ClaimFlag>()).get(1)
                .getContent().contains("SYNTHESIS:"));

        SectionAssignment oneSource = assignment(3);
        assertFalse("one source: nothing to combine, no letters",
                SectionWriterPrompt.userPrompt(oneSource.toBuilder().passages(Arrays.asList(pack.get(0),
                        pack.get(2))).build()).contains("SYNTHESIS"));
        assertFalse("0 paragraphs per part leaves it out", SectionWriterPrompt.userPrompt(a.toBuilder()
                .synthesis(new SynthesisCriteria(2, 0, 2, 0.5)).build()).contains("SYNTHESIS"));
        assertEquals("AA", SectionWriterPrompt.sourceLabels(manySources(27)).get("s26"));
    }

    @Test
    public void aSourceWithANameIsCalledByItAndOnlyTheOthersGetLetters() {
        List<EvidencePassage> pack = Arrays.asList(
                new EvidencePassage(1, "dguv", "c1", "p1", "Helme mit Sensoren melden Stürze."),
                new EvidencePassage(2, "baua", "c2", "p2", "Die Gefährdungsbeurteilung nennt Sturzgefahren."),
                new EvidencePassage(3, "firma", "c3", "p3", "Sensoren erfassen auch Hitze."));
        java.util.Map<String, String> names = new java.util.LinkedHashMap<String, String>();
        names.put("baua", "baua.example");
        names.put("firma", " ");
        SectionAssignment a = assignment(1).toBuilder().passages(pack).sourceNames(names).build();
        assertEquals("a blank name is none", java.util.Collections.singletonMap("baua", "baua.example"),
                a.getSourceNames());
        String prompt = SectionWriterPrompt.userPrompt(a);
        assertTrue(prompt, prompt.contains("[1] \"\"\"Helme mit Sensoren melden Stürze.\"\"\" (source A)"));
        assertTrue(prompt, prompt.contains("[2] \"\"\"Die Gefährdungsbeurteilung nennt Sturzgefahren.\"\"\" (source "
                + "baua.example)"));
        assertTrue("the letters count only the sources without a name", prompt.contains("[3] \"\"\"Sensoren "
                + "erfassen auch Hitze.\"\"\" (source B)"));
        assertEquals(a.getSourceNames(), a.toBuilder().build().getSourceNames());
        assertTrue(assignment(1).getSourceNames().isEmpty());
    }

    /** Two pages of one site share its name, so the writer cannot tell them apart: they count as one source. */
    @Test
    public void sourcesThatShareANameCountAsOneForTheSynthesis() {
        List<EvidencePassage> pack = new ArrayList<EvidencePassage>(Arrays.asList(
                new EvidencePassage(1, "seite-1", "c1", "p1", "Die Ampel schaltet nach 40 Sekunden um."),
                new EvidencePassage(2, "seite-2", "c2", "p2", "Die Ampel zeigt Radfahrern ein eigenes Grün.")));
        java.util.Map<String, String> names = new java.util.LinkedHashMap<String, String>();
        names.put("seite-1", "verkehr.example");
        names.put("seite-2", "verkehr.example");
        SectionAssignment a = assignment(1).toBuilder().passages(pack).sourceNames(names)
                .synthesis(new SynthesisCriteria(2, 1, 2, 0.5)).build();
        String prompt = SectionWriterPrompt.userPrompt(a);
        assertFalse(prompt, prompt.contains("SYNTHESIS"));
        assertFalse("no label that sets one source against another", prompt.contains("(source "));

        pack.add(new EvidencePassage(3, "seite-3", "c3", "p3", "Der Radweg ist zwei Meter breit."));
        names.put("seite-3", "radweg.example");
        String mixed = SectionWriterPrompt.userPrompt(a.toBuilder().passages(pack).sourceNames(names).build());
        assertTrue(mixed, mixed.contains("combine passages of at least 2 different sources"));
        assertTrue(mixed, mixed.contains("[2] \"\"\"Die Ampel zeigt Radfahrern ein eigenes Grün.\"\"\" (source "
                + "verkehr.example)"));
        assertTrue(mixed, mixed.contains("(source radweg.example)"));
    }

    private static List<EvidencePassage> manySources(int n) {
        List<EvidencePassage> out = new ArrayList<EvidencePassage>();
        for (int i = 0; i < n; i++) {
            out.add(new EvidencePassage(i + 1, "s" + i, "c", "p" + i, "Text " + i + "."));
        }
        return out;
    }

    @Test
    public void longSectionIsSplitByKeyPoints() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T")
                .keyPoints(Arrays.asList("k1", "k2", "k3", "k4")).passages(pack(6)).wordsPerCall(450)
                .sectionWords(900).answerTokens(1200).build();
        ScriptedChat chat = new ScriptedChat().answer("Teil eins [1].").answer("Teil zwei [2].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertEquals(2, chat.requests.size());
        assertTrue(chat.requests.get(0).get(1).getContent().contains("- k2"));
        assertFalse(chat.requests.get(0).get(1).getContent().contains("- k3"));
        assertTrue(chat.requests.get(1).get(1).getContent().contains("- k3"));
        assertEquals("Teil eins [1].\n\nTeil zwei [2].", draft.getText());
        assertEquals(2, draft.getCitations().size());
    }

    /**
     * Live run 4, book 5: the three parts of a section saw the same eight passages, and up to 13 repeated sentences
     * per part had to be taken out. Each part now gets the passages that cover its key points, numbered [1]..[m] in
     * its prompt and renumbered to the pack's numbers in the section.
     */
    @Test
    public void eachPartGetsThePassagesOfItsKeyPoints() {
        List<EvidencePassage> pack = Arrays.asList(
                new EvidencePassage(1, "a", "ca", "pa",
                        "Vibrationen von Bohrhämmern belasten Hände und Arme der Beschäftigten auf Baustellen."),
                new EvidencePassage(2, "b", "cb", "pb",
                        "Lärm auf Baustellen schädigt das Gehör der Beschäftigten ohne geeigneten Gehörschutz."),
                new EvidencePassage(3, "c", "cc", "pc",
                        "Vibrationen werden mit Sensoren am Handgelenk der Beschäftigten bei der Arbeit gemessen."),
                new EvidencePassage(4, "d", "cd", "pd",
                        "Lärmpegel über 85 Dezibel verlangen nach der Verordnung einen geeigneten Gehörschutz."));
        SectionAssignment a = SectionAssignment.builder("s1").title("Belastungen")
                .keyPoints(Arrays.asList("Vibrationen messen", "Lärm und Gehörschutz")).passages(pack)
                .passagesPerCall(2).wordsPerCall(450).sectionWords(900).answerTokens(1200).build();
        ScriptedChat chat = new ScriptedChat().answer("Vibrationen belasten die Arme [1]. Sensoren messen sie [2].")
                .answer("Lärm schädigt das Gehör [1]. Ab 85 Dezibel braucht es Gehörschutz [2].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertEquals("no repair", 2, chat.requests.size());
        String first = chat.requests.get(0).get(1).getContent();
        String second = chat.requests.get(1).get(1).getContent();
        assertTrue(first, first.contains("[1] \"\"\"Vibrationen von Bohrhämmern"));
        assertTrue(first, first.contains("[2] \"\"\"Vibrationen werden mit Sensoren"));
        assertFalse(first, first.contains("Lärm auf Baustellen"));
        assertTrue(second, second.contains("[1] \"\"\"Lärm auf Baustellen"));
        assertTrue(second, second.contains("[2] \"\"\"Lärmpegel über 85"));
        assertFalse(second, second.contains("Bohrhämmern"));
        assertEquals("Vibrationen belasten die Arme [1]. Sensoren messen sie [3].\n\n"
                + "Lärm schädigt das Gehör [2]. Ab 85 Dezibel braucht es Gehörschutz [4].", draft.getText());
        assertEquals("pc", draft.getCitations().get(3).getPassageId());
        assertEquals("pd", draft.getCitations().get(4).getPassageId());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    @Test
    public void aSectionIsWrittenInOnePartPerCallTargetAndAtMostOnePerKeyPoint() {
        assertEquals(3, SectionAssignment.partCount(1100, 450, 4));
        assertEquals(2, SectionAssignment.partCount(1100, 450, 2));
        assertEquals(1, SectionAssignment.partCount(0, 450, 4));
        assertEquals(1, SectionAssignment.partCount(1100, 450, 0));
        assertEquals(1, SectionAssignment.partCount(450, 450, 4));
    }

    /** Live run 4, book 5, section 1.3: the repair of a part with one error came back without any text. */
    @Test
    public void aRepairThatKeepsFewerSupportedWordsLeavesTheFirstAnswer() {
        ScriptedChat chat = new ScriptedChat()
                .answer("Die Rate stieg auf 15,5 % [1]. Danach kam ein Rückgang [2]. Das war wichtig.")
                .answer("GAP: Zahlen nach 2021");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(6));
        assertEquals(2, chat.requests.size());
        assertEquals("Die Rate stieg auf 15,5 % [1]. Danach kam ein Rückgang [2]. Das war wichtig.",
                draft.getText());
        assertTrue(log.toString(), log.toString().contains("repaired answer of 0 words, 0 citation errors"));
        assertTrue(log.toString(),
                log.toString().contains("kept the first answer (12 words without an error, the repaired one 0)"));
        assertEquals(1, draft.flagsOf(ClaimFlag.Kind.UNCITED_SENTENCE).size());
    }

    /** Live run 4, book 3, section 2.2: the second half repeated sentences the first half had written. */
    @Test
    public void aLaterPartLeavesOutWhatAnEarlierPartSaid() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T")
                .keyPoints(Arrays.asList("k1", "k2", "k3", "k4")).passages(pack(6)).wordsPerCall(450)
                .sectionWords(900).answerTokens(1200).build();
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg weiter [1].")
                .answer("Die Rate stieg weiter [2]. Danach kam ein Rückgang [3].");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(a);
        assertEquals("Die Rate stieg weiter [1].\n\nDanach kam ein Rückgang [3].", draft.getText());
        assertTrue(log.toString(), log.toString().contains("removed 0 label lines and 1 repeated sentences"));
        assertFalse("the log names counts, never text", log.toString().contains("Rate stieg"));
    }

    /** Live run 4, books 2 and 3: echoed key points and a closing list of questions stood in the book as prose. */
    @Test
    public void labelLinesAndOpenQuestionsLeaveTheProse() {
        ScriptedChat chat = new ScriptedChat()
                .answer("Die Rate stieg weiter [1].\n\n- growth\n- Rückgang\n\nWie hoch war die Rate 2021?");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertEquals("Die Rate stieg weiter [1].", draft.getText());
        assertEquals(Arrays.asList("Wie hoch war die Rate 2021?"), draft.getGaps());
        assertEquals("nothing is left to repair", 1, chat.requests.size());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    /** Live run 4, books 2 and 3: "unsere tragbaren Geräte", "implementieren wir" (a manufacturer's voice). */
    @Test
    public void aSourcesWeGetsTheOneRepair() {
        ScriptedChat chat = new ScriptedChat().answer("Unsere Rate stieg weiter [1].")
                .answer("Die Rate stieg weiter [1].");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertTrue(chat.requests.get(0).get(0).getContent().contains("neutral third person"));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> repair = chat.requests.get(1);
        String last = repair.get(repair.size() - 1).getContent();
        assertTrue(last, last.contains("first-person voice of a source \"Unsere\": Unsere Rate stieg weiter [1]."));
        assertEquals("Die Rate stieg weiter [1].", draft.getText());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    /** Live run 4, book 5, section 1.2: "GAP: Gesamtlänge des Textes, Zielwert von 1100 Wörtern, …". */
    @Test
    public void aGapLineThatNamesTheWordTargetIsNoOpenPoint() {
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg weiter [1].\n"
                + "GAP: Gesamtlänge des Textes, Zielwert von 450 Wörtern\nGAP: Zahlen nach 2021");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(6));
        assertEquals(Arrays.asList("Zahlen nach 2021"), draft.getGaps());
        assertTrue(log.toString(), log.toString().contains("dropped 1 gap lines that are no open point"));
        assertTrue(log.toString(), log.toString().contains("(target 450), 0 citation errors"));
        assertTrue(SectionWriter.namesNumber("Zielwert von 1.100 Wörtern", 1100));
        assertTrue(SectionWriter.namesNumber("Zielwert: 1 100 Wörter", 1100));
        assertTrue(SectionWriter.namesNumber("1100 Wörter.", 1100));
        assertFalse(SectionWriter.namesNumber("ISO 11000 und Seite 21100", 1100));
        assertFalse(SectionWriter.namesNumber("Version 1.1003", 1100));
    }

    /** Codex on #214: a gap whose number the material names too is an open point, even when it is the target. */
    @Test
    public void aGapLineWithANumberOfTheMaterialStaysAnOpenPoint() {
        List<EvidencePassage> passages = new ArrayList<EvidencePassage>(pack(5));
        passages.add(new EvidencePassage(6, "src-6", "sha-6", "p-6", "Eine Studie begleitete 450 Beschäftigte auf "
                + "zwölf Baustellen über drei Monate und maß dabei Puls, Hauttemperatur und Schrittzahl."));
        SectionAssignment a = SectionAssignment.builder("s1").title("Rates").brief("How the rate developed")
                .keyPoints(Arrays.asList("growth")).passages(passages).bookLanguage("German")
                .wordsPerCall(450).answerTokens(1200).build();
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg weiter [1].\n"
                + "GAP: Langzeitdaten für 450 Beschäftigte\nGAP: Zahlen nach 2021");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(a);
        assertEquals(Arrays.asList("Langzeitdaten für 450 Beschäftigte", "Zahlen nach 2021"), draft.getGaps());
        assertFalse(log.toString(), log.toString().contains("gap lines that are no open point"));
    }

    /** Live run 4, book 5, section 2.1: the repair moved the listed uncited sentences into GAP lines. */
    @Test
    public void aSentenceTheRepairListedIsNoOpenPointOfTheRepairedAnswer() {
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg weiter [1]. Effizienz ist entscheidend.")
                .answer("Die Rate stieg weiter [1].\nGAP: Effizienz ist entscheidend.\nGAP: Zahlen nach 2021");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(6));
        assertEquals(2, chat.requests.size());
        assertEquals("Die Rate stieg weiter [1].", draft.getText());
        assertEquals(Arrays.asList("Zahlen nach 2021"), draft.getGaps());
        assertTrue(log.toString(), log.toString().contains("one repair for"));
        assertTrue(log.toString(), log.toString().contains("repaired answer of 5 words, 0 citation errors"));
        assertFalse("the log names counts, never text", log.toString().contains("Effizienz"));
    }

    @Test
    public void continuationStartingWithGapKeepsItOnItsOwnLine() {
        assertEquals("The rate rose [1].\nGAP: Later figures", SectionWriter.join("The rate rose [1].", "GAP: Later figures"));
        CitationValidator.Result r = new CitationValidator().validate(
                SectionWriter.join("The rate rose [1].", "GAP: Later figures"), 3);
        assertEquals(Arrays.asList("Later figures"), r.getGaps());
    }

    @Test
    public void cutKeepsSentenceEndingInsideQuotes() {
        assertEquals("She said \u201CThe rate rose.\u201D [1]",
                SectionWriter.upToLastFullSentence("She said \u201CThe rate rose.\u201D [1] Then an unfinished"));
    }

    @Test
    public void failedCallGivesFailedDraft() {
        SectionDraft draft = new SectionWriter(new ScriptedChat(), null).write(assignment(6));
        assertFalse(draft.isOk());
        assertEquals("no scripted answer", draft.getFailure());
    }

    @Test
    public void writerOnPortCallsThePhaseDraftWriterSlotWithTheAnswerBudget() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("Die Rate stieg auf 15,5 % [1].\nGAP: Zahlen nach 2021");
        SectionDraft draft = SectionWriter.onPort(port, Duration.ofSeconds(10), null).write(assignment(6));
        assertTrue(draft.getFailure(), draft.isOk());
        ChatRequest request = port.requests().get(0);
        assertEquals(ResearchModelSlots.PHASE_DRAFT_WRITER, request.getSlot());
        assertEquals(1200, request.getMaxOutputTokens());
        assertEquals(Arrays.asList("Zahlen nach 2021"), draft.getGaps());
        port.close();
    }

    static List<EvidencePassage> longPack(int k, int tokens) {
        List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
        for (int i = 1; i <= k; i++) {
            passages.add(new EvidencePassage(i, "s" + i, "c" + i, "p" + i, repeat("x", tokens * 3)));
        }
        return passages;
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
