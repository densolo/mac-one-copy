import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the app icon, "Shared space": two Mac screens (blue and coral) that meet only in a dark
 * overlap holding a cloud, the one place both Macs share.
 * Run: java tools/icon/Icon.java <out.png> [size]   (tools/icon/make-icns.sh builds the .icns and resource)
 */
public class Icon {
    public static void main(String[] args) throws Exception {
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        ImageIO.write(draw(size), "png", new File(args[0]));
    }

    static final Color BLUE = new Color(0x2F6BFF);
    static final Color CORAL = new Color(0xFF6A4D);
    static final Color OVERLAP = new Color(0x16123A);

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 1024.0, size / 1024.0);

        // macOS icon grid: an 824×824 tile centred on the 1024 canvas, with a soft drop shadow.
        Shape tile = new RoundRectangle2D.Double(100, 100, 824, 824, 185, 185);
        for (int i = 14; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 3));
            g.fill(new RoundRectangle2D.Double(100 - i * 0.5, 112 - i * 0.2, 824 + i, 824 + i * 0.6, 185 + i, 185 + i));
        }
        g.setPaint(new GradientPaint(0, 100, Color.WHITE, 0, 924, new Color(0xE9EDF3)));
        g.fill(tile);
        g.setClip(tile);

        // Two screens, offset diagonally, and their stands.
        Shape left = new RoundRectangle2D.Double(165, 290, 430, 330, 60, 60);
        Shape right = new RoundRectangle2D.Double(429, 384, 430, 330, 60, 60);
        g.setColor(BLUE);
        g.fill(left);
        g.setColor(CORAL);
        g.fill(right);
        g.setStroke(new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(BLUE);
        g.draw(new Line2D.Double(300, 680, 420, 680));
        g.draw(new Line2D.Double(360, 620, 360, 680));
        g.setColor(CORAL);
        g.draw(new Line2D.Double(600, 774, 720, 774));
        g.draw(new Line2D.Double(660, 714, 660, 774));

        // Where they overlap: the shared space, with a cloud in it.
        Area overlap = new Area(left);
        overlap.intersect(new Area(right));
        g.setColor(OVERLAP);
        g.fill(overlap);
        g.setColor(Color.WHITE);
        g.fill(cloud(512, 518, 140));
        g.dispose();
        return img;
    }

    static Area cloud(double cx, double cy, double w) {
        double s = w / 524.0;
        Area a = new Area(new RoundRectangle2D.Double(cx - 262 * s, cy - 15 * s, 524 * s, 200 * s, 200 * s, 200 * s));
        a.add(new Area(new Ellipse2D.Double(cx - 215 * s, cy - 120 * s, 220 * s, 220 * s)));
        a.add(new Area(new Ellipse2D.Double(cx - 85 * s, cy - 200 * s, 280 * s, 280 * s)));
        a.add(new Area(new Ellipse2D.Double(cx + 85 * s, cy - 70 * s, 170 * s, 170 * s)));
        return a;
    }
}
