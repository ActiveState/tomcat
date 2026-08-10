/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.servlets;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.servlet.ServletContext;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import static org.apache.catalina.startup.SimpleHttpClient.CRLF;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.ExpandWar;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

@RunWith(Parameterized.class)
public class TestDefaultServletPut extends TomcatBaseTest {

    private static final String START_TEXT= "Starting text";
    private static final String START_LEN = Integer.toString(START_TEXT.length());
    private static final String PATCH_TEXT= "Ending *";
    private static final String PATCH_LEN = Integer.toString(PATCH_TEXT.length());
    private static final String END_TEXT= "Ending * text";

    @Parameterized.Parameters(name = "{index} rangeHeader [{0}]")
    public static Collection<Object[]> parameters() {
        List<Object[]> parameterSets = new ArrayList<>();

        // Valid partial PUT
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.TRUE, END_TEXT, Boolean.TRUE });
        // Full PUT
        parameterSets.add(new Object[] {
                "", null, PATCH_TEXT, Boolean.TRUE });
        // Invalid range
        parameterSets.add(new Object[] {
                "Content-Range: apples 0-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes00-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes0-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes=0-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes@0-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 9-7/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes -7/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 9-/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 9-X/" + START_LEN + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-5/" + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-5/0x5" + CRLF, Boolean.FALSE, START_TEXT, Boolean.TRUE });
        // Valid partial PUT but partial PUT is disabled
        parameterSets.add(new Object[] {
                "Content-Range: bytes 0-" + PATCH_LEN + "/" + START_LEN + CRLF, Boolean.TRUE, START_TEXT, Boolean.FALSE });

        return parameterSets;
    }


    private File tempDocBase;

    @Parameter(0)
    public String contentRangeHeader;

    @Parameter(1)
    public Boolean contentRangeHeaderValid;

    @Parameter(2)
    public String expectedEndText;

    @Parameter(3)
    public boolean allowPartialPut;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        tempDocBase = Files.createTempDirectory(getTemporaryDirectory().toPath(), "put").toFile();
    }


    /*
     * Replaces the text at the start of START_TEXT with PATCH_TEXT.
     */
    @Test
    public void testPut() throws Exception {
        // Configure a web app with a read/write default servlet
        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        w.addInitParameter("allowPartialPut", Boolean.toString(allowPartialPut));
        ctxt.addServletMappingDecoded("/", "default");

        tomcat.start();

        // Disable caching
        ctxt.getResources().setCachingAllowed(false);

        // Full PUT
        PutClient putClient = new PutClient(getPort());

        putClient.setRequest(new String[] {
                "PUT /test.txt HTTP/1.1" + CRLF +
                "Host: localhost:" + getPort() + CRLF +
                "Content-Length: " + START_LEN + CRLF +
                CRLF +
                START_TEXT
        });
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue(putClient.isResponse201());
        putClient.disconnect();

        putClient.reset();

        // Partial PUT
        putClient.connect();
        putClient.setRequest(new String[] {
                "PUT /test.txt HTTP/1.1" + CRLF +
                "Host: localhost:" + getPort() + CRLF +
                contentRangeHeader +
                "Content-Length: " + PATCH_LEN + CRLF +
                CRLF +
                PATCH_TEXT
        });
        putClient.processRequest(false);
        if (contentRangeHeaderValid == null) {
            // Not present (so will do a full PUT, replacing the existing)
            Assert.assertTrue(putClient.isResponse204());
        } else if (contentRangeHeaderValid.booleanValue() && allowPartialPut) {
            // Valid
            Assert.assertTrue(putClient.isResponse204());
        } else {
            // Not valid
            Assert.assertTrue(putClient.isResponse400());
        }

        // Check for the final resource
        String path = "http://localhost:" + getPort() + "/test.txt";
        ByteChunk responseBody = new ByteChunk();

        int rc = getUrl(path, responseBody, null);

        Assert.assertEquals(200,  rc);
        Assert.assertEquals(expectedEndText, responseBody.toString());
    }


    /*
     * CVE-2025-24813: the partial PUT temp file used to be named by replacing
     * '/' with '.' in the request path, so a request to /evil.session left a
     * predictable ".evil.session" file sitting in the temp dir (and only
     * ever cleaned up via deleteOnExit(), i.e. never during normal
     * operation). Verify the temp file name is no longer path-derived and is
     * actually removed once the request has finished.
     */
    @Test
    public void testPartialPutDoesNotUsePredictableTempFileName() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        w.addInitParameter("allowPartialPut", "true");
        ctxt.addServletMappingDecoded("/", "default");

        tomcat.start();
        ctxt.getResources().setCachingAllowed(false);

        File tempDir = (File) ctxt.getServletContext().getAttribute(ServletContext.TEMPDIR);

        String requestPath = "evil.session";
        String predictableName = "." + requestPath.replace('/', '.');

        byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 'A');

        PutClient putClient = new PutClient(getPort());
        putClient.setRequest(new String[] {
                "PUT /" + requestPath + " HTTP/1.1" + CRLF +
                "Host: localhost:" + getPort() + CRLF +
                "Content-Range: bytes 0-" + (payload.length - 1) + "/" + payload.length + CRLF +
                "Content-Length: " + payload.length + CRLF +
                CRLF +
                new String(payload, StandardCharsets.ISO_8859_1)
        });
        putClient.connect();
        putClient.processRequest(false);
        Assert.assertTrue(putClient.isResponse201());
        putClient.disconnect();

        File predictable = new File(tempDir, predictableName);
        Assert.assertFalse(
                "Predictable, path-derived temp file was left behind - CVE-2025-24813 regression: "
                        + predictable.getAbsolutePath(),
                predictable.exists());

        File[] leftoverPutParts = tempDir.listFiles((dir, name) -> name.startsWith("put-part-"));
        Assert.assertTrue(
                "Partial PUT temp file was not cleaned up after the request completed",
                leftoverPutParts == null || leftoverPutParts.length == 0);
    }


    @Override
    public void tearDown() {
        ExpandWar.deleteDir(tempDocBase, false);
    }


    private static class PutClient extends SimpleHttpClient {

        PutClient(int port) {
            setPort(port);
        }


        @Override
        public boolean isResponseBodyOK() {
            return false;
        }
    }
}
