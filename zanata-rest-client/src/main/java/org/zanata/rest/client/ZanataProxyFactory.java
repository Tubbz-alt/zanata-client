package org.zanata.rest.client;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jboss.resteasy.client.ClientExecutor;
import org.jboss.resteasy.client.ClientRequestFactory;
import org.jboss.resteasy.client.ClientResponse;
import org.jboss.resteasy.client.core.executors.ApacheHttpClient4Executor;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zanata.rest.RestConstant;
import org.zanata.rest.dto.VersionInfo;

public class ZanataProxyFactory implements ITranslationResourcesFactory {
    private String clientVersion;
    private String serverVersion;

    static {
        ResteasyProviderFactory instance =
                ResteasyProviderFactory.getInstance();
        RegisterBuiltin.register(instance);
    }

    private static final Logger log = LoggerFactory
            .getLogger(ZanataProxyFactory.class);
    private final ClientRequestFactory crf;

    /**
     * This will by default pass a null ClientExecutor to the
     * ClientRequestFactory which in turn create default client executor. If
     * sslCertDisabled is true, we will create our customized ClientExecutor.
     *
     * @param base
     *            base url
     * @param username
     *            username
     * @param apiKey
     *            api key
     * @param clientApiVersion
     *            client API version
     * @param logHttp
     *            whether to log http output
     * @param sslCertDisabled
     *            whether to disable SSL certificate verification
     */
    public ZanataProxyFactory(URI base, String username, String apiKey,
            VersionInfo clientApiVersion, boolean logHttp,
            boolean sslCertDisabled) {
        ClientExecutor clientExecutor =
                sslCertDisabled ? createClientExecutor() : null;

        crf = new ClientRequestFactory(clientExecutor, null, fixBase(base));
        crf.setFollowRedirects(true);
        registerPrefixInterceptor(new TraceDebugInterceptor(logHttp));
        registerPrefixInterceptor(new ApiKeyHeaderDecorator(username, apiKey,
                clientApiVersion.getVersionNo()));

        clientVersion = clientApiVersion.getVersionNo();
        String clientTimestamp = clientApiVersion.getBuildTimeStamp();
        IVersionResource iversion = createIVersionResource();
        ClientResponse<VersionInfo> versionResp = iversion.get();
        // unauthorized
        if (versionResp.getResponseStatus() == Response.Status.UNAUTHORIZED) {
            throw new RuntimeException("Incorrect username/password");
        } else if (versionResp.getResponseStatus() == Response.Status.SERVICE_UNAVAILABLE) {
            throw new RuntimeException("Service is currently unavailable. " +
                "Please check outage notification or try again later.");
        }
        ClientUtility.checkResult(versionResp);
        VersionInfo serverVersionInfo = versionResp.getEntity();
        serverVersion = serverVersionInfo.getVersionNo();
        String serverTimestamp = serverVersionInfo.getBuildTimeStamp();

        log.info("client API version: {}, server API version: {}",
                clientVersion, serverVersion);
        if (!serverVersion.equals(clientVersion)) {
            log.warn("client API version is {}, but server API version is {}",
                    clientVersion, serverVersion);
        } else if (serverVersion.contains(RestConstant.SNAPSHOT_VERSION)
                && !serverTimestamp.equalsIgnoreCase(clientTimestamp)) {
            log.warn(
                    "client API timestamp is {}, but server API timestamp is {}",
                    clientTimestamp, serverTimestamp);
        }
    }

    private static ClientExecutor createClientExecutor() {
        try {
            // Create a trust manager that does not validate certificate chains
            // against our server
            TrustManager[] trustOurCerts =
                    new TrustManager[] { new AcceptAllX509TrustManager() };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustOurCerts, new SecureRandom());

            SSLSocketFactory factory = new SSLSocketFactory(sslContext);

            HttpClient client = new DefaultHttpClient();
            ClientConnectionManager manager = client.getConnectionManager();
            manager.getSchemeRegistry().register(
                    new Scheme("https", 443, factory));
            return new ApacheHttpClient4Executor(client);

        } catch (Exception e) {
            log.warn("error disabling SSL certificate", e);
        }
        log.warn("fall back to SSL certificate verification.");
        return null;
    }

    /**
     * Returns the Base url to be used for rest Requests.
     */
    private URL getBaseUrl() {
        try {
            return new URL(fixBase(crf.getBase()).toString() + getUrlPrefix());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private URI getBaseUri() {
        try {
            return getBaseUrl().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    protected String getUrlPrefix() {
        return "rest/";
    }

    public <T> T createProxy(Class<T> clazz, URI baseUri) {
        log.debug("{} proxy uri: {}", clazz.getSimpleName(), baseUri);
        T proxy = crf.createProxy(clazz, baseUri);
        // CacheFactory.makeCacheable(proxy);
        return proxy;
    }

    /**
     * Returns a client proxy, provided all information is on the proxied
     * interface. (i.e. The interface is marked with a {@link javax.ws.rs.Path}
     * annotation.)
     *
     * @param clazz
     *            Client interface to proxy.
     * @return Client proxy for the class.
     * @see {@link ZanataProxyFactory#createProxy(Class, java.net.URI)}
     */
    public <T> T createProxy(Class<T> clazz) {
        return createProxy(clazz, getBaseUri());
    }

    private static URI fixBase(URI base) {
        if (base != null) {
            String baseString = base.toString();
            if (!baseString.endsWith("/")) {
                try {
                    URI result = new URI(baseString + "/");
                    log.warn("Appending '/' to base URL '{}': using '{}'",
                            baseString, result);
                    return result;
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return base;
    }

    public IGlossaryResource getGlossaryResource() {
        return createProxy(IGlossaryResource.class);
    }

    public IAccountResource getAccount(String username) {
        return createProxy(IAccountResource.class, getAccountURI(username));
    }

    public URI getAccountURI(String username) {
        try {
            URL url = new URL(getBaseUrl(), "accounts/u/" + username);
            return url.toURI();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public IProjectResource getProject(String proj) {
        return createProxy(IProjectResource.class, getProjectURI(proj));
    }

    public URI getProjectURI(String proj) {
        try {
            URL url = new URL(getBaseUrl(), "projects/p/" + proj);
            return url.toURI();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public IProjectIterationResource getProjectIteration(String proj,
            String iter) {
        return createProxy(IProjectIterationResource.class,
                getProjectIterationURI(proj, iter));
    }

    public URI getProjectIterationURI(String proj, String iter) {
        try {
            URL url =
                    new URL(getBaseUrl(), "projects/p/" + proj
                            + "/iterations/i/" + iter);
            return url.toURI();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    // NB IProjectsResource is not currently used in Java
    public IProjectsResource getProjectsResource() {
        return createProxy(IProjectsResource.class);
    }

    @Override
    public ITranslatedDocResource getTranslatedDocResource(String projectSlug,
            String versionSlug) {
        return createProxy(ITranslatedDocResource.class,
                getResourceURI(projectSlug, versionSlug));
    }

    public ISourceDocResource getSourceDocResource(String projectSlug,
            String versionSlug) {
        return createProxy(ISourceDocResource.class,
                getResourceURI(projectSlug, versionSlug));
    }

    public IFileResource getFileResource() {
        return createProxy(IFileResource.class);
    }

    public IStatisticsResource getStatisticsResource() {
        return createProxy(IStatisticsResource.class);
    }

    public ICopyTransResource getCopyTransResource() {
        return createProxy(ICopyTransResource.class);
    }

    public IAsynchronousProcessResource getAsynchronousProcessResource() {
        return createProxy(IAsynchronousProcessResource.class);
    }

    @Override
    public URI getResourceURI(String projectSlug, String versionSlug) {
        String spec =
                "projects/p/" + projectSlug + "/iterations/i/" + versionSlug
                        + "/r";
        try {
            return new URL(getBaseUrl(), spec).toURI();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            String msg =
                    "URI Syntax error. Please make sure your project (project ID) and version are correct.";
            log.error(msg);
            log.error("part of your url: {}", spec);
            throw new RuntimeException(msg);
        }
    }

    /**
     * @see org.jboss.resteasy.client.core.ClientInterceptorRepositoryImpl#registerInterceptor(Object)
     * @param interceptor
     */
    public void registerPrefixInterceptor(Object interceptor) {
        crf.getPrefixInterceptors().registerInterceptor(interceptor);
    }

    protected IVersionResource createIVersionResource() {
        return createProxy(IVersionResource.class, getBaseUri());
    }

    /**
     * Compares a given version identifier with the server version.
     *
     * @param version
     *            The version to against which to compare the server version.
     * @return A positive integer if the server version is greater than the
     *         given version. A negative integer if the server version is less
     *         than the given version. 0 if both versions are the same.
     */
    public int compareToServerVersion(String version) {
        DefaultArtifactVersion srvVersion =
                new DefaultArtifactVersion(serverVersion);
        DefaultArtifactVersion providedVersion =
                new DefaultArtifactVersion(version);

        return srvVersion.compareTo(providedVersion);
    }

    private static class AcceptAllX509TrustManager implements X509TrustManager {
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        public void
                checkClientTrusted(X509Certificate[] certs, String authType)
                        throws CertificateException {
        }

        public void
                checkServerTrusted(X509Certificate[] certs, String authType)
                        throws CertificateException {
        }
    }
}
