/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.net.SocketException;

import org.junit.Assert;
import org.junit.Test;

/*
 * CVE-2023-44487 (HTTP/2 Rapid Reset): opening a stream and resetting it
 * before the server responds used to cost the attacker almost nothing while
 * the server still did real work per stream. The existing overhead-count
 * circuit breaker (from CVE-2019-0199) penalized settings/priority/ping
 * frames but not RST_STREAM. Verify a client that resets a stream it just
 * opened gets its connection closed for excessive overhead.
 */
public class TestHttp2RapidReset extends Http2TestBase {

    @Test
    public void testRapidResetClosesConnection() throws Exception {
        http2Connect();

        sendSimpleGetRequest(3);
        sendRst(3, Http2Error.CANCEL.getCode());

        // The response to the GET may still land on the wire before the
        // server notices the reset and closes the connection, so read
        // frames until the Goaway shows up (or the connection just drops).
        boolean sawGoaway = false;
        for (int i = 0; i < 20 && !sawGoaway; i++) {
            output.clearTrace();
            try {
                parser.readFrame(true);
            } catch (SocketException se) {
                // Some platform / connector combinations close the TCP
                // connection before the client reads the Goaway frame.
                break;
            }
            if (output.getTrace().contains("Goaway")) {
                sawGoaway = true;
                Assert.assertTrue(output.getTrace(), output.getTrace().contains(
                        "[" + Http2Error.ENHANCE_YOUR_CALM.getCode() + "]"));
            }
        }

        Assert.assertTrue("Connection was not closed for excessive RST overhead - CVE-2023-44487 regression",
                sawGoaway);
    }
}
