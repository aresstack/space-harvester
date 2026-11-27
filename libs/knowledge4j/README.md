# Knowledge4j

Shared contracts for [Research4j](https://github.com/aresstack/research4j) and [Writing4j](https://github.com/aresstack/writing4j). Knowledge4j holds interfaces and small value types that a host application implements; it contains no knowledge base, no project management and no model client. Research4j and Writing4j never depend on each other, only on Knowledge4j.

The sources come from [Ask AI](https://github.com/aresstack/askai) main at [d89d1c5f](https://github.com/aresstack/askai/commit/d89d1c5f) (26 Nov 2026). Ask AI itself remains unchanged.

## Modules

| Module | Content |
| --- | --- |
| knowledge-model | `ModelPort` with slots, messages, tools, thinking mode, output format, reservations and resources; `EmbeddingPort` with its three roles; the chat core (`MainModelChat`, `PortMainModelChat`, `BlockingModelCall`, metrics) and the neutral role core (`ModelRole`, `RoleOutput`, `RoleToolCatalog`). Test fixtures: `FakeModelPort`. |
| knowledge-file-support (optional) | Crash-safe UTF-8 writes (`AtomicFiles`) and `CorruptFileException`, shared by the file stores of Research4j and Writing4j. |
| knowledge-api | The basket (`SourceBasket` of `SourceRef`s, read through `BasketReader`): the only interface between Research4j, which fills it, and Writing4j, which writes from it; mission and Weidezaun (`Fence`); the registrable domain of a host by the bundled public suffix list (`PublicSuffixDomainKeyResolver`), shared by both libraries to group sources by site. Source references (`SourceRef`, extensible `SourceKind`, exact `SourceLocation`), `Provenance` and `Classification`; `SourceSearchPort`, `ContentFetchPort`, `PassageSearchPort`, `PassageLookup`, `PassageView`; `SentenceSegmentationPort`; cancellation and progress; the mandatory `TransferPolicy` with the run guard (high-water mark) and guarded ports. Read views for writing live in Writing4j. |
| knowledge-model-support (optional) | `CapacityPlanner`: a contract-true `ModelPort` over a narrow streaming `ModelBackend` for hosts without their own model management: fair queue per model, capacity, context and wait from the host settings, no leaked places, warm-up reported as load, reachability only or none. |

Package names start with `com.aresstack.knowledge4j`. Java 21.

## Build

```sh
bash gradlew clean build
```

Contract tests are published as JUnit 5 test fixtures, so a host can test its adapters against them. During development, Research4j, Writing4j and hosts include Knowledge4j as a Gradle composite build; no local Maven publication is needed.

## Releases

Nothing is published or tagged without the explicit approval of the product owner.

## License

MIT; see LICENSE.
