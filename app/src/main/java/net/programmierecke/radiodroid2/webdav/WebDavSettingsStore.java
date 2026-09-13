package net.programmierecke.radiodroid2.webdav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;

public final class WebDavSettingsStore {
    private static final String PREFS = "webdav_settings";
    private static final String URL = "url";
    private static final String DIRECTORY = "directory";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String KEY_ALIAS = "radiodroid_webdav_password";
    private final Context context;
    private final SharedPreferences preferences;

    public WebDavSettingsStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String url, String username, String password) {
        save(url, "", username, password);
    }

    public synchronized void save(String url, String directory, String username, String password) {
        WebDavSettings.normalizeUrl(url);
        WebDavSettings.normalizeDirectory(directory);
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Missing credentials");
        }
        preferences.edit().putString(URL, WebDavSettings.normalizeUrl(url)).putString(DIRECTORY, WebDavSettings.normalizeDirectory(directory)).putString(USERNAME, username.trim()).putString(PASSWORD, encrypt(password)).apply();
    }

    public synchronized void saveKeepingPassword(String url, String username) throws WebDavException {
        saveKeepingPassword(url, "", username);
    }

    public synchronized void saveKeepingPassword(String url, String directory, String username) throws WebDavException {
        WebDavSettings old = load();
        if (old == null) throw new WebDavException(WebDavException.Kind.INVALID_DATA, "Password required");
        save(url, directory, username, old.getPassword());
    }

    public synchronized void delete() {
        preferences.edit().clear().apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    public synchronized WebDavSettings load() throws WebDavException {
        String url = preferences.getString(URL, null);
        String username = preferences.getString(USERNAME, null);
        String encoded = preferences.getString(PASSWORD, null);
        if (url == null || username == null || encoded == null) return null;
        try {
            String directory = preferences.getString(DIRECTORY, "");
            return new WebDavSettings(url, directory, username, decrypt(encoded));
        } catch (Exception e) {
            throw new WebDavException(WebDavException.Kind.INVALID_DATA, "WebDAV credentials unavailable", e);
        }
    }

    private String encrypt(String password) {
        try {
            KeyPair pair = getOrCreateKeyPair();
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
            return Base64.encodeToString(cipher.doFinal(password.getBytes(Charset.forName("UTF-8"))), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Secure credential storage unavailable", e);
        }
    }

    private String decrypt(String value) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) throw new IllegalStateException("Secure credential key unavailable");
        PrivateKey key = (PrivateKey) store.getKey(KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return new String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), Charset.forName("UTF-8"));
    }

    @SuppressLint("NewApi")
    private KeyPair getOrCreateKeyPair() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            PublicKey publicKey = store.getCertificate(KEY_ALIAS).getPublicKey();
            PrivateKey privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
            return new KeyPair(publicKey, privateKey);
        }
        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 20);
        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS)
                .setSubject(new X500Principal("CN=" + KEY_ALIAS))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .build();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        generator.initialize(spec);
        return generator.generateKeyPair();
    }
}
