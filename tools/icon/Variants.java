import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Icon proposals, rendered side by side for review.
 * Run: java tools/icon/Variants.java <out-dir>   → variant-N.png (1024) and sheet.png
 */
public class Variants {
    interface Painter { void paint(Graphics2D g); }

    static final String[] NAMES = {
        "1  Chunk cloud", "2  Shared space", "3  Relay arc", "4  Through the slot", "5  Sliced file",
    };
    static final Painter[] VARIANTS = { Variants::chunkCloud, Variants::sharedSpace, Variants::relayArc, Variants::slot, Variants::slicedFile };

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        BufferedImage[] full = new BufferedImage[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) {
            full[i] = render(VARIANTS[i], 1024);
            ImageIO.write(full[i], "png", new File(dir, "variant-" + (i + 1) + ".png"));
        }
        ImageIO.write(sheet(), "png", new File(dir, "sheet.png"));
    }

    // ---- shared helpers ----------------------------------------------------------------------

    static BufferedImage render(Painter p, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.scale(size / 1024.0, size / 1024.0);
        p.paint(g);
        g.dispose();
        return img;
    }

    static final Shape TILE = new RoundRectangle2D.Double(100, 100, 824, 824, 185, 185);

    /** macOS-style tile with a soft shadow and a vertical gradient; later drawing is clipped to it. */
    static void tile(Graphics2D g, Color top, Color bottom) {
        for (int i = 14; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 3));
            g.fill(new RoundRectangle2D.Double(100 - i * 0.5, 112 - i * 0.2, 824 + i, 824 + i * 0.6, 185 + i, 185 + i));
        }
        g.setPaint(new GradientPaint(0, 100, top, 0, 924, bottom));
        g.fill(TILE);
        g.setClip(TILE);
    }

    static Area cloud(double cx, double cy, double w) {
        double s = w / 524.0; // proportions of a 524-wide cloud
        Area a = new Area(new RoundRectangle2D.Double(cx - 262 * s, cy - 15 * s, 524 * s, 200 * s, 200 * s, 200 * s));
        a.add(new Area(new Ellipse2D.Double(cx - 215 * s, cy - 120 * s, 220 * s, 220 * s)));
        a.add(new Area(new Ellipse2D.Double(cx - 85 * s, cy - 200 * s, 280 * s, 280 * s)));
        a.add(new Area(new Ellipse2D.Double(cx + 85 * s, cy - 70 * s, 170 * s, 170 * s)));
        return a;
    }

    static Color c(int rgb) { return new Color(rgb); }
    static Color c(int rgb, int alpha) { return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha); }

    // ---- 1: a cloud made of chunks, some breaking off to the right, others arriving from the left ----

    static void chunkCloud(Graphics2D g) {
        tile(g, c(0x4B3BC4), c(0x1C1260));
        Area cl = cloud(500, 560, 620);
        int step = 46, sq = 38;
        Random rnd = new Random(7);
        for (int y = 200; y < 800; y += step) {
            for (int x = 120; x < 900; x += step) {
                Rectangle2D r = new Rectangle2D.Double(x, y, sq, sq);
                if (!cl.contains(r)) continue; // only whole tiles, so the cloud outline stays crisp
                int a = 215 + rnd.nextInt(40);
                g.setColor(new Color(255, 255, 255, a));
                g.fill(new RoundRectangle2D.Double(x, y, sq, sq, 11, 11));
            }
        }
        // Chunks in flight: leaving on the right, getting smaller and fainter.
        double[][] out = { {810, 470, 40, 230}, {862, 420, 32, 170}, {904, 380, 24, 120} };
        double[][] in = { {136, 690, 34, 180}, {96, 730, 26, 120} };
        for (double[] d : out) { g.setColor(new Color(0x7CF2D0 & 0xFFFFFF | ((int) d[3] << 24), true)); g.fill(new RoundRectangle2D.Double(d[0], d[1], d[2], d[2], d[2] * 0.3, d[2] * 0.3)); }
        for (double[] d : in) { g.setColor(new Color(255, 255, 255, (int) d[3])); g.fill(new RoundRectangle2D.Double(d[0], d[1], d[2], d[2], d[2] * 0.3, d[2] * 0.3)); }
        g.setClip(null);
    }

    // ---- 2: two Macs that never touch; the only space they share is a cloud ----

    static void sharedSpace(Graphics2D g) {
        tile(g, c(0xFFFFFF), c(0xE9EDF3));
        Shape left = new RoundRectangle2D.Double(165, 300, 430, 330, 60, 60);
        Shape right = new RoundRectangle2D.Double(429, 394, 430, 330, 60, 60);
        g.setColor(c(0x2F6BFF)); g.fill(left);
        g.setColor(c(0xFF6A4D)); g.fill(right);
        Area overlap = new Area(left); overlap.intersect(new Area(right));
        g.setColor(c(0x16123A)); g.fill(overlap);
        Area cl = cloud(512, 520, 150);
        cl.intersect(overlap);
        g.setColor(Color.WHITE); g.fill(cloud(512, 528, 140));
        // Stands under both screens.
        g.setStroke(new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c(0x2F6BFF)); g.draw(new Line2D.Double(300, 690, 420, 690)); g.draw(new Line2D.Double(360, 630, 360, 690));
        g.setColor(c(0xFF6A4D)); g.draw(new Line2D.Double(600, 784, 720, 784)); g.draw(new Line2D.Double(660, 724, 660, 784));
        g.setClip(null);
    }

    // ---- 3: chunks travelling in an arc from one Mac, over a small cloud, to the other ----

    static void relayArc(Graphics2D g) {
        tile(g, c(0x0F4C55), c(0x06222A));
        Color mint = c(0x5CE6B8);
        g.setStroke(new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(mint);
        g.draw(new RoundRectangle2D.Double(170, 600, 230, 160, 34, 34));
        g.draw(new Line2D.Double(240, 820, 330, 820)); g.draw(new Line2D.Double(285, 760, 285, 820));
        g.draw(new RoundRectangle2D.Double(624, 600, 230, 160, 34, 34));
        g.draw(new Line2D.Double(694, 820, 784, 820)); g.draw(new Line2D.Double(739, 760, 739, 820));
        // Cloud at the top of the arc.
        g.setColor(Color.WHITE);
        g.fill(cloud(512, 300, 250));
        // Chunks along the arc: left Mac → cloud → right Mac.
        int n = 7;
        for (int i = 0; i < n; i++) {
            double t = (i + 0.5) / n;
            if (Math.abs(t - 0.5) < 0.1) continue; // hidden by the cloud
            double x = 285 + (739 - 285) * t;
            double y = 560 - Math.sin(Math.PI * t) * 300;
            double s = 62;
            g.setColor(t < 0.5 ? c(0xFFFFFF, 235) : mint);
            g.fill(new RoundRectangle2D.Double(x - s / 2, y - s / 2, s, s, 12, 12));
        }
        g.setClip(null);
    }

    // ---- 4: a document passing through a slot in a cloud ----

    static void slot(Graphics2D g) {
        tile(g, c(0xFFB238), c(0xF2552C));
        Area cl = cloud(512, 520, 640);
        // Page behind the cloud: the top shows above, the bottom comes out below.
        Shape page = new RoundRectangle2D.Double(392, 180, 240, 640, 26, 26);
        g.setColor(c(0x3A1C0E, 60)); g.fill(new RoundRectangle2D.Double(400, 194, 240, 640, 26, 26));
        g.setColor(Color.WHITE); g.fill(page);
        g.setColor(c(0xF2552C, 160));
        g.setStroke(new BasicStroke(18, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int y : new int[] { 240, 290, 340 }) g.draw(new Line2D.Double(432, y, y == 340 ? 520 : 592, y));
        for (int y : new int[] { 700, 750 }) g.draw(new Line2D.Double(432, y, y == 750 ? 520 : 592, y));
        // Cloud in front, with a dark slot the page goes through.
        g.setColor(c(0x5A1E05, 60)); g.fill(cl.createTransformedArea(AffineTransform.getTranslateInstance(0, 14)));
        g.setColor(Color.WHITE); g.fill(cl);
        g.setColor(c(0x2B1206));
        g.fill(new RoundRectangle2D.Double(362, 494, 300, 30, 30, 30));
        g.setClip(null);
    }

    // ---- 5: a file cut into chunks, the chunks shifting apart as they travel ----

    static void slicedFile(Graphics2D g) {
        tile(g, c(0x1ED6A8), c(0x0A7A83));
        int n = 5;
        double x0 = 300, y0 = 210, w = 360, h = 600, gap = 18;
        double sh = (h - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            double shift = (i - 1) * 46; // the lower the slice, the further it has travelled
            if (i == 0) shift = -46;
            double y = y0 + i * (sh + gap);
            Shape strip = i == 0
                ? cornerStrip(x0 + shift, y, w, sh)
                : new RoundRectangle2D.Double(x0 + shift, y, w, sh, 22, 22);
            g.setColor(c(0x053B3F, 55)); g.fill(AffineTransform.getTranslateInstance(8, 12).createTransformedShape(strip));
            g.setColor(new Color(255, 255, 255, 255 - i * 22)); g.fill(strip);
            g.setColor(c(0x0A7A83, 130));
            g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double lx = x0 + shift + 50;
            g.draw(new Line2D.Double(lx, y + sh / 2, lx + (i % 2 == 0 ? 200 : 250), y + sh / 2));
        }
        g.setClip(null);
    }

    /** Top slice of a page with a folded corner. */
    static Shape cornerStrip(double x, double y, double w, double h) {
        Path2D p = new Path2D.Double();
        double f = 80;
        p.moveTo(x + 22, y);
        p.lineTo(x + w - f, y);
        p.lineTo(x + w, y + f);
        p.lineTo(x + w, y + h - 22);
        p.quadTo(x + w, y + h, x + w - 22, y + h);
        p.lineTo(x + 22, y + h);
        p.quadTo(x, y + h, x, y + h - 22);
        p.lineTo(x, y + 22);
        p.quadTo(x, y, x + 22, y);
        p.closePath();
        return p;
    }

    // ---- contact sheet: each variant large, plus Dock (64) and Finder list (32) sizes ----

    static BufferedImage sheet() {
        int cell = 300, pad = 24, cols = VARIANTS.length;
        int w = pad + cols * (cell + pad), h = 520;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(new Color(0xF4F5F7)); g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x2B2D31)); g.fillRect(0, 370, w, h - 370); // dark strip to check contrast
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        for (int i = 0; i < cols; i++) {
            int x = pad + i * (cell + pad);
            g.drawImage(render(VARIANTS[i], cell), x, 20, null);
            g.setColor(new Color(0x2B2D31));
            g.drawString(NAMES[i], x + 8, 345);
            g.drawImage(render(VARIANTS[i], 64), x + 40, 398, null);
            g.drawImage(render(VARIANTS[i], 32), x + 140, 414, null);
            g.drawImage(render(VARIANTS[i], 64), x + 200, 398, null);
            g.setColor(new Color(0xF4F5F7));
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(0xBBBEC4));
        g.drawString("64 px and 32 px, on a dark background", pad, 500);
        g.dispose();
        return img;
    }
}
