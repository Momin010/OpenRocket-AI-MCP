package info.openrocket.swing.mcp;

import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle and event hub for the OpenRocket AI (MCP) bridge.
 *
 * <p>Owns a loopback-bound {@link HttpServer} that speaks the MCP Streamable-HTTP transport, and
 * broadcasts an activity log so the {@link AiBridgePanel} can show, in real time, what a connected
 * agent is doing.</p>
 */
public final class McpBridge {

	private static final Logger log = LoggerFactory.getLogger(McpBridge.class);

	public static final int DEFAULT_PORT = 8723;

	private static final McpBridge INSTANCE = new McpBridge();

	/** A single line in the live activity feed. */
	public record Activity(long timeMillis, String tool, String detail, boolean ok) {
	}

	/** Listener for activity-feed lines and server state changes. */
	public interface Listener {
		void onActivity(Activity activity);

		void onStateChanged(boolean running);
	}

	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final OpenRocketTools tools = new OpenRocketTools();
	private final String sessionId = UUID.randomUUID().toString();

	private HttpServer server;
	private int port = DEFAULT_PORT;
	private String token = null;

	private McpBridge() {
	}

	public static McpBridge getInstance() {
		return INSTANCE;
	}

	OpenRocketTools getTools() {
		return tools;
	}

	String getSessionId() {
		return sessionId;
	}

	public synchronized boolean isRunning() {
		return server != null;
	}

	public synchronized int getPort() {
		return port;
	}

	public synchronized String getToken() {
		return token;
	}

	public String getUrl() {
		return "http://127.0.0.1:" + getPort() + "/mcp";
	}

	public synchronized void start(int port, String token) throws IOException {
		if (server != null) {
			throw new IllegalStateException("AI bridge is already running");
		}
		this.port = port;
		this.token = (token == null || token.isBlank()) ? null : token.trim();

		HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		s.createContext("/mcp", new McpHttpHandler(this));
		s.setExecutor(Executors.newCachedThreadPool(daemonFactory()));
		s.start();
		this.server = s;
		log.info("OpenRocket MCP bridge listening on {}", getUrl());
		fireState(true);
	}

	public synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
			log.info("OpenRocket MCP bridge stopped");
			fireState(false);
		}
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	void fireActivity(String tool, String detail, boolean ok) {
		Activity a = new Activity(System.currentTimeMillis(), tool, detail, ok);
		for (Listener l : listeners) {
			try {
				l.onActivity(a);
			} catch (Exception e) {
				log.warn("Activity listener failed", e);
			}
		}
	}

	private void fireState(boolean running) {
		for (Listener l : listeners) {
			try {
				l.onStateChanged(running);
			} catch (Exception e) {
				log.warn("State listener failed", e);
			}
		}
	}

	private static ThreadFactory daemonFactory() {
		final AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, "mcp-bridge-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
