# Offline builds in ChatGPT

The rolling `chatgpt-compatible` GitHub release contains a ZIP named
`space-harvester-chatgpt-compatible.zip`. It includes the complete Gradle
multi-module repository, the Gradle 8.14.3 distribution and a dependency cache
populated by a Java 21 Linux GitHub Actions build.

## Run after extraction

Requires **Java 21**, Bash and Linux. Run from the extracted project root:

```bash
bash chatgpt-build.sh
```

The script sets `GRADLE_USER_HOME` to the included
`.chatgpt/gradle-home` and invokes `bash gradlew --offline --no-daemon
--console=plain clean build`. It uses `xvfb-run` when installed for Swing
tests. The wrapper need not be executable.

## Rebuild the release

The workflow `.github/workflows/chatgpt-compatible-release.yml` runs on
`main` pushes or manually via GitHub Actions. It builds with network access
and `--refresh-dependencies`, removes transient Gradle state, packages the
repository together with the cache, and **tests the extracted ZIP offline**
before replacing the rolling release asset.

The ZIP is targeted at Linux x86-64 and Java 21 (the same environment as
the GitHub runner); it is not a cross-platform cache guarantee. New
dependencies, Gradle plugins or uncached tasks may require rebuilding the ZIP.
Do not commit `.chatgpt/` to Git.
