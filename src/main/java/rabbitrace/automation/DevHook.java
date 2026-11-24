package rabbitrace.automation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The dev-only loopback hook: opt-in via
 * {@code -Dautomation.port}, bound to 127.0.0.1, UTF-8, {@code \n}-terminated, one
 * line in and one line out, connection closed by the server. Replies start with
 * {@code ok} or {@code err}.
 */
public final class DevHook {

    public interface Handler {
        String handle(String line) throws Exception;
    }

    private DevHook() {
    }

    /**
     * Starts the hook when {@code -Dautomation.port} is set and returns true.
     *
     * @param onQuit run after an {@code ok bye} reply has been flushed
     */
    public static boolean startIfEnabled(Handler handler, Runnable onQuit) {
        String portText = System.getProperty("automation.port");
        if (portText == null || portText.isBlank()) {
            return false;
        }
        int port = Integer.parseInt(portText.trim());
        Thread server = new Thread(() -> serve(port, handler, onQuit), "automation");
        server.setDaemon(true);
        server.start();
        System.out.println("automation listening 127.0.0.1:" + port);
        return true;
    }

    private static void serve(int port, Handler handler, Runnable onQuit) {
        try (ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter out = new BufferedWriter(
                             new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    String line = in.readLine();
                    String reply = line == null ? "err empty request" : run(line.trim(), handler);
                    out.write(reply);
                    out.newLine();
                    out.flush();
                    if ("ok bye".equals(reply)) {
                        onQuit.run();
                        System.exit(0);
                    }
                } catch (Exception e) {
                    System.err.println("automation: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("automation could not listen on 127.0.0.1:" + port + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static String run(String line, Handler handler) {
        if (line.isEmpty()) {
            return "err empty command";
        }
        try {
            return handler.handle(line);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "err " + message;
        }
    }
}
