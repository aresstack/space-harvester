package com.aresstack.spaceharvester.app.hf;

/**
 * Eine Datei eines Hugging-Face-Repositorys, wie die Tree-API sie nennt (übernommen aus askai
 * {@code com.aresstack.askai.hf.HuggingFaceFile}).
 *
 * @param repository etwa {@code openai/whisper-tiny}
 * @param path       Pfad im Repository
 * @param size       Länge in Bytes, {@code -1} wenn unbekannt
 * @param sha256     erwartete SHA-256 (bei LFS-Dateien die {@code oid}) in Kleinbuchstaben, leer wenn unbekannt
 * @param gitOid     Git-Blob-SHA-1 kleiner Dateien ohne LFS, leer wenn unbekannt
 */
public record HuggingFaceFile(String repository, String path, long size, String sha256, String gitOid) {

    public HuggingFaceFile {
        repository = repository == null ? "" : repository;
        path = path == null ? "" : path;
        sha256 = sha256 == null ? "" : sha256.strip().toLowerCase(java.util.Locale.ROOT);
        gitOid = gitOid == null ? "" : gitOid.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
