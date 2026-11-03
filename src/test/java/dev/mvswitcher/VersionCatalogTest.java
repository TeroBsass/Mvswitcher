package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionCatalogTest {
    @Test void knownProfilesAreAvailable() {
        assertNotNull(VersionCatalog.find("1.21.11"));
        assertNotNull(VersionCatalog.find("26.3"));
    }
    @Test void unknownProfileIsNotInvented() {
        assertNull(VersionCatalog.find("1.20.1"));
    }
}
