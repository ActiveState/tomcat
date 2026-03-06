import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class VerifyTomcatSecurityDeep {

    public static void main(String[] args) throws Exception {
        System.out.println("--- Starting Enhanced Tomcat Security Verification ---");

        // 1. Setup Embedded Tomcat
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);
        tomcat.getConnector();

        File baseDir = new File("tomcat-temp-test-deep");
        if (!baseDir.exists()) baseDir.mkdirs();
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        // Create a dummy target file so Tomcat accepts the upload
        File evilDir = new File(baseDir, "evil");
        evilDir.mkdirs();
        File targetFile = new File(evilDir, "payload.session");
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write("dummy_background_data".getBytes());
        }

        Context ctx = tomcat.addContext("", baseDir.getAbsolutePath());

        // 2. Enable DefaultServlet with PUT allowed
        org.apache.catalina.Wrapper defaultServlet = Tomcat.addServlet(ctx, "default", "org.apache.catalina.servlets.DefaultServlet");
        ctx.addServletMappingDecoded("/*", "default");
        defaultServlet.addInitParameter("readonly", "false");

        tomcat.start();
        System.out.println("Embedded Tomcat started on port 8080.");

        // 3. Locate the TEMPDIR where Tomcat stores upload chunks
        File workDir = new File(baseDir, "work/Tomcat/localhost/ROOT");
        if (!workDir.exists()) workDir.mkdirs();

        // 4. Run the Exploit Simulation in a separate thread
        Thread attackerThread = new Thread(() -> {
            try {
                System.out.println("[Attacker] Sending malicious Partial PUT request (Large Payload)...");
                URL url = new URL("http://localhost:8080/evil/payload.session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("PUT");

                // Force streaming mode to avoid Java buffering the whole thing
                int totalBytes = 1024 * 1024; // 1 MB payload
                conn.setFixedLengthStreamingMode(totalBytes);

                // Content-Range triggers the vulnerable executePartialPut logic
                conn.setRequestProperty("Content-Range", "bytes 0-" + (totalBytes - 1) + "/" + totalBytes);

                // Send data slowly to force Tomcat to spool to disk
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] chunk = new byte[1024]; // 1KB chunks
                    for (int i = 0; i < 1024; i++) { // Send 1024 chunks
                        os.write(chunk);
                        os.flush();
                        Thread.sleep(5); // Sleep 5ms between chunks
                    }
                }
                System.out.println("[Attacker] Request complete. Server returned: " + conn.getResponseCode());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        attackerThread.start();

        // 5. Monitor the temp directory while the request is processing
        boolean foundRandomTempFile = false;
        File vulnerableFile = new File(workDir, ".evil.payload.session");

        System.out.println("[Monitor] Scanning " + workDir.getAbsolutePath() + " for files...");

        // Check the directory while the attacker thread is writing
        for (int i = 0; i < 50; i++) {
            File[] files = workDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("put-part-")) {
                        foundRandomTempFile = true;
                        System.out.println("  -> [SECURE] Found randomly generated temp file: " + f.getName());
                        break; // We found it, no need to keep printing it
                    }
                }
            }
            if(foundRandomTempFile) break; // Exit loop early once found
            Thread.sleep(100);
        }

        attackerThread.join(); // Wait for the request to finish

        // 6. Final Security Assertions
        System.out.println("\n--- Verification Results ---");

        if (vulnerableFile.exists()) {
            System.err.println("[FAILED] Predictable file was created: " + vulnerableFile.getAbsolutePath());
        } else {
            System.out.println("[PASSED] Predictable file was NOT created.");
        }

        if (foundRandomTempFile) {
            System.out.println("[PASSED] Tomcat successfully used the secure, random 'put-part-' naming convention.");
        } else {
            System.err.println("[FAILED] Did not detect the secure 'put-part-' temp file.");
        }

        // Verify the finally block deleted the temp file
        boolean anyTempFilesLeft = false;
        File[] remainingFiles = workDir.listFiles();
        if (remainingFiles != null) {
            for (File f : remainingFiles) {
                if (f.getName().startsWith("put-part-")) {
                    anyTempFilesLeft = true;
                }
            }
        }

        if (anyTempFilesLeft) {
            System.err.println("[FAILED] Tomcat did not delete the temporary file after the request finished!");
        } else {
            System.out.println("[PASSED] Tomcat immediately deleted the temporary chunk file (finally block works).");
        }

        // 7. Cleanup
        tomcat.stop();
        tomcat.destroy();
    }
}