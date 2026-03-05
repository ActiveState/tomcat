import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class VerifyTomcatSecurity {

    public static void main(String[] args) throws Exception {
        System.out.println("--- Starting Tomcat Security Verification ---");

        // 1. Setup Embedded Tomcat
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);
        tomcat.getConnector(); // Trigger connector creation

        // Create a temporary directory for Tomcat to use
        File baseDir = new File("tomcat-temp-test");
        if (!baseDir.exists()) baseDir.mkdirs();
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        Context ctx = tomcat.addContext("", baseDir.getAbsolutePath());

        // 2. Enable DefaultServlet and explicitly allow PUT requests (readonly=false)
        org.apache.catalina.Wrapper defaultServlet = Tomcat.addServlet(ctx, "default", "org.apache.catalina.servlets.DefaultServlet");
        ctx.addServletMappingDecoded("/*", "default");
        defaultServlet.addInitParameter("readonly", "false");

        tomcat.start();
        System.out.println("Embedded Tomcat started on port 8080.");

        // 3. Run the Exploit Simulation
        testPartialPut(baseDir);

        // 4. Cleanup
        tomcat.stop();
        tomcat.destroy();
        System.out.println("--- Verification Complete ---");
    }

    private static void testPartialPut(File baseDir) throws Exception {
        System.out.println("\n[Testing CVE-2025-24813: Partial PUT Path Equivalence]");
        
        // The attacker targets this URI
        URL url = new URL("http://localhost:8080/evil/payload.session");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        
        // The Content-Range header forces Tomcat to use the executePartialPut() logic
        conn.setRequestProperty("Content-Range", "bytes 0-9/100");

        try (OutputStream os = conn.getOutputStream()) {
            os.write("malicious_".getBytes());
        }

        int responseCode = conn.getResponseCode();
        System.out.println("Server HTTP Response: " + responseCode);

        // In the vulnerable version, the URI "/evil/payload.session" is converted 
        // to a temp file named ".evil.payload.session"
        File workDir = new File(baseDir, "work/Tomcat/localhost/ROOT");
        File vulnerableFile = new File(workDir, ".evil.payload.session");

        if (vulnerableFile.exists()) {
            System.out.println("[VULNERABLE] Predictable temp file was created: " + vulnerableFile.getAbsolutePath());
        } else {
            System.out.println("[SECURE] The predictable temp file does NOT exist. Patch is active!");
        }
    }
}