package org.zanata.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.ContainerServer;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.zanata.client.commands.AppAbortStrategy;
import org.zanata.client.commands.NullAbortStrategy;

import static org.hamcrest.MatcherAssert.*;

/**
 * @author Patrick Huang <a
 *         href="mailto:pahuang@redhat.com">pahuang@redhat.com</a>
 */
public class ClientToServerTest {
    private ZanataClient client;
    @Mock
    private AppAbortStrategy mockAbortStrategy;
    @Captor
    private ArgumentCaptor<Throwable> throwableCapture;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        client =
                new ZanataClient(mockAbortStrategy, out, err);
    }

    @Test
    @Ignore("this will talk to live server")
    public void testDisableSSLCertOption() {
        String command = "stats";
        String url = "https://translate.engineering.redhat.com/";
        String project = "iok";
        String version = "6.4";
        client.processArgs(command, "--url", url, "--project", project,
                "--project-version", version, "--disable-ssl-cert");
    }

    @Test
    public void test503ResponseHandling() throws IOException {
        // https://bugzilla.redhat.com/show_bug.cgi?id=874983
        HTTPMockContainer mockContainer =
            HTTPMockContainer.notOkResponse(Status.SERVICE_UNAVAILABLE);
        ContainerServer server = new ContainerServer(mockContainer);
        Connection connection = new SocketConnection(server);
        SocketAddress address = new InetSocketAddress(8080);
        connection.connect(address);

        String command = "stats";
        String url = "http://localhost:8080/";
        String project = "iok";
        String version = "6.4";
//        client.setErrors(true);
        client.processArgs(command, "--url", url, "--project", project,
                "--project-version", version, "--username", "admin", "--key",
                "abcdeabcdeabcdeabcdeabcdeabcde12");

        server.stop();
        Mockito.verify(mockAbortStrategy).abort(throwableCapture.capture());
        assertThat("Client will display meaningful message for 503",
                throwableCapture.getValue().getMessage()
                        .contains("Service is currently unavailable"));

    }
}
