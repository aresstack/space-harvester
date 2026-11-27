package com.aresstack.knowledge4j.policy;

/**
 * One exit of the libraries as the host bound it: a search port, a fetcher, a model slot, an embedding endpoint.
 *
 * @param id       the host's name for the exit, for the visible reason of a denied step; never a URL with
 *                 credentials
 * @param exposure where the exit sits; {@code null} counts as {@link Exposure#EXTERNAL}
 */
public record TransferTarget(String id, Exposure exposure) {

    public TransferTarget {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a transfer target needs an id");
        }
        exposure = exposure == null ? Exposure.EXTERNAL : exposure;
    }

    public static TransferTarget internal(String id) {
        return new TransferTarget(id, Exposure.INTERNAL);
    }

    public static TransferTarget external(String id) {
        return new TransferTarget(id, Exposure.EXTERNAL);
    }
}
