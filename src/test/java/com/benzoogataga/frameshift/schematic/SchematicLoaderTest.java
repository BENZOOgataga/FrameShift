package com.benzoogataga.frameshift.schematic;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SchematicLoaderTest {

    @Test
    void cursorCarriesDirectoryIndexFilenameAndHash() throws Exception {
        Method encode = SchematicLoader.class.getDeclaredMethod("encodeCursor", int.class, String.class, String.class);
        encode.setAccessible(true);
        String token = (String) encode.invoke(null, 1, "castle.schem", "abc123");

        Method decode = SchematicLoader.class.getDeclaredMethod("decodeCursor", String.class);
        decode.setAccessible(true);
        Object cursor = decode.invoke(null, token);

        Method directoryIndex = cursor.getClass().getDeclaredMethod("directoryIndex");
        Method fileName = cursor.getClass().getDeclaredMethod("fileName");
        Method pathHash = cursor.getClass().getDeclaredMethod("pathHash");

        assertEquals(1, directoryIndex.invoke(cursor));
        assertEquals("castle.schem", fileName.invoke(cursor));
        assertEquals("abc123", pathHash.invoke(cursor));
    }

    @Test
    void hashUsesNormalizedPathAsTieBreaker() throws Exception {
        Method hash = SchematicLoader.class.getDeclaredMethod("hashPath", Path.class);
        hash.setAccessible(true);

        String first = (String) hash.invoke(null, Path.of("C:\\Temp\\One\\castle.schem").normalize());
        String second = (String) hash.invoke(null, Path.of("C:\\Temp\\Two\\castle.schem").normalize());

        assertNotEquals(first, second);
    }

}
