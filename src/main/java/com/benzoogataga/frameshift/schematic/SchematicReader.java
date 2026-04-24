package com.benzoogataga.frameshift.schematic;

import java.io.IOException;
import java.nio.file.Path;

// Defines the minimum contract every schematic format reader must implement.
public interface SchematicReader {

    boolean supports(Path file);

    SchematicMetadata readMetadata(Path file) throws IOException;

    BlockStream openBlockStream(Path file, SchematicReadOptions options) throws IOException;
}
