package info.openrocket.swing.mcp;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A small software 3D renderer that turns a simulated flight into a cinematic MP4: the rocket
 * flies its real trajectory over a perspective ground grid, filmed by multiple auto-tracking
 * cameras (ground / chase / onboard / orbit / tracking) that cut between each other through the
 * launch. Pure Java2D + a hand-rolled world→camera→screen projection, encoded with ffmpeg.
 */
public class FlightVideoRenderer {

	// --- flight data (SI; world axes: X=downrange, Y=altitude, Z=lateral) ---
	private final double[] t;
	private final double[] alt;
	private final double[] range;
	private final double[] pitch;   // radians from vertical, may be null
	private final double[] vel;
	private final int n;
	private final double flightTime;
	private final double apogeeTime;
	private final double maxAlt;
	private final double maxVel;

	// --- rocket geometry ---
	private final double length;
	private final double radius;
	private final double finSpan;

	// --- output ---
	private final int width;
	private final int height;
	private final int fps;
	private final double videoSeconds;
	private final Scene scene;
	private final String simName;

	public enum Scene { DAY, SUNSET, SPACE }

	public FlightVideoRenderer(String simName, double[] t, double[] alt, double[] range, double[] pitch,
							   double[] vel, double length, double radius, double finSpan,
							   int width, int height, int fps, double videoSeconds, Scene scene) {
		this.simName = simName;
		this.t = t;
		this.alt = alt;
		this.range = range;
		this.pitch = pitch;
		this.vel = vel;
		this.n = t.length;
		this.flightTime = t[n - 1];
		this.length = Math.max(0.1, length);
		this.radius = Math.max(0.005, radius);
		this.finSpan = Math.max(radius, finSpan);
		this.width = width;
		this.height = height;
		this.fps = fps;
		this.videoSeconds = videoSeconds;
		this.scene = scene;
		double ma = 0;
		int ai = 0;
		double mv = 0;
		for (int i = 0; i < n; i++) {
			if (alt[i] > ma) {
				ma = alt[i];
				ai = i;
			}
			mv = Math.max(mv, vel[i]);
		}
		this.maxAlt = Math.max(1, ma);
		this.maxVel = Math.max(1, mv);
		this.apogeeTime = t[ai];
	}

	/** Render all frames and encode an MP4 with ffmpeg. Returns the number of frames written. */
	public int render(File outputMp4, File posterPng, String ffmpegPath) throws Exception {
		File dir = Files.createTempDirectory("orflight").toFile();
		int totalFrames = Math.max(2, (int) Math.round(fps * videoSeconds));
		int posterFrame = (int) (totalFrames * 0.45);
		for (int f = 0; f < totalFrames; f++) {
			double ft = videoTimeToFlightTime((double) f / (totalFrames - 1));
			BufferedImage img = renderFrame(ft);
			ImageIO.write(img, "png", new File(dir, String.format("frame_%05d.png", f)));
			if (f == posterFrame && posterPng != null) {
				ImageIO.write(img, "png", posterPng);
			}
		}
		encode(dir, outputMp4, ffmpegPath);
		// best-effort cleanup
		for (File f : dir.listFiles()) {
			f.delete();
		}
		dir.delete();
		return totalFrames;
	}

	private void encode(File framesDir, File out, String ffmpeg) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(ffmpeg);
		cmd.add("-y");
		cmd.add("-framerate");
		cmd.add(String.valueOf(fps));
		cmd.add("-i");
		cmd.add(new File(framesDir, "frame_%05d.png").getAbsolutePath());
		cmd.add("-c:v");
		cmd.add("libx264");
		cmd.add("-pix_fmt");
		cmd.add("yuv420p");
		cmd.add("-crf");
		cmd.add("18");
		cmd.add("-movflags");
		cmd.add("+faststart");
		cmd.add(out.getAbsolutePath());
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String log = new String(p.getInputStream().readAllBytes());
		int code = p.waitFor();
		if (code != 0) {
			throw new Exception("ffmpeg failed (exit " + code + "): "
					+ log.substring(Math.max(0, log.length() - 400)));
		}
	}

	/** Map normalized video progress [0,1] to a flight time, lingering on the exciting ascent. */
	private double videoTimeToFlightTime(double u) {
		double ascentFrac = 0.6;
		if (u < ascentFrac) {
			return (u / ascentFrac) * apogeeTime;
		}
		return apogeeTime + ((u - ascentFrac) / (1 - ascentFrac)) * (flightTime - apogeeTime);
	}

	// ------------------------------------------------------------------
	// Per-frame rendering
	// ------------------------------------------------------------------

	private BufferedImage renderFrame(double ft) {
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double[] pos = sampleVec(ft);                 // rocket position (world)
		double[] axis = attitude(ft);                 // rocket axis direction (world, unit)
		double v = sample(vel, ft);
		double a = sample(alt, ft);
		boolean ascending = ft < apogeeTime;
		boolean thrusting = ft < Math.min(apogeeTime, burnoutTime());
		boolean chute = !ascending && v < 0.5 * maxVel && a > 0.5;

		Camera cam = cameraFor(ft, pos, axis);
		drawSky(g);
		drawGround(g, cam);
		drawSmoke(g, cam, ft);
		drawRocket(g, cam, pos, axis, thrusting, chute);
		drawHud(g, ft, a, v, cam.name);
		g.dispose();
		return img;
	}

	private double burnoutTime() {
		// crude: motor stops accelerating once velocity peaks
		double mv = 0;
		double tp = 0;
		for (int i = 0; i < n; i++) {
			if (vel[i] > mv) {
				mv = vel[i];
				tp = t[i];
			}
		}
		return tp;
	}

	// ------------------------------------------------------------------
	// Cameras
	// ------------------------------------------------------------------

	private static final class Camera {
		double[] eye;
		double[] fwd;
		double[] right;
		double[] up;
		double focal;
		String name;
	}

	private Camera makeCamera(double[] eye, double[] target, double fovDeg, String name) {
		Camera c = new Camera();
		c.eye = eye;
		double[] worldUp = {0, 1, 0};
		c.fwd = norm(sub(target, eye));
		c.right = norm(cross(c.fwd, worldUp));
		c.up = cross(c.right, c.fwd);
		c.focal = (height / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);
		c.name = name;
		return c;
	}

	/** Pick a camera rig based on the flight phase; all rigs auto-track the rocket. */
	private Camera cameraFor(double ft, double[] pos, double[] axis) {
		double ap = apogeeTime;
		if (ft < 0.9) {
			return makeCamera(new double[]{9, 1.7, 7}, pos, 45, "GROUND CAM");
		} else if (ft < Math.max(2.2, ap * 0.35)) {
			double[] back = scale(axis, -length * 6);
			double[] eye = add(pos, new double[]{back[0] + 1.5, back[1] - 2.0, 3.0});
			return makeCamera(eye, pos, 55, "CHASE CAM");
		} else if (ft < ap * 0.9) {
			return makeCamera(new double[]{70, 2, 25}, pos, 18, "TRACKING CAM");
		} else if (ft < ap * 1.15) {
			double ang = ft * 1.4;
			double r = Math.max(6, length * 8);
			double[] eye = add(pos, new double[]{Math.cos(ang) * r, 1.5, Math.sin(ang) * r});
			return makeCamera(eye, pos, 50, "ORBIT CAM");
		} else if (ft < ap + (flightTime - ap) * 0.45) {
			// onboard: just off the nose, looking down at the receding ground
			double[] eye = add(pos, scale(axis, length * 0.6));
			double[] target = {pos[0], 0, pos[2]};
			return makeCamera(eye, target, 70, "ONBOARD CAM");
		} else {
			return makeCamera(new double[]{12, 1.7, 10}, pos, 40, "RECOVERY CAM");
		}
	}

	// ------------------------------------------------------------------
	// Scene drawing
	// ------------------------------------------------------------------

	private void drawSky(Graphics2D g) {
		Color top;
		Color bottom;
		switch (scene) {
			case SUNSET: top = new Color(0x1B2A4A); bottom = new Color(0xFF9E57); break;
			case SPACE:  top = new Color(0x05060A); bottom = new Color(0x10131F); break;
			default:     top = new Color(0x2E6BB8); bottom = new Color(0xCFE8FF); break;
		}
		g.setPaint(new GradientPaint(0, 0, top, 0, height, bottom));
		g.fillRect(0, 0, width, height);
		if (scene == Scene.SPACE) {
			g.setColor(new Color(255, 255, 255, 180));
			long seed = 12345;
			for (int i = 0; i < 220; i++) {
				seed = seed * 6364136223846793005L + 1442695040888963407L;
				int x = (int) ((seed >>> 33) % width);
				seed = seed * 6364136223846793005L + 1442695040888963407L;
				int y = (int) ((seed >>> 33) % (height * 3 / 4));
				g.fillRect(x, y, 1, 1);
			}
		}
	}

	private void drawGround(Graphics2D g, Camera cam) {
		Color base = scene == Scene.SPACE ? new Color(0x16, 0x19, 0x24)
				: (scene == Scene.SUNSET ? new Color(0x6A, 0x43, 0x2E) : new Color(0x3E, 0x82, 0x3E));
		Color haze = scene == Scene.SPACE ? new Color(0x10, 0x13, 0x1F)
				: (scene == Scene.SUNSET ? new Color(0xFF, 0x9E, 0x57) : new Color(0xCF, 0xE8, 0xFF));
		// Checkerboard ground rendered per-tile so individual tiles clip cleanly and the plane
		// recedes to a hazy horizon (conveys altitude and motion).
		double half = Math.max(120, maxAlt * 0.9);
		int tiles = 30;
		double step = (2 * half) / tiles;
		for (int i = 0; i < tiles; i++) {
			for (int j = 0; j < tiles; j++) {
				double x0 = -half + i * step;
				double z0 = -half + j * step;
				double[] c0 = project(cam, new double[]{x0, 0, z0});
				double[] c1 = project(cam, new double[]{x0 + step, 0, z0});
				double[] c2 = project(cam, new double[]{x0 + step, 0, z0 + step});
				double[] c3 = project(cam, new double[]{x0, 0, z0 + step});
				if (c0 == null || c1 == null || c2 == null || c3 == null) {
					continue;
				}
				double depth = (c0[2] + c1[2] + c2[2] + c3[2]) / 4;
				double blend = Math.min(1, depth / (half * 1.2));
				Color col = lerp(base, haze, blend * 0.85);
				if (((i + j) & 1) == 0) {
					col = lerp(col, Color.BLACK, 0.06);
				}
				Path2D tile = new Path2D.Double();
				tile.moveTo(c0[0], c0[1]);
				tile.lineTo(c1[0], c1[1]);
				tile.lineTo(c2[0], c2[1]);
				tile.lineTo(c3[0], c3[1]);
				tile.closePath();
				g.setColor(col);
				g.fill(tile);
			}
		}
	}

	private static Color lerp(Color a, Color b, double f) {
		f = Math.max(0, Math.min(1, f));
		return new Color(
				(int) (a.getRed() + (b.getRed() - a.getRed()) * f),
				(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * f),
				(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * f));
	}

	private void drawSmoke(Graphics2D g, Camera cam, double ft) {
		// fading smoke puffs along the path already flown
		int steps = 26;
		for (int i = 1; i <= steps; i++) {
			double pt = ft - i * 0.12;
			if (pt < 0) {
				break;
			}
			double[] wp = sampleVec(pt);
			double[] s = project(cam, wp);
			if (s == null) {
				continue;
			}
			float alpha = (float) Math.max(0, 0.5 - i * 0.018);
			double rpx = Math.max(2, cam.focal * (radius * (1 + i * 0.5)) / s[2]);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g.setColor(new Color(220, 220, 220));
			g.fillOval((int) (s[0] - rpx), (int) (s[1] - rpx), (int) (2 * rpx), (int) (2 * rpx));
		}
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
	}

	private void drawRocket(Graphics2D g, Camera cam, double[] pos, double[] axis,
							boolean thrusting, boolean chute) {
		double[] tail = pos;
		double[] noseBase = add(pos, scale(axis, length * 0.75));
		double[] nose = add(pos, scale(axis, length));
		double[] st = project(cam, tail);
		double[] sb = project(cam, noseBase);
		double[] sn = project(cam, nose);
		if (st == null || sb == null || sn == null) {
			return;
		}
		double rpxTail = Math.max(1.5, cam.focal * radius / st[2]);
		double rpxBase = Math.max(1.0, cam.focal * radius / sb[2]);
		// perpendicular to the body axis in screen space
		double[] dir = {sn[0] - st[0], sn[1] - st[1]};
		double dl = Math.hypot(dir[0], dir[1]);
		if (dl < 1e-3) {
			dl = 1;
		}
		double[] perp = {-dir[1] / dl, dir[0] / dl};

		if (thrusting) {
			double[] flameEnd = add(tail, scale(axis, -length * (0.7 + 0.3 * Math.sin(pos[1] * 7))));
			double[] sf = project(cam, flameEnd);
			if (sf != null) {
				Polygon flame = new Polygon();
				flame.addPoint((int) (st[0] + perp[0] * rpxTail), (int) (st[1] + perp[1] * rpxTail));
				flame.addPoint((int) sf[0], (int) sf[1]);
				flame.addPoint((int) (st[0] - perp[0] * rpxTail), (int) (st[1] - perp[1] * rpxTail));
				g.setColor(new Color(0xFF, 0x8A, 0x10, 230));
				g.fillPolygon(flame);
			}
		}

		// fins (at the tail, spanning out perpendicular)
		double finPx = Math.max(2, cam.focal * finSpan / st[2]);
		double[] finBack = add(tail, scale(axis, -length * 0.12));
		double[] sfb = project(cam, finBack);
		if (sfb != null) {
			g.setColor(new Color(0x33, 0x55, 0x99));
			for (int sgn = -1; sgn <= 1; sgn += 2) {
				Polygon fin = new Polygon();
				fin.addPoint((int) (st[0] + perp[0] * rpxTail * sgn), (int) (st[1] + perp[1] * rpxTail * sgn));
				fin.addPoint((int) (sfb[0] + perp[0] * finPx * sgn), (int) (sfb[1] + perp[1] * finPx * sgn));
				fin.addPoint((int) (sfb[0] + perp[0] * rpxTail * sgn), (int) (sfb[1] + perp[1] * rpxTail * sgn));
				g.fillPolygon(fin);
			}
		}

		// body (tail -> nose base) with a cylindrical shading gradient
		Polygon body = new Polygon();
		body.addPoint((int) (st[0] + perp[0] * rpxTail), (int) (st[1] + perp[1] * rpxTail));
		body.addPoint((int) (sb[0] + perp[0] * rpxBase), (int) (sb[1] + perp[1] * rpxBase));
		body.addPoint((int) (sb[0] - perp[0] * rpxBase), (int) (sb[1] - perp[1] * rpxBase));
		body.addPoint((int) (st[0] - perp[0] * rpxTail), (int) (st[1] - perp[1] * rpxTail));
		g.setPaint(new GradientPaint(
				(float) (st[0] + perp[0] * rpxTail), (float) (st[1] + perp[1] * rpxTail), new Color(0xF5, 0xF5, 0xF5),
				(float) (st[0] - perp[0] * rpxTail), (float) (st[1] - perp[1] * rpxTail), new Color(0x9A, 0x9A, 0xA5)));
		g.fillPolygon(body);

		// nose cone
		Polygon noseP = new Polygon();
		noseP.addPoint((int) (sb[0] + perp[0] * rpxBase), (int) (sb[1] + perp[1] * rpxBase));
		noseP.addPoint((int) sn[0], (int) sn[1]);
		noseP.addPoint((int) (sb[0] - perp[0] * rpxBase), (int) (sb[1] - perp[1] * rpxBase));
		g.setColor(new Color(0xCC, 0x33, 0x33));
		g.fillPolygon(noseP);

		if (chute) {
			double[] top = add(pos, new double[]{0, length * 1.5, 0});
			double[] sc = project(cam, top);
			if (sc != null) {
				double cpx = Math.max(8, cam.focal * (finSpan * 3) / sc[2]);
				g.setColor(new Color(0xE0, 0x40, 0x40, 235));
				g.fillArc((int) (sc[0] - cpx), (int) (sc[1] - cpx / 2), (int) (2 * cpx), (int) cpx, 0, 180);
				g.setColor(new Color(80, 80, 80));
				g.setStroke(new BasicStroke(1f));
				g.drawLine((int) (sc[0] - cpx), (int) sc[1], (int) sn[0], (int) sn[1]);
				g.drawLine((int) (sc[0] + cpx), (int) sc[1], (int) sn[0], (int) sn[1]);
			}
		}
	}

	private void drawHud(Graphics2D g, double ft, double a, double v, String camName) {
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, 250, 86);
		g.fillRect(width - 230, 0, 230, 30);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
		g.drawString(String.format("T+%05.2f s", ft), 14, 24);
		g.drawString(String.format("ALT  %6.1f m", a), 14, 46);
		g.drawString(String.format("VEL  %6.1f m/s", v), 14, 68);
		g.setColor(new Color(0xFFD24A));
		g.drawString(camName, width - 220, 21);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		g.setColor(new Color(255, 255, 255, 200));
		g.drawString("OpenRocket-AI  ·  " + simName, 14, height - 16);
	}

	// ------------------------------------------------------------------
	// Trajectory sampling + 3D math
	// ------------------------------------------------------------------

	private double[] sampleVec(double ft) {
		return new double[]{sample(range, ft), sample(alt, ft), 0};
	}

	private double[] attitude(double ft) {
		double th;
		if (pitch != null) {
			th = sample(pitch, ft);
		} else {
			double dt = 0.05;
			double dr = sample(range, ft + dt) - sample(range, ft - dt);
			double da = sample(alt, ft + dt) - sample(alt, ft - dt);
			th = Math.atan2(dr, Math.max(1e-6, da));
		}
		return norm(new double[]{Math.sin(th), Math.cos(th), 0});
	}

	private double sample(double[] arr, double ft) {
		if (ft <= t[0]) {
			return arr[0];
		}
		if (ft >= t[n - 1]) {
			return arr[n - 1];
		}
		int i = 0;
		while (i < n - 1 && t[i + 1] < ft) {
			i++;
		}
		double span = t[i + 1] - t[i];
		double f = span > 1e-9 ? (ft - t[i]) / span : 0;
		return arr[i] + (arr[i + 1] - arr[i]) * f;
	}

	private double[] project(Camera c, double[] p) {
		double[] rel = sub(p, c.eye);
		double cz = dot(rel, c.fwd);
		if (cz < 0.05) {
			return null;
		}
		double cx = dot(rel, c.right);
		double cy = dot(rel, c.up);
		return new double[]{width / 2.0 + c.focal * cx / cz, height / 2.0 - c.focal * cy / cz, cz};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] add(double[] a, double[] b) {
		return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	private static double[] scale(double[] a, double s) {
		return new double[]{a[0] * s, a[1] * s, a[2] * s};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double[] norm(double[] a) {
		double l = Math.sqrt(dot(a, a));
		return l < 1e-9 ? new double[]{0, 1, 0} : new double[]{a[0] / l, a[1] / l, a[2] / l};
	}
}
