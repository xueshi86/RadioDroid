package net.programmierecke.radiodroid2.webdav;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class WebDavClient {
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L;
    private final WebDavSettings settings;
    private final OkHttpClient client;

    public WebDavClient(WebDavSettings settings) {
        if (settings == null) throw new IllegalArgumentException("Missing settings");
        this.settings = settings;
        client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).followRedirects(false).build();
    }

    public void checkConnection() throws WebDavException {
        Response response = null;
        try {
            Request request = authenticated(new Request.Builder().url(settings.getBaseUrl()).method("PROPFIND", RequestBody.create(null, new byte[0])).header("Depth", "0")).build();
            response = client.newCall(request).execute();
            requireSuccess(response);
        } catch (IOException e) {
            throw network(e);
        } finally {
            if (response != null) response.close();
        }
    }

    public void upload(String name, File file) throws WebDavException {
        if (file == null || !file.isFile()) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Upload file unavailable");
        Response response = null;
        try {
            response = executePut(name, file);
            if (response.code() == 409) {
                response.close();
                response = null;
                ensureDirectory();
                response = executePut(name, file);
            }
            requireSuccess(response);
        } catch (IOException e) {
            throw network(e);
        } finally {
            if (response != null) response.close();
        }
    }

    public File download(String name, File directory) throws WebDavException {
        if (directory == null || (!directory.exists() && !directory.mkdirs())) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Download directory unavailable");
        File target;
        try { target = File.createTempFile("webdav_", ".tmp", directory); } catch (IOException e) { throw network(e); }
        Response response = null;
        try {
            Request request = authenticated(new Request.Builder().url(fileUrl(name)).get()).build();
            response = client.newCall(request).execute();
            requireSuccess(response);
            if (response.body() == null) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Empty response");
            if (response.body().contentLength() > MAX_DOWNLOAD_BYTES) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Remote file is too large");
            InputStream input = response.body().byteStream();
            FileOutputStream output = new FileOutputStream(target);
            long total = 0;
            byte[] buffer = new byte[8192];
            int count;
            try {
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Remote file is too large");
                    output.write(buffer, 0, count);
                }
                output.flush();
            } finally {
                output.close();
                input.close();
            }
            if (target.length() == 0) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Empty file");
            return target;
        } catch (WebDavException e) {
            target.delete();
            throw e;
        } catch (IOException e) {
            target.delete();
            throw network(e);
        } finally {
            if (response != null) response.close();
        }
    }

    private Response executePut(String name, File file) throws IOException, WebDavException {
        Request request = authenticated(new Request.Builder().url(fileUrl(name)).put(RequestBody.create(OCTET_STREAM, file))).build();
        return client.newCall(request).execute();
    }

    private void ensureDirectory() throws WebDavException {
        HttpUrl base = HttpUrl.parse(settings.getBaseUrl());
        if (base == null) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Invalid WebDAV URL");
        List<String> segments = base.pathSegments();
        StringBuilder path = new StringBuilder("/");
        Response response = null;
        try {
            for (String segment : segments) {
                if (segment == null || segment.isEmpty()) continue;
                path.append(segment).append('/');
                HttpUrl target = base.newBuilder().encodedPath(path.toString()).build();
                Request request = authenticated(new Request.Builder().url(target).method("MKCOL", RequestBody.create(null, new byte[0]))).build();
                response = client.newCall(request).execute();
                int code = response.code();
                response.close();
                response = null;
                if (code == 200 || code == 201 || code == 405 || code == 301) continue;
                if (code == 401) throw new WebDavException(WebDavException.Kind.AUTHENTICATION, "WebDAV authentication failed");
                if (code == 403) throw new WebDavException(WebDavException.Kind.PERMISSION, "WebDAV permission denied");
                throw new WebDavException(WebDavException.Kind.PROTOCOL, "WebDAV directory creation is not supported");
            }
        } catch (IOException e) {
            throw network(e);
        } finally {
            if (response != null) response.close();
        }
    }

    private Request.Builder authenticated(Request.Builder builder) {
        return builder.header("Authorization", Credentials.basic(settings.getUsername(), settings.getPassword()));
    }

    private String fileUrl(String name) throws WebDavException {
        if (!"favourites.m3u".equals(name) && !"radio_droid_database.db".equals(name)) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Invalid remote file");
        return settings.getBaseUrl() + name;
    }

    private void requireSuccess(Response response) throws WebDavException {
        int code = response.code();
        if (code >= 200 && code < 300) return;
        if (code == 401) throw new WebDavException(WebDavException.Kind.AUTHENTICATION, "WebDAV authentication failed");
        if (code == 403) throw new WebDavException(WebDavException.Kind.PERMISSION, "WebDAV permission denied");
        if (code == 404 || code == 409) throw new WebDavException(WebDavException.Kind.NOT_FOUND, "WebDAV location not found");
        if (code == 405 || code == 501) throw new WebDavException(WebDavException.Kind.PROTOCOL, "WebDAV method is not supported");
        if (code >= 300 && code < 400) throw new WebDavException(WebDavException.Kind.PROTOCOL, "WebDAV redirect is not supported");
        throw new WebDavException(WebDavException.Kind.NETWORK, "WebDAV request failed: " + code);
    }

    private WebDavException network(IOException e) {
        return new WebDavException(WebDavException.Kind.NETWORK, e instanceof SocketTimeoutException ? "WebDAV request timed out" : "WebDAV network request failed", e);
    }
}
