package com.benzoogataga.frameshift.schematic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchematicMetadataTest {

    @Test
    void volumeUsesLongMath() {
        SchematicMetadata metadata = new SchematicMetadata(
            "big",
            Path.of("big.schem"),
            SchematicFormat.SPONGE_V2,
            50_000,
            1_000,
            2,
            0,
            0,
            0,
            1L,
            0,
            -1L,
            0,
            0
        );

        assertEquals(100_000_000L, metadata.volume());
    }
}
