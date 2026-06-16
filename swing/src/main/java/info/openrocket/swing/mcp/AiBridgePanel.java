package info.openrocket.swing.mcp;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.miginfocom.swing.MigLayout;

/**
 * Floating companion window for the AI bridge. Lets the user start/stop the MCP server, shows the
 * connect command, and streams a live feed of every tool an agent invokes so you can watch the AI
 * work alongside the design in the main window.
 */
public class AiBridgePanel extends JFrame implements McpBridge.Listener {

	private static AiBridgePanel openInstance;

	private final McpBridge bridge = McpBridge.getInstance();
	private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss");

	private final JLabel statusLabel = new JLabel();
	private final JTextArea connectField = new JTextArea(2, 40);
	private final JSpinner portSpinner = new JSpinner(
			new SpinnerNumberModel(McpBridge.DEFAULT_PORT, 1024, 65535, 1));
	private final JPasswordField tokenField = new JPasswordField(16);
	private final JButton toggleButton = new JButton();
	private final JTextArea logArea = new JTextArea();

	/** Open (or focus) the single shared panel. */
	public static void showPanel() {
		if (openInstance == null) {
			openInstance = new AiBridgePanel();
		}
		openInstance.setVisible(true);
		openInstance.toFront();
		openInstance.requestFocus();
	}

	private AiBridgePanel() {
		super("OpenRocket AI Copilot");
		setDefaultCloseOperation(HIDE_ON_CLOSE);

		JPanel content = new JPanel(new MigLayout("fill, insets 10", "[grow]", "[][][][grow]"));

		// Status + controls row
		JPanel controls = new JPanel(new MigLayout("insets 0", "[][][]push[][]"));
		controls.add(new JLabel("Port:"));
		portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#"));
		controls.add(portSpinner);
		controls.add(new JLabel("Token (optional):"));
		controls.add(tokenField, "growx");
		toggleButton.addActionListener(this::onToggle);
		controls.add(toggleButton, "wrap");
		content.add(controls, "growx, wrap");

		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
		content.add(statusLabel, "growx, wrap");

		// Connect command
		JPanel connectPanel = new JPanel(new BorderLayout(6, 0));
		connectField.setEditable(false);
		connectField.setLineWrap(true);
		connectField.setWrapStyleWord(false);
		connectField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		connectPanel.add(new JScrollPane(connectField), BorderLayout.CENTER);
		JButton copyButton = new JButton("Copy");
		copyButton.addActionListener(e -> {
			Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(connectField.getText()), null);
		});
		connectPanel.add(copyButton, BorderLayout.EAST);
		content.add(connectPanel, "growx, wrap");

		// Activity log
		logArea.setEditable(false);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JScrollPane logScroll = new JScrollPane(logArea);
		logScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Live activity"));
		content.add(logScroll, "grow");

		setContentPane(content);
		setPreferredSize(new Dimension(620, 460));
		pack();
		setLocationRelativeTo(null);

		// Esc closes the window.
		content.registerKeyboardAction(e -> setVisible(false),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		bridge.addListener(this);
		refreshState(bridge.isRunning());
	}

	private void onToggle(ActionEvent e) {
		if (bridge.isRunning()) {
			bridge.stop();
		} else {
			try {
				int port = (Integer) portSpinner.getValue();
				String token = new String(tokenField.getPassword());
				bridge.start(port, token);
				append(null, "Bridge started on " + bridge.getUrl(), true);
			} catch (Exception ex) {
				append(null, "Failed to start: " + ex.getMessage(), false);
			}
		}
	}

	private void refreshState(boolean running) {
		toggleButton.setText(running ? "Stop" : "Start");
		portSpinner.setEnabled(!running);
		tokenField.setEnabled(!running);
		if (running) {
			statusLabel.setText("●  Running — " + bridge.getUrl());
			statusLabel.setForeground(new Color(0x2E, 0x7D, 0x32));
			connectField.setText(buildConnectCommand());
		} else {
			statusLabel.setText("○  Stopped");
			statusLabel.setForeground(Color.GRAY);
			connectField.setText("Start the bridge to get the connection command.");
		}
	}

	private String buildConnectCommand() {
		StringBuilder sb = new StringBuilder("claude mcp add --transport http openrocket ");
		sb.append(bridge.getUrl());
		String token = bridge.getToken();
		if (token != null) {
			sb.append(" --header \"Authorization: Bearer ").append(token).append("\"");
		}
		return sb.toString();
	}

	private void append(String tool, String detail, boolean ok) {
		String prefix = tool == null ? "" : (tool + "  ");
		String mark = ok ? "✓" : "✗";
		logArea.append(clock.format(new Date()) + "  " + mark + "  " + prefix + detail + "\n");
		logArea.setCaretPosition(logArea.getDocument().getLength());
	}

	// --- McpBridge.Listener (may be called off the EDT) ---

	@Override
	public void onActivity(McpBridge.Activity a) {
		SwingUtilities.invokeLater(() -> append(a.tool(), a.detail(), a.ok()));
	}

	@Override
	public void onStateChanged(boolean running) {
		SwingUtilities.invokeLater(() -> refreshState(running));
	}
}
