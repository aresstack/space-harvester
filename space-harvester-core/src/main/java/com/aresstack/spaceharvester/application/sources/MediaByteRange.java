package com.aresstack.spaceharvester.application.sources;

/** An authorized, short-lived stream of ranged media reads from a knowledge source. */
@FunctionalInterface
public interface MediaByteRange {

    byte[] read(long offset, int maximumBytes);
}
