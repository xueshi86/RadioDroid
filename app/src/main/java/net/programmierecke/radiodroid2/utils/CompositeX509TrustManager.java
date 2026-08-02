package net.programmierecke.radiodroid2.utils;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * 组合式 X509TrustManager，依次尝试多个 TrustManager 进行证书验证。
 * <p>
 * 用于在不修改系统 TrustStore 的前提下，为 OkHttp 额外信任 ISRG Root X1
 * (Let's Encrypt 根证书)，解决 Android 5.1 等旧版本设备无法验证
 * Let's Encrypt 证书导致 SSL 握手失败的问题。
 * <p>
 * 安全性：系统 TrustManager 始终优先检查。只有系统验证失败时，
 * 才尝试额外的 TrustManager（仅信任 ISRG Root X1）。
 */
public class CompositeX509TrustManager implements X509TrustManager {

    private final X509TrustManager systemTrustManager;
    private final X509TrustManager[] additionalTrustManagers;

    public CompositeX509TrustManager(X509TrustManager systemTrustManager,
                                     X509TrustManager... additionalTrustManagers) {
        this.systemTrustManager = systemTrustManager;
        this.additionalTrustManagers = additionalTrustManagers;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            systemTrustManager.checkClientTrusted(chain, authType);
        } catch (CertificateException e) {
            tryAdditional(chain, authType, true);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            systemTrustManager.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            tryAdditional(chain, authType, false);
        }
    }

    private void tryAdditional(X509Certificate[] chain, String authType, boolean isClient)
            throws CertificateException {
        CertificateException lastException = null;
        for (X509TrustManager tm : additionalTrustManagers) {
            try {
                if (isClient) {
                    tm.checkClientTrusted(chain, authType);
                } else {
                    tm.checkServerTrusted(chain, authType);
                }
                return; // 验证成功
            } catch (CertificateException ce) {
                lastException = ce;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        List<X509Certificate> list = new ArrayList<>();
        list.addAll(Arrays.asList(systemTrustManager.getAcceptedIssuers()));
        for (X509TrustManager tm : additionalTrustManagers) {
            list.addAll(Arrays.asList(tm.getAcceptedIssuers()));
        }
        return list.toArray(new X509Certificate[0]);
    }
}
