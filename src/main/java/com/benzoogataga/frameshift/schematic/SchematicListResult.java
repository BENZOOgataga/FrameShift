package com.benzoogataga.frameshift.schematic;

import org.jetbrains.annotations.Nullable;

import java.util.List;

// Bundles one page of metadata results plus paging and error counters.
public final class SchematicListResult {

    public final List<SchematicMetadata> entries;
    @Nullable
    public final String nextCursor;
    public final int skipped;
    public final int failed;

    public SchematicListResult(List<SchematicMetadata> entries, @Nullable String nextCursor, int skipped, int failed) {
        this.entries = entries;
        this.nextCursor = nextCursor;
        this.skipped = skipped;
        this.failed = failed;
    }
}
