package info.openrocket.swing.mcp;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Animated flight playback: watch the rocket fly its simulated trajectory (downrange x altitude)
 * from launch to landing, correctly oriented, with exhaust on boost and a parachute on descent.
 *
 * <p>Pure Swing 2D so it renders reliably anywhere. Driven by a {@link Timer}.</p>
 */
public class FlightAnimationFrame extends JFrame {

	private final double[] time;
	private final double[] alt;
	private final double[] range;
	private final double[] pitch;   // radians from vertical, may be null
	private final double[] vel;
	private final int n;
	private final double maxAlt;
	private final double maxRange;
	private final double maxVel;
	private final double flightTime;

	private double simTime = 0;
	private double speed = 1.0;
	private long lastTick;
	private final Timer timer;
	private final AnimPanel panel;

	public static void show(String title, double[] time, double[] alt, double[] range,
							double[] pitch, double[] vel) {
		FlightAnimationFrame frame = new FlightAnimationFrame(title, time, alt, range, pitch, vel);
		frame.setVisible(true);
		frame.start();
	}

	private FlightAnimationFrame(String title, double[] time, double[] alt, double[] range,
								 double[] pitch, double[] vel) {
		super("Flight Animation — " + title);
		this.time = time;
		this.alt = alt;
		this.range = range;
		this.pitch = pitch;
		this.vel = vel;
		this.n = time.length;
		this.flightTime = n > 0 ? time[n - 1] : 0;
		double ma = 0;
		double mr = 0;
		double mv = 0;
		for (int i = 0; i < n; i++) {
			ma = Math.max(ma, alt[i]);
			mr = Math.max(mr, Math.abs(range[i]));
			mv = Math.max(mv, Math.abs(vel[i]));
		}
		this.maxAlt = Math.max(1, ma);
		this.maxRange = Math.max(maxAlt * 0.25, mr);   // keep some horizontal room for vertical flights
		this.maxVel = Math.max(1, mv);

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		timer = new Timer(1000 / 60, e -> tick());
		panel = new AnimPanel();
		panel.setPreferredSize(new Dimension(820, 560));
		setContentPane(panel);

		JPanel controls = new JPanel();
		JButton replay = new JButton("Replay");
		replay.addActionListener(e -> {
			simTime = 0;
			lastTick = System.nanoTime();
			timer.start();
		});
		JButton pause = new JButton("Pause");
		pause.addActionListener(e -> {
			if (timer.isRunning()) {
				timer.stop();
				pause.setText("Play");
			} else {
				lastTick = System.nanoTime();
				timer.start();
				pause.setText("Pause");
			}
		});
		JSlider speedSlider = new JSlider(1, 20, 10);
		speedSlider.addChangeListener(e -> speed = speedSlider.getValue() / 10.0);
		controls.add(replay);
		controls.add(pause);
		controls.add(new javax.swing.JLabel("Speed"));
		controls.add(speedSlider);
		add(controls, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(null);
	}

	private void start() {
		lastTick = System.nanoTime();
		timer.start();
	}

	private void tick() {
		long now = System.nanoTime();
		double dt = (now - lastTick) / 1.0e9;
		lastTick = now;
		simTime += dt * speed;
		if (simTime >= flightTime) {
			simTime = flightTime;
			timer.stop();
		}
		panel.repaint();
	}

	/** Index into the series for the current playback time. */
	private int indexAt(double t) {
		int i = 0;
		while (i < n - 1 && time[i + 1] < t) {
			i++;
		}
		return i;
	}

	private class AnimPanel extends JPanel {
		@Override
		protected void paintComponent(Graphics g0) {
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();

			// Sky + ground
			g.setPaint(new GradientPaint(0, 0, new Color(0x2A5BAA), 0, h, new Color(0xBFE3FF)));
			g.fillRect(0, 0, w, h);
			int groundY = h - 40;
			g.setColor(new Color(0x3C7A3C));
			g.fillRect(0, groundY, w, h - groundY);

			double pad = 60;
			double sx = (w - 2 * pad) / (2 * maxRange);    // range centred at the launch point
			double sy = (groundY - pad) / maxAlt;
			double scale = Math.min(sx, sy);
			double originX = w / 2.0;

			// Launch pad marker
			g.setColor(Color.DARK_GRAY);
			g.fillRect((int) originX - 2, groundY - 14, 4, 14);

			int idx = indexAt(simTime);

			// Trail
			g.setStroke(new BasicStroke(2f));
			g.setColor(new Color(255, 255, 255, 160));
			Path2D trail = new Path2D.Double();
			for (int i = 0; i <= idx; i++) {
				double px = originX + range[i] * scale;
				double py = groundY - alt[i] * scale;
				if (i == 0) {
					trail.moveTo(px, py);
				} else {
					trail.lineTo(px, py);
				}
			}
			g.draw(trail);

			// Rocket
			double rx = originX + range[idx] * scale;
			double ry = groundY - alt[idx] * scale;
			double tilt = (pitch != null) ? pitch[idx] : trajectoryAngle(idx);
			boolean ascending = idx < n - 1 && alt[Math.min(idx + 1, n - 1)] >= alt[idx];
			boolean thrusting = idx > 0 && idx < n - 1 && vel[idx] > vel[idx - 1] && ascending;
			boolean chute = !ascending && Math.abs(vel[idx]) < 0.5 * maxVel && alt[idx] > 0.5;

			AffineTransform old = g.getTransform();
			g.translate(rx, ry);
			g.rotate(tilt);
			drawRocket(g, thrusting, chute);
			g.setTransform(old);

			// HUD
			g.setColor(Color.WHITE);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
			g.drawString(String.format("t = %5.2f s", time[idx]), 12, 20);
			g.drawString(String.format("alt = %6.1f m", alt[idx]), 12, 38);
			g.drawString(String.format("vel = %6.1f m/s", vel[idx]), 12, 56);
			g.drawString(String.format("downrange = %6.1f m", range[idx]), 12, 74);
			g.drawString(String.format("apogee = %6.1f m", maxAlt), 12, 92);
		}

		private void drawRocket(Graphics2D g, boolean thrusting, boolean chute) {
			// Rocket drawn pointing up (-y); body 26px, nose 10px, fins at base.
			if (thrusting) {
				g.setColor(new Color(0xFF, 0x99, 0x10, 220));
				Path2D flame = new Path2D.Double();
				flame.moveTo(-4, 13);
				flame.lineTo(0, 30);
				flame.lineTo(4, 13);
				flame.closePath();
				g.fill(flame);
			}
			if (chute) {
				g.setColor(new Color(0xE0, 0x40, 0x40, 230));
				g.fillArc(-16, -34, 32, 26, 0, 180);
				g.setColor(Color.DARK_GRAY);
				g.setStroke(new BasicStroke(1f));
				g.drawLine(-14, -21, -2, -8);
				g.drawLine(14, -21, 2, -8);
			}
			g.setColor(new Color(0xEE, 0xEE, 0xEE));
			g.fillRoundRect(-3, -3, 6, 16, 3, 3);          // body
			g.setColor(new Color(0xCC, 0x33, 0x33));
			Path2D nose = new Path2D.Double();
			nose.moveTo(0, -13);
			nose.lineTo(-3, -3);
			nose.lineTo(3, -3);
			nose.closePath();
			g.fill(nose);
			g.setColor(new Color(0x33, 0x55, 0x99));        // fins
			Path2D finL = new Path2D.Double();
			finL.moveTo(-3, 7);
			finL.lineTo(-8, 14);
			finL.lineTo(-3, 13);
			finL.closePath();
			g.fill(finL);
			Path2D finR = new Path2D.Double();
			finR.moveTo(3, 7);
			finR.lineTo(8, 14);
			finR.lineTo(3, 13);
			finR.closePath();
			g.fill(finR);
		}

		private double trajectoryAngle(int idx) {
			if (idx >= n - 1) {
				return 0;
			}
			double dx = range[idx + 1] - range[idx];
			double dy = alt[idx + 1] - alt[idx];
			return Math.atan2(dx, Math.max(1e-6, dy));   // 0 = straight up
		}
	}
}
