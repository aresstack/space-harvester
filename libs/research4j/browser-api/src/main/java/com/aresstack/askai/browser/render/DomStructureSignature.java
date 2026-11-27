package com.aresstack.askai.browser.render;

/**
 * A depth-bounded structural shape of a container (tag skeleton plus coarse text/link markers),
 * used to find REPEATED sibling blocks — the strongest mechanical result signal. Signatures are
 * comparable within one snapshot only; they are not stable selectors.
 */
public final class DomStructureSignature {

    public final String value;

    public DomStructureSignature(String value) {
        this.value = value == null ? "" : value;
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DomStructureSignature
                && value.equals(((DomStructureSignature) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
