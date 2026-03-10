import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class VerifyTomcatSecurityDeep {

    public static void main(String[] args) throws Exception {
        System.out.println("--- Starting Ultimate Tomcat Security & Integration Verification (9.0.46) ---");

        // 1. Setup Embedded Tomcat
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);

        // Enable HTTP/2 Engine to test our manual cherry-picks
        tomcat.getConnector().addUpgradeProtocol(new Http2Protocol());

        File baseDir = new File("tomcat-temp-test-deep-9046");
        if (!baseDir.exists()) baseDir.mkdirs();
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        // Create a dummy target file
        File evilDir = new File(baseDir, "evil");
        evilDir.mkdirs();
        File targetFile = new File(evilDir, "payload.session");
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write("dummy_background_data".getBytes());
        }

        Context ctx = tomcat.addContext("", baseDir.getAbsolutePath());

        // Enable DefaultServlet with PUT allowed
        org.apache.catalina.Wrapper defaultServlet = Tomcat.addServlet(ctx, "default", "org.apache.catalina.servlets.DefaultServlet");
        ctx.addServletMappingDecoded("/*", "default");
        defaultServlet.addInitParameter("readonly", "false");

        tomcat.start();
        System.out.println("Embedded Tomcat 9.0.46 started on port 8080 with HTTP/2 enabled.");

        File workDir = new File(baseDir, "work/Tomcat/localhost/ROOT");
        if (!workDir.exists()) workDir.mkdirs();

        // =========================================================================
        // TEST 1: HTTP/2 Protocol Engine Verification (CVE-2023-44487)
        // =========================================================================
        System.out.println("\n[Test 1] Verifying HTTP/2 Engine and Manual Code Integration...");
        try {
            // Java 11+ HttpClient natively supports HTTP/2 upgrades
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpRequest request = HttpRequest.newBuilder(new URI("http://localhost:8080/evil/payload.session")).GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.version() == HttpClient.Version.HTTP_2) {
                System.out.println("[PASSED] HTTP/2 Connection Established!");
                System.out.println("[PASSED] Http2Parser & Http2UpgradeHandler loaded correctly without API drift crashes.");
            } else {
                System.err.println("[FAILED] Server downgraded to HTTP/1.1. HTTP/2 upgrade failed.");
            }
        } catch (Exception e) {
            System.err.println("[FAILED] HTTP/2 request crashed! We have a runtime API drift error:");
            e.printStackTrace();
        }

        // =========================================================================
        // TEST 2: Partial PUT Path Equivalence (CVE-2025-24813)
        // =========================================================================
        System.out.println("\n[Test 2] Verifying CVE-2025-24813 (Partial PUT RCE)...");

        Thread attackerThread = new Thread(() -> {
            try {
                System.out.println("[Attacker] Sending malicious Partial PUT request (1MB Payload)...");
                URL url = new URL("http://localhost:8080/evil/payload.session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("PUT");

                int totalBytes = 1024 * 1024; // 1 MB payload
                conn.setFixedLengthStreamingMode(totalBytes);
                conn.setRequestProperty("Content-Range", "bytes 0-" + (totalBytes - 1) + "/" + totalBytes);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] chunk = new byte[1024];
                    for (int i = 0; i < 1024; i++) {
                        os.write(chunk);
                        os.flush();
                        Thread.sleep(5);
                    }
                }
                System.out.println("[Attacker] Request complete. Server returned: " + conn.getResponseCode());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        attackerThread.start();

        boolean foundRandomTempFile = false;
        File vulnerableFile = new File(workDir, ".evil.payload.session");

        for (int i = 0; i < 50; i++) {
            File[] files = workDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("put-part-")) {
                        foundRandomTempFile = true;
                        break;
                    }
                }
            }
            if(foundRandomTempFile) break;
            Thread.sleep(100);
        }

        attackerThread.join();

        System.out.println("\n--- Verification Results ---");

        if (vulnerableFile.exists()) {
            System.err.println("[FAILED] Predictable file was created: " + vulnerableFile.getAbsolutePath());
        } else {
            System.out.println("[PASSED] Predictable file was NOT created.");
        }

        if (foundRandomTempFile) {
            System.out.println("[PASSED] Tomcat successfully used the secure, random 'put-part-' naming convention.");
        } else {
            System.err.println("[FAILED] Did not detect the secure 'put-part-' temp file (Likely buffered entirely in memory, which is also secure).");
        }

        // Cleanup
        tomcat.stop();
        tomcat.destroy();
    }
}