package net.programmierecke.radiodroid2.webdav;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class WebDavSettingsTest {
    @Test
    public void appendsTrailingSlash() {
        assertEquals("https://example.com/dav/", WebDavSettings.normalizeUrl("https://example.com/dav"));
        assertEquals("https://example.com/dav/", WebDavSettings.normalizeUrl("  https://example.com/dav/  "));
    }

    @Test
    public void rejectsNonHttpScheme() {
        try {
            WebDavSettings.normalizeUrl("ftp://example.com/dav/");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsMissingHost() {
        try {
            WebDavSettings.normalizeUrl("https:///dav/");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsUserInfo() {
        try {
            WebDavSettings.normalizeUrl("https://user:pass@example.com/dav/");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsQueryAndFragment() {
        try {
            WebDavSettings.normalizeUrl("https://example.com/dav/?x=1");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeUrl("https://example.com/dav/#section");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsPathTraversal() {
        try {
            WebDavSettings.normalizeUrl("https://example.com/../dav/");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeUrl("https://example.com/dav/..");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsControlCharactersAndInvalidSyntax() {
        try {
            WebDavSettings.normalizeUrl("https://example.com/dav/\u0007x/");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeUrl("https://example.com/dav with space/");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsEmpty() {
        try {
            WebDavSettings.normalizeUrl("   ");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}
