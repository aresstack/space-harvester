/**
 * Adapter "Git-Repository" (#137): exactly one ref of a Git remote (Azure Repos, GitHub, GitLab, local) as a
 * knowledge source. JGit stays internal to the adapter, reads from a bare cache owned by the application and never
 * runs repository code, hooks, push or checkout.
 */
package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;
