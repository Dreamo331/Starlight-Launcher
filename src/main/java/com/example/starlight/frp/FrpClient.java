// 星光启动器\src\main\java\frp\FrpClient.java
package com.example.starlight.frp;
import com.example.starlight.config.Endpoints;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FrpClient {
    private static final String DEFAULT_CTRL_HOST = Endpoints.frpControlHost();
    private static final int DEFAULT_CTRL_PORT = Endpoints.frpControlPort();

    private final String ctrlHost;
    private final int ctrlPort;
    private final String localMcHost;
    private final int localMcPort;
    private final String username;

    private Socket ctrlSocket;
    private volatile boolean running;
    private ScheduledExecutorService heartbeatExecutor;
    private long lastHeartbeatAck;
    private ConnectionCallback callback;
    private int assignedTunnelPort = -1;
    private Thread receiveThread;
    private Thread localForwardThread;
    private final Object reconnectLock = new Object();
    private volatile boolean isReconnecting = false;

    public interface ConnectionCallback {
        void onStateChange(State state);
        void onError(String error);
        void onConnected(int tunnelPort);
        void onDisconnected();
    }

    public enum State { DISCONNECTED, CONNECTING, CHECKING_LOCAL_MC, HANDSHAKING, CONNECTED, ERROR }

    // 构造函数重载
    public FrpClient(String localMcAddr) {
        this(DEFAULT_CTRL_HOST, DEFAULT_CTRL_PORT, localMcAddr, "Player");
    }
    public FrpClient(String localMcHost, int localMcPort, String username) {
        this(DEFAULT_CTRL_HOST, DEFAULT_CTRL_PORT, localMcHost, localMcPort, username);
    }
    public FrpClient(String ctrlHost, int ctrlPort, String localMcAddr, String username) {
        this.ctrlHost = ctrlHost;
        this.ctrlPort = ctrlPort;
        String[] parts = localMcAddr.split(":");
        this.localMcHost = parts[0];
        this.localMcPort = Integer.parseInt(parts[1]);
        this.username = username;
    }
    public FrpClient(String ctrlHost, int ctrlPort, String localMcHost, int localMcPort, String username) {
        this.ctrlHost = ctrlHost;
        this.ctrlPort = ctrlPort;
        this.localMcHost = localMcHost;
        this.localMcPort = localMcPort;
        this.username = username;
    }

    public void setCallback(ConnectionCallback cb) { this.callback = cb; }
    public State getState() { return running ? State.CONNECTED : State.DISCONNECTED; }
    public int getTunnelPort() { return assignedTunnelPort; }

    public void connect() {
        synchronized (reconnectLock) {
            if (running || isReconnecting) {
                System.out.println("[FrpClient] Already connecting, skipping duplicate connect request");
                return;
            }
            isReconnecting = true;
        }
        
        try {
            doConnect();
        } finally {
            isReconnecting = false;
        }
    }
    
    private void doConnect() {
        setState(State.CONNECTING);
        try {
            // 检查本地MC服务器是否可达，避免在服务器无法访问时浪费资源建立隧道
            setState(State.CHECKING_LOCAL_MC);
            if (!checkLocalMcServer()) {
                throw new IOException("Cannot connect to local MC server " + localMcHost + ":" + localMcPort + ", please make sure the MC server is running");
            }
            System.out.println("[FrpClient] Local MC server check passed");
            
            // 建立控制连接
            ctrlSocket = new Socket();
            ctrlSocket.connect(new InetSocketAddress(ctrlHost, ctrlPort), 10000);
            ctrlSocket.setTcpNoDelay(true);
            ctrlSocket.setKeepAlive(true);
            
            // 进行握手认证
            if (!handshake()) throw new IOException("Handshake failed");
            
            // 成功建立连接
            running = true;
            setState(State.CONNECTED);
            if (callback != null) callback.onConnected(assignedTunnelPort);
            
            // 启动本地转发线程
            localForwardThread = new Thread(this::startLocalForwarding);
            localForwardThread.setDaemon(true);
            localForwardThread.start();
            
            startHeartbeat();
            receiveThread = new Thread(this::receiveLoop);
            receiveThread.setDaemon(true);
            receiveThread.start();

        } catch (IOException e) {
            setState(State.ERROR);
            if (callback != null) callback.onError("连接失败: " + e.getMessage());
            cleanup();
        }
    }
    

    // 避免在服务器无法访问时浪费资源建立隧道
    private boolean checkLocalMcServer() {
        try (Socket testSocket = new Socket()) {
            testSocket.connect(new InetSocketAddress(localMcHost, localMcPort), 5000);
            System.out.println("[FrpClient] Local MC server " + localMcHost + ":" + localMcPort + " connected");
            return true;
        } catch (IOException e) {
            System.err.println("[FrpClient] Local MC server check failed: " + e.getMessage());
            return false;
        }
    }

    private boolean handshake() throws IOException {
        setState(State.HANDSHAKING);
        InputStream in = ctrlSocket.getInputStream();
        OutputStream out = ctrlSocket.getOutputStream();
        String info = "user:" + username + "|local:" + localMcHost + ":" + localMcPort;
        byte[] data = info.getBytes("UTF-8");
        byte[] header = Protocol.createHeader(Protocol.MSG_HELLO, data.length);
        out.write(header);
        out.write(data);
        out.flush();
        byte[] respHeader = new byte[12];
        if (!readFully(in, respHeader) || !Protocol.validate(respHeader)) return false;
        if (Protocol.type(respHeader) == Protocol.MSG_ERROR) {
            int len = Protocol.length(respHeader);
            byte[] err = new byte[len];
            readFully(in, err);
            throw new IOException("Server error: " + new String(err));
        }
        if (Protocol.type(respHeader) != Protocol.MSG_HELLO_ACK) return false;
        int len = Protocol.length(respHeader);
        byte[] respData = new byte[len];
        if (!readFully(in, respData)) return false;
        String resp = new String(respData, "UTF-8");
        if (resp.startsWith("port:")) {
            assignedTunnelPort = Integer.parseInt(resp.substring(5));
            System.out.println("[FrpClient] Tunnel port assigned: " + assignedTunnelPort);
            return true;
        }
        return false;
    }

    private void startLocalForwarding() {
        int retryCount = 0;
        final int MAX_RETRIES = 5;
        final long RETRY_INTERVAL = 2000;
        
        while (running && retryCount < MAX_RETRIES) {
            Socket mcSocket = null;
            try {
                System.out.println("[FrpClient] Connecting to local MC " + localMcHost + ":" + localMcPort);
                mcSocket = new Socket();
                mcSocket.connect(new InetSocketAddress(localMcHost, localMcPort), 5000);
                mcSocket.setTcpNoDelay(true);
                System.out.println("[FrpClient] Connected to local MC server");
                
                InputStream mcIn = mcSocket.getInputStream();
                OutputStream mcOut = mcSocket.getOutputStream();
                
                synchronized (this) {
                    this.currentMcOut = mcOut;
                }
                retryCount = 0;
                
                // 双向转发
                byte[] buf = new byte[8192];
                int n;
                while (running && (n = mcIn.read(buf)) != -1) {
                    byte[] data = Arrays.copyOf(buf, n);
                    byte[] h = Protocol.createHeader(Protocol.MSG_DATA, n);
                    
                    OutputStream ctrlOut;
                    synchronized (this) { ctrlOut = ctrlSocket != null ? ctrlSocket.getOutputStream() : null; }
                    
                    if (ctrlOut != null) {
                        synchronized (ctrlOut) {
                            ctrlOut.write(h);
                            ctrlOut.write(data);
                            ctrlOut.flush();
                        }
                    }
                }
                
                System.out.println("[FrpClient] MC connection lost, reconnecting...");
                
            } catch (IOException e) {
                if (running) {
                    System.err.println("[FrpClient] Local MC connection failed: " + e.getMessage());
                    retryCount++;
                }
            } finally {
                synchronized (this) { currentMcOut = null; }
                if (mcSocket != null) {
                    try { mcSocket.close(); } catch (IOException ignored) {}
                }
            }
            
            if (running && retryCount < MAX_RETRIES) {
                try { Thread.sleep(RETRY_INTERVAL); } catch (InterruptedException ignored) {}
            }
        }
        
        if (retryCount >= MAX_RETRIES) {
            System.err.println("[FrpClient] Local MC connection retry limit exceeded, closing tunnel");
            disconnect();
        }
    }

    private OutputStream currentMcOut = null;

    private void receiveLoop() {
        try {
            InputStream in = ctrlSocket.getInputStream();
            byte[] header = new byte[12];
            while (running) {
                if (!readFully(in, header)) break;
                if (!Protocol.validate(header)) continue;
                byte type = Protocol.type(header);
                int len = Protocol.length(header);
                
                if (type == Protocol.MSG_DATA) {
                    byte[] data = new byte[len];
                    if (!readFully(in, data)) break;
                    OutputStream mcOut;
                    synchronized (this) { mcOut = currentMcOut; }
                    if (mcOut != null) {
                        mcOut.write(data);
                        mcOut.flush();
                    } else {
                        System.out.println("[FrpClient] WARN: currentMcOut is null, dropping data length: " + len);
                    }
                } else if (type == Protocol.MSG_HEARTBEAT_ACK) {
                    lastHeartbeatAck = System.currentTimeMillis();
                } else if (type == Protocol.MSG_HEARTBEAT) {
                    byte[] ack = Protocol.createHeader(Protocol.MSG_HEARTBEAT_ACK, 0);
                    synchronized (ctrlSocket.getOutputStream()) {
                        ctrlSocket.getOutputStream().write(ack);
                        ctrlSocket.getOutputStream().flush();
                    }
                    lastHeartbeatAck = System.currentTimeMillis();
                } else if (type == Protocol.MSG_CLOSE) {
                    System.out.println("[FrpClient] Server requested close");
                    disconnect();
                    return;
                } else if (type == Protocol.MSG_ERROR) {
                    byte[] err = new byte[len];
                    readFully(in, err);
                    System.err.println("[FrpClient] Server error: " + new String(err));
                } else {
                    if (len > 0) { byte[] skip = new byte[len]; readFully(in, skip); }
                }
            }
        } catch (IOException e) {
            if (running) System.err.println("[FrpClient] Receive loop exception: " + e.getMessage());
        } finally {
            if (running) disconnect();
        }
    }

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        lastHeartbeatAck = System.currentTimeMillis();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running) return;
            try {
                byte[] hb = Protocol.createHeader(Protocol.MSG_HEARTBEAT, 0);
                synchronized (ctrlSocket.getOutputStream()) {
                    ctrlSocket.getOutputStream().write(hb);
                    ctrlSocket.getOutputStream().flush();
                }
            } catch (IOException e) { disconnect(); }
        }, 30, 30, TimeUnit.SECONDS);
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running) return;
            if (System.currentTimeMillis() - lastHeartbeatAck > 90000) {
                System.err.println("[FrpClient] Heartbeat timeout");
                disconnect();
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    public void disconnect() {
        if (!running && ctrlSocket == null) return;
        running = false;
        cleanup();
    }
    
    private void cleanup() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        
        synchronized (this) {
            currentMcOut = null;
        }
        
        if (ctrlSocket != null) {
            try { 
                // 发送关闭消息给服务端
                try {
                    byte[] closeMsg = Protocol.createHeader(Protocol.MSG_CLOSE, 0);
                    ctrlSocket.getOutputStream().write(closeMsg);
                    ctrlSocket.getOutputStream().flush();
                } catch (IOException ignored) {}
                ctrlSocket.close(); 
            } catch (IOException ignored) {} 
            ctrlSocket = null; 
        }
        
        assignedTunnelPort = -1;
        setState(State.DISCONNECTED);
        if (callback != null) callback.onDisconnected();
    }

    private boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0, len = b.length;
        while (off < len) { int r = in.read(b, off, len - off); if (r == -1) return false; off += r; }
        return true;
    }

    private void setState(State s) {
        if (callback != null) callback.onStateChange(s);
    }

    public static FrpClient quickConnect(String localMcAddr) {
        FrpClient client = new FrpClient(localMcAddr);
        client.connect();
        return client;
    }

    public static void main(String[] args) {
        String localAddr = args.length > 0 ? args[0] : "127.0.0.1:25565";
        System.out.println("Starting FRP client, local MC address: " + localAddr);
        FrpClient client = new FrpClient(localAddr);
        client.setCallback(new ConnectionCallback() {
            @Override
            public void onStateChange(State s) { System.out.println("State: " + s); }
            @Override
            public void onError(String e) { System.err.println("Error: " + e); }
            @Override
            public void onConnected(int port) { 
                System.out.println("Tunnel established, public port: " + port); 
                System.out.println("Use the public IP and port to connect to the MC server");
                System.out.println("Tunnel address: " + DEFAULT_CTRL_HOST + ":" + port);
            }
            
            @Override
            public void onDisconnected() { System.out.println("Disconnected"); }
        });
        client.connect();
        System.out.println("Press Enter to exit...");
        try { System.in.read(); } catch (IOException ignored) {}
        client.disconnect();
    }

    static class Protocol {
        static final byte[] MAGIC = {0x4D, 0x43, 0x46, 0x52, 0x50};
        static final byte VERSION = 0x01;
        static final byte MSG_HELLO = 1, MSG_HELLO_ACK = 2, MSG_DATA = 3, MSG_HEARTBEAT = 4, MSG_HEARTBEAT_ACK = 5, MSG_CLOSE = 6, MSG_ERROR = 7;
        static byte[] createHeader(byte type, int len) {
            byte[] h = new byte[12];
            System.arraycopy(MAGIC, 0, h, 0, 5);
            h[5] = VERSION; h[6] = type;
            h[7] = (byte)(len >> 24); h[8] = (byte)(len >> 16); h[9] = (byte)(len >> 8); h[10] = (byte)len;
            return h;
        }
        static int length(byte[] h) { return ((h[7]&0xFF)<<24)|((h[8]&0xFF)<<16)|((h[9]&0xFF)<<8)|(h[10]&0xFF); }
        static boolean validate(byte[] h) { for (int i=0;i<5;i++) if (h[i]!=MAGIC[i]) return false; return h[5]==VERSION; }
        static byte type(byte[] h) { return h[6]; }
    }
}
