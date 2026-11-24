package rabbitrace.automation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The automation client: owns framing and argument
 * parsing, sends one command line, prints one reply line. PowerShell never parses
 * the JSON — the tools are one-line shims over this class.
 */
public final class Ctl {

    private Ctl() {
    }

    public static void main(String[] args) throws Exception {
        String portText = System.getProperty("automation.port");
        if (portText == null || portText.isBlank()) {
            System.err.println("err no -Dautomation.port");
            System.exit(2);
        }
        String command = String.join(" ", args).trim();
        if (command.isEmpty()) {
            System.err.println("err empty command");
            System.exit(2);
        }
        int port = Integer.parseInt(portText.trim());
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.write(command);
            out.newLine();
            out.flush();
            String reply = in.readLine();
            if (reply == null) {
                System.out.println("err connection closed without a reply");
                System.exit(1);
            }
            System.out.println(reply);
            if (reply.startsWith("err")) {
                System.exit(1);
            }
        }
    }
}
