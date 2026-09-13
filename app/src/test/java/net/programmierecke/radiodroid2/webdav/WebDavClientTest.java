package net.programmierecke.radiodroid2.webdav;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebDavClientTest {
    private MockWebServer server;
    private WebDavClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new WebDavClient(new WebDavSettings(server.url("/dav/").toString(), "user", "pass"));
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void checkConnectionUsesPropfind() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207).setBody("<multistatus/>"));
        client.checkConnection();
        RecordedRequest request = server.takeRequest();
        assertEquals("PROPFIND", request.getMethod());
        assertEquals("/dav/", request.getPath());
        assertEquals("0", request.getHeader("Depth"));
        assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"));
    }

    @Test
    public void checkConnectionSucceedsWithPlainOk() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        client.checkConnection();
    }

    @Test
    public void checkConnectionReportsAuthenticationFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        expect(WebDavException.Kind.AUTHENTICATION, client::checkConnection);
    }

    @Test
    public void checkConnectionReportsPermissionFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403));
        expect(WebDavException.Kind.PERMISSION, client::checkConnection);
    }

    @Test
    public void checkConnectionReportsMissingLocation() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        expect(WebDavException.Kind.NOT_FOUND, client::checkConnection);
    }

    @Test
    public void checkConnectionReportsUnsupportedMethod() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(405));
        expect(WebDavException.Kind.PROTOCOL, client::checkConnection);
    }

    @Test
    public void checkConnectionReportsConflictAsMissingLocation() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409));
        expect(WebDavException.Kind.NOT_FOUND, client::checkConnection);
    }

    @Test
    public void checkConnectionRejectsRedirect() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example.com/"));
        expect(WebDavException.Kind.PROTOCOL, client::checkConnection);
    }

    @Test
    public void uploadSendsFileWithBasicAuth() throws Exception {
        File file = createTempFile("content");
        server.enqueue(new MockResponse().setResponseCode(201));
        client.upload("favourites.m3u", file);
        RecordedRequest request = server.takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals("/dav/favourites.m3u", request.getPath());
        assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"));
        assertEquals("content", request.getBody().readUtf8());
        file.delete();
    }

    @Test
    public void uploadCreatesMissingDirectoryAndRetries() throws Exception {
        File file = createTempFile("content");
        final AtomicInteger puts = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("PUT".equals(request.getMethod())) {
                    if (puts.getAndIncrement() == 0) return new MockResponse().setResponseCode(409);
                    return new MockResponse().setResponseCode(201);
                }
                if ("MKCOL".equals(request.getMethod())) return new MockResponse().setResponseCode(405);
                return new MockResponse().setResponseCode(200);
            }
        });
        client.upload("favourites.m3u", file);

        RecordedRequest firstPut = server.takeRequest();
        assertEquals("PUT", firstPut.getMethod());
        assertEquals("/dav/favourites.m3u", firstPut.getPath());
        RecordedRequest mkcol = server.takeRequest();
        assertEquals("MKCOL", mkcol.getMethod());
        assertEquals("/dav/", mkcol.getPath());
        RecordedRequest retryPut = server.takeRequest();
        assertEquals("PUT", retryPut.getMethod());
        assertEquals("/dav/favourites.m3u", retryPut.getPath());
        assertEquals("content", retryPut.getBody().readUtf8());
        file.delete();
    }

    @Test
    public void configuredDirectoryIsUsedForConnectionCheck() throws Exception {
        WebDavClient directoryClient = new WebDavClient(new WebDavSettings(server.url("/dav/").toString(), "backup", "user", "pass"));
        server.enqueue(new MockResponse().setResponseCode(207).setBody("<multistatus/>"));
        directoryClient.checkConnection();
        RecordedRequest request = server.takeRequest();
        assertEquals("PROPFIND", request.getMethod());
        assertEquals("/dav/backup/", request.getPath());
    }

    @Test
    public void configuredDirectoryIsUsedForUpload() throws Exception {
        WebDavClient directoryClient = new WebDavClient(new WebDavSettings(server.url("/dav/").toString(), "backup/sub", "user", "pass"));
        File file = createTempFile("content");
        server.enqueue(new MockResponse().setResponseCode(201));
        directoryClient.upload("favourites.m3u", file);
        RecordedRequest request = server.takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals("/dav/backup/sub/favourites.m3u", request.getPath());
        file.delete();
    }

    @Test
    public void configuredDirectoryIsUsedForDownload() throws Exception {
        WebDavClient directoryClient = new WebDavClient(new WebDavSettings(server.url("/dav/").toString(), "backup", "user", "pass"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello"));
        File target = directoryClient.download("favourites.m3u", tempDirectory());
        assertEquals("hello", readFile(target));
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/dav/backup/favourites.m3u", request.getPath());
        target.delete();
    }

    @Test
    public void downloadWritesFileContent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello"));
        File target = client.download("favourites.m3u", tempDirectory());
        assertEquals("hello", readFile(target));
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/dav/favourites.m3u", request.getPath());
        target.delete();
    }

    @Test
    public void downloadReportsMissingFile() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        expect(WebDavException.Kind.NOT_FOUND, () -> client.download("favourites.m3u", tempDirectory()));
    }

    @Test
    public void downloadReportsAuthenticationFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        expect(WebDavException.Kind.AUTHENTICATION, () -> client.download("favourites.m3u", tempDirectory()));
    }

    @Test
    public void downloadReportsEmptyFile() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));
        expect(WebDavException.Kind.INVALID_DATA, () -> client.download("favourites.m3u", tempDirectory()));
    }

    @Test
    public void downloadRejectsRedirect() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(301).setHeader("Location", "https://elsewhere.example.com/dav/favourites.m3u"));
        expect(WebDavException.Kind.PROTOCOL, () -> client.download("favourites.m3u", tempDirectory()));
    }

    private interface ThrowingTask {
        void run() throws Exception;
    }

    private void expect(WebDavException.Kind kind, ThrowingTask task) throws Exception {
        try {
            task.run();
            fail("Expected WebDavException with kind " + kind);
        } catch (WebDavException e) {
            assertEquals(kind, e.getKind());
        }
    }

    private File tempDirectory() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "webdav_test");
        if (!dir.exists()) assertTrue(dir.mkdirs());
        return dir;
    }

    private File createTempFile(String content) throws Exception {
        File file = File.createTempFile("upload_", ".tmp", tempDirectory());
        FileOutputStream output = new FileOutputStream(file);
        try { output.write(content.getBytes(Charset.forName("UTF-8"))); }
        finally { output.close(); }
        return file;
    }

    private String readFile(File file) throws Exception {
        InputStream input = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
        } finally { input.close(); }
    }
}
