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

    @Test
    public void normalizesDirectory() {
        assertEquals("", WebDavSettings.normalizeDirectory(null));
        assertEquals("", WebDavSettings.normalizeDirectory("  "));
        assertEquals("backup", WebDavSettings.normalizeDirectory("/backup/"));
        assertEquals("backup", WebDavSettings.normalizeDirectory("  backup  "));
        assertEquals("backup/sub", WebDavSettings.normalizeDirectory("//backup///sub//"));
    }

    @Test
    public void rejectsInvalidDirectory() {
        try {
            WebDavSettings.normalizeDirectory("..");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeDirectory("a/../b");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeDirectory("a\\b");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeDirectory("a\u0007b");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            WebDavSettings.normalizeDirectory("a%2e%2eb");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void baseUrlAppendsDirectory() {
        WebDavSettings noDirectory = new WebDavSettings("https://example.com/dav/", "user", "pass");
        assertEquals("https://example.com/dav/", noDirectory.getBaseUrl());

        WebDavSettings withDirectory = new WebDavSettings("https://example.com/dav/", "backup/sub", "user", "pass");
        assertEquals("https://example.com/dav/backup/sub/", withDirectory.getBaseUrl());
        assertEquals("backup/sub", withDirectory.getDirectory());

        WebDavSettings normalizedDirectory = new WebDavSettings("https://example.com/dav/", "/backup/", "user", "pass");
        assertEquals("https://example.com/dav/backup/", normalizedDirectory.getBaseUrl());
    }
}
