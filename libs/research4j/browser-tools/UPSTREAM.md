# Origin

Moved from [aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client) at commit
`68400723e3b6ee1d77a6601268709bb11d413947` (MIT, AresStack), the version Space Harvester ran as its copied module
`enterprise-ai-browser`. Packages stay as they were (`com.aresstack.enterpriseai.*`), like the Ask AI packages of
this repository, so hosts migrate by swapping the dependency.

| Upstream module | Here |
|---|---|
| `browser-api` (port, pages, links, controls, engine SPI) and `astu/chat/ChatConversationId` | `browser-api` |
| `browser-runtime` (profile per chat, delayed start, reconnect) | `browser-tools` |
| `astu/approval`, `astu/acropolis/attachment` (text extractor port) | `browser-tools` |
| `proasteion/katagogion` tool description types | `browser-tools` |
| `proasteion/application` browser tools, access rules, approvals, progress | `browser-tools` |

Not moved: `browser-firefox` (Firefox over WebDriver BiDi with the vendored WD4J). The engine of these tools is
the Playwright engine in `browser-playwright`.
