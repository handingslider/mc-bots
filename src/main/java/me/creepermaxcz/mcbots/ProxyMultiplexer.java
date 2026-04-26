package me.creepermaxcz.mcbots;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyMultiplexer {

    private final ServerSocket serverSocket;
    private final List<InetSocketAddress> proxies;
    private final String proxyType;
    private final AtomicInteger proxyIndex = new AtomicInteger(0);

    public ProxyMultiplexer(List<InetSocketAddress> proxies, String proxyType) throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.proxies = new ArrayList<>(proxies);
        this.proxyType = proxyType;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleSocks5(clientSocket)).start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        Log.error("Multiplexer accept failed: " + e.getMessage());
                    }
                }
            }
        }, "Multiplexer-Acceptor").start();
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void handleSocks5(Socket clientSocket) {
        try {
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());

            // 1. Version identifier/method selection message
            int version = in.readUnsignedByte();
            if (version != 5) {
                clientSocket.close();
                return;
            }
            int nMethods = in.readUnsignedByte();
            byte[] methods = new byte[nMethods];
            in.readFully(methods);

            // Respond with "no authentication required"
            out.writeByte(5);
            out.writeByte(0);

            // 2. Request message
            version = in.readUnsignedByte();
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte(); // RSV
            int atyp = in.readUnsignedByte();

            String targetHost;
            int targetPort;

            if (atyp == 1) { // IPv4
                byte[] addr = new byte[4];
                in.readFully(addr);
                targetHost = InetAddress.getByAddress(addr).getHostAddress();
            } else if (atyp == 3) { // Domain name
                int len = in.readUnsignedByte();
                byte[] addr = new byte[len];
                in.readFully(addr);
                targetHost = new String(addr);
            } else {
                clientSocket.close();
                return;
            }
            targetPort = in.readUnsignedShort();

            if (cmd != 1) { // CONNECT
                clientSocket.close();
                return;
            }

            // Pick a real proxy
            int index = proxyIndex.getAndIncrement() % proxies.size();
            InetSocketAddress realProxyAddr = proxies.get(index);

            // Connect to the real proxy and then to the target
            Proxy.Type type = proxyType.equalsIgnoreCase("SOCKS") || proxyType.equalsIgnoreCase("SOCKS5") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            Proxy realProxy = new Proxy(type, realProxyAddr);

            Socket targetSocket = new Socket(realProxy);
            try {
                targetSocket.connect(new InetSocketAddress(targetHost, targetPort), 10000);
            } catch (IOException e) {
                // Connection failed
                out.writeByte(5);
                out.writeByte(1); // general SOCKS server failure
                out.writeByte(0);
                out.writeByte(1);
                out.writeInt(0);
                out.writeShort(0);
                clientSocket.close();
                return;
            }

            // Connection successful
            out.writeByte(5);
            out.writeByte(0); // success
            out.writeByte(0);
            out.writeByte(1);
            out.writeInt(0);
            out.writeShort(0);

            // Tunnel data
            Thread t1 = new Thread(() -> relay(clientSocket, targetSocket));
            Thread t2 = new Thread(() -> relay(targetSocket, clientSocket));
            t1.start();
            t2.start();

        } catch (IOException e) {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void relay(Socket source, Socket dest) {
        try (InputStream in = source.getInputStream();
             OutputStream out = dest.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
        } finally {
            try {
                source.close();
            } catch (IOException ignored) {}
            try {
                dest.close();
            } catch (IOException ignored) {}
        }
    }
}
