/*
 * COMSAT
 * Copyright (C) 2014, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.fibers.jersey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.AbstractResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import co.paralleluniverse.common.util.Debug;
import co.paralleluniverse.embedded.containers.EmbeddedServer;
import co.paralleluniverse.embedded.containers.JettyServer;
import co.paralleluniverse.embedded.containers.TomcatServer;
import co.paralleluniverse.embedded.containers.UndertowServer;

@RunWith(Parameterized.class)
public class FiberServletContainerTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {JettyServer.class},
            {TomcatServer.class},
            {UndertowServer.class},
        });
    }
    private final Class<? extends EmbeddedServer> cls;
    private EmbeddedServer server;
    private CloseableHttpClient client;

    public FiberServletContainerTest(Class<? extends EmbeddedServer> cls) {
        this.cls = cls;
    }
    private static final String PACKAGE_NAME_PREFIX = FiberServletContainerTest.class.getPackage().getName() + ".";

    public static final String REQUEST_FILTER_HEADER = "test.filter.request.header";

    public static final String REQUEST_FILTER_HEADER_VALUE = "ok";

    public static final String RESPONSE_FILTER_HEADER = "test.filter.request.header";

    public static final String RESPONSE_FILTER_HEADER_VALUE = "ok";

    @Before
    public void setUp() throws Exception {
        this.server = cls.newInstance();
        // snippet jersey registration
        server.addServlet("api", co.paralleluniverse.fibers.jersey.ServletContainer.class, "/*")
                .setInitParameter("jersey.config.server.provider.packages", PACKAGE_NAME_PREFIX)
                // end of snippet
                .setLoadOnStartup(1);
        server.start();
        this.client = HttpClients.custom().setDefaultRequestConfig(RequestConfig.custom()
                .setSocketTimeout(5000).setConnectTimeout(5000).setConnectionRequestTimeout(5000)
                .build()).build();
    }

    @After
    public void tearDown() throws Exception {
        server.stop();
        client.close();
    }

    @Test
    public void testGet() throws IOException, InterruptedException, Exception {
        for (int i = 0; i < 10; i++)
            client.execute(new HttpGet("http://localhost:8080/service?sleep=10"), TEST_RESPONSE_HANDLER);
    }

    @Test
    public void testPost() throws IOException, InterruptedException, Exception {
        for (int i = 0; i < 10; i++)
            client.execute(new HttpPost("http://localhost:8080/service?sleep=10"), TEST_RESPONSE_HANDLER);
    }

    private static final ResponseHandler<Void> TEST_RESPONSE_HANDLER = new AbstractResponseHandler<Void>() {
        @Override
        public Void handleEntity(HttpEntity entity) throws IOException {
            assertEquals("sleep was 10", EntityUtils.toString(entity));
            return null;
        }

        @Override
        public Void handleResponse(HttpResponse response) throws HttpResponseException, IOException {
            Header h = response.getFirstHeader(RESPONSE_FILTER_HEADER);
            assertNotNull(h);
            assertEquals(RESPONSE_FILTER_HEADER_VALUE, h.getValue());
            return super.handleResponse(response);
        }
    };

    @Rule
    public TestName name = new TestName();
    @Rule
    public TestRule watchman = new TestWatcher() {
        @Override
        protected void starting(Description desc) {
            if (Debug.isDebug()) {
                System.out.println("STARTING TEST " + desc.getMethodName());
                Debug.record(0, "STARTING TEST " + desc.getMethodName());
            }
        }

        @Override
        public void failed(Throwable e, Description desc) {
            System.out.println("FAILED TEST " + desc.getMethodName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            if (Debug.isDebug() && !(e instanceof OutOfMemoryError)) {
                Debug.record(0, "EXCEPTION IN THREAD " + Thread.currentThread().getName() + ": " + e + " - " + Arrays.toString(e.getStackTrace()));
                Debug.dumpRecorder("~/quasar.dump");
            }
        }

        @Override
        protected void succeeded(Description desc) {
            Debug.record(0, "DONE TEST " + desc.getMethodName());
        }
    };

}
