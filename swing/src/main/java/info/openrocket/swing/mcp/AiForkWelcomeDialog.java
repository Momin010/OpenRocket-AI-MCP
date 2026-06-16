package info.openrocket.swing.mcp;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.prefs.Preferences;

import info.openrocket.core.util.BuildProperties;

/**
 * First-launch announcement that this build is an AI-integrated fork of OpenRocket. Shown once
 * per version (tracked in user {@link Preferences}); offers to open the AI Copilot bridge panel.
 */
public final class AiForkWelcomeDialog extends JDialog {

	private static final String PREF_NODE = "info/openrocket/ai";
	private static final String SHOWN_KEY = "aiForkWelcomeShownVersion";

	private AiForkWelcomeDialog(Frame owner) {
		super(owner, "OpenRocket + AI", true);

		JEditorPane text = new JEditorPane("text/html",
				"<html><body style='font-family:sans-serif; width:430px;'>"
				+ "<h2>🚀 OpenRocket <span style='color:#2E7D32;'>+ AI</span></h2>"
				+ "<p>This is an <b>AI-integrated fork</b> of OpenRocket. It is the same great "
				+ "rocket simulator, released under the same <b>GNU GPL v3</b> license, with one big "
				+ "addition:</p>"
				+ "<p><b>An AI Copilot bridge (MCP server)</b> that lets AI agents such as "
				+ "Claude Code design, simulate, analyse, optimise and even screenshot and "
				+ "fly your rockets — live, in this window.</p>"
				+ "<p>Open it any time from <b>Tools &rarr; AI Copilot (MCP bridge)</b>, click "
				+ "<b>Start</b>, then connect your agent with:</p>"
				+ "<pre style='background:#f0f0f0; padding:6px;'>claude mcp add --transport http \\\n"
				+ "  openrocket http://127.0.0.1:8723/mcp</pre>"
				+ "<p style='color:#666; font-size:90%;'>Based on OpenRocket "
				+ BuildProperties.getVersion() + ". Not affiliated with the OpenRocket project.</p>"
				+ "</body></html>");
		text.setEditable(false);
		text.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

		JButton open = new JButton("Open AI Copilot");
		open.addActionListener(e -> {
			setVisible(false);
			AiBridgePanel.showPanel();
		});
		JButton close = new JButton("Maybe later");
		close.addActionListener(e -> setVisible(false));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(close);
		buttons.add(open);

		JPanel content = new JPanel(new BorderLayout());
		content.add(text, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		setPreferredSize(new Dimension(500, 430));
		pack();
		setLocationRelativeTo(owner);
	}

	/** Show the dialog once per version. Returns immediately if already shown. */
	public static void maybeShow(Frame owner) {
		Preferences prefs = Preferences.userRoot().node(PREF_NODE);
		String version = BuildProperties.getVersion();
		if (version.equals(prefs.get(SHOWN_KEY, ""))) {
			return;
		}
		prefs.put(SHOWN_KEY, version);
		new AiForkWelcomeDialog(owner).setVisible(true);
	}
}
