import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Icon build tool (run once per rebrand; no external dependencies):
 *
 *   java tools/BuildIcon.java
 *
 * Reads   docs/icon-source.png          master art, any size, ideally square
 * Writes  src/main/resources/ui/app-icon.png   256 px, used by the JavaFX windows
 *         build/SignFlow.ico                   256/128/64/48/32/16, used by jpackage
 *
 * Replace docs/icon-source.png with your own picture and re-run (or just run
 * ./package.sh, which regenerates both when the source is newer).
 */
public class BuildIcon {

    private static final int[] ICO_SIZES = {256, 128, 64, 48, 32, 16};
    private static final int FX_SIZE = 256;

    public static void main(String[] args) throws IOException {
        Path source = Path.of("docs/icon-source.png");
        BufferedImage master;
        if (Files.exists(source)) {
            master = ImageIO.read(source.toFile());
        } else {
            System.out.println("docs/icon-source.png not found - generating default artwork");
            master = defaultArtwork(1024);
            Files.createDirectories(source.getParent());
            ImageIO.write(master, "png", source.toFile());
        }
        if (master.getWidth() != master.getHeight()) {
            int side = Math.min(master.getWidth(), master.getHeight());
            BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = square.createGraphics();
            g.drawImage(master, (side - master.getWidth()) / 2, (side - master.getHeight()) / 2, null);
            g.dispose();
            master = square;
        }

        Files.createDirectories(Path.of("src/main/resources/ui"));
        Files.createDirectories(Path.of("build"));
        ImageIO.write(scale(master, FX_SIZE), "png", Path.of("src/main/resources/ui/app-icon.png").toFile());
        System.out.println("wrote src/main/resources/ui/app-icon.png (" + FX_SIZE + "px)");

        writeIco(master, Path.of("build/SignFlow.ico"));
        System.out.println("wrote build/SignFlow.ico " + java.util.Arrays.toString(ICO_SIZES));
    }

    /* ---------- ICO container with 32-bit BGRA (DIB) entries ---------- */

    private static void writeIco(BufferedImage master, Path out) throws IOException {
        List<byte[]> dibs = new ArrayList<>();
        for (int size : ICO_SIZES) {
            dibs.add(dibEntry(scale(master, size)));
        }
        try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
            int count = ICO_SIZES.length;
            int offset = 6 + 16 * count;
            fos.write(new byte[]{0, 0, 1, 0, (byte) count, 0}); // ICONDIR, type=1 (icon)
            for (int i = 0; i < count; i++) {
                int size = ICO_SIZES[i];
                byte[] dib = dibs.get(i);
                fos.write(new byte[]{
                        (byte) (size % 256), (byte) (size % 256), // width, height (256 -> 0)
                        0, 0,                                     // palette, reserved
                        1, 0,                                     // planes
                        32, 0,                                    // bpp
                });
                fos.write(le32(dib.length));
                fos.write(le32(offset));
                offset += dib.length;
            }
            for (byte[] dib : dibs) {
                fos.write(dib);
            }
        }
    }

    /** One BITMAPINFOHEADER + bottom-up BGRA pixels + all-zero AND mask. */
    private static byte[] dibEntry(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int andRow = ((w + 31) / 32) * 4;
        int xorSize = w * h * 4;
        int andSize = andRow * h;
        ByteArrayOutputStream o = new ByteArrayOutputStream(40 + xorSize + andSize);
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        try {
            o.write(new byte[]{
                    40, 0, 0, 0,                                            // biSize
            });
            o.write(le32(w));
            o.write(le32(h * 2));                                           // XOR + AND heights
            o.write(new byte[]{
                    1, 0, 32, 0,                                            // planes, bpp
                    0, 0, 0, 0,                                             // BI_RGB
            });
            o.write(le32(xorSize + andSize));
            o.write(new byte[16]);                                          // 4 unused fields
            for (int y = h - 1; y >= 0; y--) {                              // bottom-up
                for (int x = 0; x < w; x++) {
                    int p = px[y * w + x];
                    o.write(p & 0xFF);                                      // B
                    o.write((p >> 8) & 0xFF);                               // G
                    o.write((p >> 16) & 0xFF);                              // R
                    o.write((p >>> 24) & 0xFF);                             // A
                }
            }
            o.write(new byte[andSize]);                                     // 1 = transparent; 0 keeps alpha
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return o.toByteArray();
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static final class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) { super(cause); }
    }

    /* ---------- helpers ---------- */

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /** Placeholder brand art: indigo-violet tile, pen nib, signature squiggle. */
    private static BufferedImage defaultArtwork(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int m = size / 10, r = size / 5;
        g.setPaint(new GradientPaint(0, 0, new Color(0x4F, 0x46, 0xE5), size, size, new Color(0x7C, 0x3A, 0xED)));
        g.fillRoundRect(0, 0, size, size, r * 2, r * 2);

        g.setColor(new Color(255, 255, 255, 235));
        int[] xs = {size / 2, size - m * 2, m * 2};
        int[] ys = {m * 2, size - m * 2, size - m * 2};
        g.fillPolygon(xs, ys, 3);

        g.setPaint(new GradientPaint(0, 0, new Color(0x4F, 0x46, 0xE5), size, size, new Color(0x7C, 0x3A, 0xED)));
        g.setStroke(new java.awt.BasicStroke(size / 20f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        java.awt.geom.Path2D.Float squiggle = new java.awt.geom.Path2D.Float();
        double y = size * 0.78;
        squiggle.moveTo(size * 0.18, y);
        squiggle.quadTo(size * 0.30, y - size * 0.22, size * 0.42, y);
        squiggle.quadTo(size * 0.52, y + size * 0.10, size * 0.62, y - size * 0.06);
        g.draw(squiggle);

        g.setColor(new Color(255, 255, 255, 90));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size / 6));
        String label = "SF";
        g.drawString(label, (size - g.getFontMetrics().stringWidth(label)) / 2, size * 0.66f);

        g.dispose();
        return img;
    }
}
