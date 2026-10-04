package io.tatkalrush.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PoolKeyTest {

    @Test
    @DisplayName("parse is the inverse of keySuffix for every class and quota")
    void parseInvertsKeySuffix() {
        for (TravelClass travelClass : TravelClass.values()) {
            for (QuotaType quota : QuotaType.values()) {
                var key = new PoolKey(4207, travelClass, quota);
                assertEquals(key, PoolKey.parse(key.keySuffix()));
            }
        }
    }

    @Test
    @DisplayName("a malformed suffix is rejected, never half-parsed")
    void malformedSuffixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PoolKey.parse("42:SL"));
        assertThrows(IllegalArgumentException.class, () -> PoolKey.parse("x:SL:GENERAL"));
        assertThrows(IllegalArgumentException.class, () -> PoolKey.parse("42:ZZ:GENERAL"));
        assertThrows(IllegalArgumentException.class, () -> PoolKey.parse("42:SL:NOPE"));
        assertThrows(IllegalArgumentException.class, () -> PoolKey.parse("42:SL:GENERAL:extra"));
    }
}
