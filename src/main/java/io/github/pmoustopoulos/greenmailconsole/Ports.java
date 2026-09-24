package io.github.pmoustopoulos.greenmailconsole;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Port probing shared by the SMTP server and the standalone console server. */
final class Ports {

    private Ports() {
    }

    /** True if a TCP server socket can currently be bound on the given address and port. */
    static boolean isFree(InetAddress address, int port) {
        if (port == 0) {
            return true; // 0 means "let the OS pick", which is always available
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(address, port), 1);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Asks the OS for a currently-free TCP port. */
    static int findFree() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("GreenMail console: could not find a free port", ex);
        }
    }
}
