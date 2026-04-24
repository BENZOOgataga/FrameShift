package com.benzoogataga.frameshift.schematic;

import java.io.IOException;

// Streams block entries one at a time when paste support is added later.
public interface BlockStream extends AutoCloseable {

    boolean hasNext() throws IOException;

    SchematicBlockEntry next() throws IOException;

    @Override
    void close() throws IOException;
}
