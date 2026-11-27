# Buch schreiben (Phase 4) at a basket folder

A favourites folder is a basket. Its context menu offers „Korb füllen …“ (Phase 3, Research4j) and
„Buch schreiben …“ (Phase 4, Writing4j). Nothing runs without that click.

## Flow

1. **Form.** Mission, edge, included and excluded (the Weidezaun), and under „Erweitert“ the length in words
   (empty = as long as the evidence carries). The form is prefilled from the folder's notes, which „Korb füllen“
   writes too.
2. **Vorbereiten** (job „Buch schreiben“ with the folder name): `BookRun.prepare` reads the basket pages as
   passages (`PageBasketReader`: the existing passage segmentation, headings from the page sections, each page
   released for AI before the next model call), writes source profiles, collects term candidates, sieves them and
   groups them into topic fields.
3. **Begriffe wählen** (`BookTermsView`), only when the sieve has an uncertain middle or nothing safely in:
   - every term is a tag, pale with an outline; a click fills it. Safe in is preselected, uncertain stays pale,
     safe out is folded away.
   - tags are grouped by topic field; deselecting a field excludes it from the book (it goes into the exclusions).
   - hovering a term shows up to three sentences with their neighbours, source and heading; „Ganzer Absatz“ opens
     the paragraph. A word used in several fields can be chosen per field.
   - not chosen is not excluded: the passages of a pale term stay evidence. „Buch schreiben“ without a change is valid.
4. **Schreiben** (second job): `BookRun.open` builds the outline from the chosen cornerstones in one call (chapters
   with questions, each naming the cornerstones it rests on), adds the questions the mission demands, checks them
   against the fence, then checks evidence, plans the sections and writes them.
5. **Result.** `<folder>.md` and `<folder> – Missionsbericht.txt` in the output directory; an existing file is never
   overwritten (`<folder> (2).md`).

## Settings it uses

- The chat model of Einstellungen › KI-Dienst, reasoning off (no `reasoning_effort`).
- Its parallelism (`ai.parallel`) for every step that can run in parallel.
- Its context length (`ai.contextTokens`): bundles and the outline follow it; without it Writing4j's defaults apply.
- No embedding model is needed; without one, shared words choose the passages of a demanded question and its
  chapter.

## What is kept

`app/books/<folder>.json` in the data directory (`BookNotes`): the Weidezaun, the length, and the reader's choice
(chosen terms per field, deselected terms, excluded fields). The reader's choice beats the sieve next time. The
last preparation stays in memory while the app runs („Begriffe wählen …“ in the folder menu reopens it).

## Limits

- Cancelling takes effect at the next model call.
- Page favourites count as pages; favourites of a whole source or space stay out of the basket.
