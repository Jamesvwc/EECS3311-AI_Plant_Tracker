package com.plantorganizer.identification;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.stereotype.Component;

import com.plantorganizer.common.InvalidInputException;

/**
 * Step 1 of the Stage 1 identification workflow: adjusts a photo to
 * Pl@ntNet's requirements before it is uploaded.
 *
 * Pl@ntNet only accepts JPEG/PNG, scales every image so its shorter side is
 * 1280 px, and recommends JPEG quality 90%. Phone photos are often 3-10 MB
 * and 4000+ px wide, so this class:
 *   1. decodes the upload (rejecting anything that is not a readable image),
 *   2. shrinks it so the shorter side is at most 1280 px (never enlarges),
 *   3. turns it upright using the EXIF orientation tag (phones store
 *      portrait photos sideways and rely on this tag),
 *   4. re-encodes it as a JPEG at 90% quality on a white background (so PNG
 *      transparency does not turn black).
 * The result is typically 200-500 KB, which uploads quickly.
 */
@Component
public class ImageProcessor {

    /** Pl@ntNet resizes so the shorter side is 1280 px; anything larger is wasted upload. */
    static final int TARGET_SHORT_SIDE = 1280;
    private static final float JPEG_QUALITY = 0.9f;

    /** Prepares one uploaded photo for the identification API. */
    public PlantImage resize(PlantImage photo) {
        BufferedImage original = decode(photo);
        int orientation = ExifOrientation.read(photo.data());

        BufferedImage scaled = scaleDown(original, TARGET_SHORT_SIDE);
        BufferedImage upright = applyOrientation(scaled, orientation);
        byte[] jpeg = encodeJpeg(upright);

        return new PlantImage(jpeg, toJpegName(photo.filename()), "image/jpeg");
    }

    private BufferedImage decode(PlantImage photo) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(photo.data()));
            if (image == null) {
                throw new InvalidInputException("'" + photo.filename()
                        + "' is not a supported image. Please upload a JPEG or PNG photo.");
            }
            return image;
        } catch (IOException e) {
            throw new InvalidInputException("'" + photo.filename() + "' could not be read as an image.");
        }
    }

    /**
     * Shrinks the image so its shorter side is at most {@code maxShortSide},
     * halving repeatedly first: one big bilinear step from 4000 px to 1280 px
     * skips pixels and looks jagged, while repeated halving keeps leaf detail.
     */
    static BufferedImage scaleDown(BufferedImage image, int maxShortSide) {
        int shortSide = Math.min(image.getWidth(), image.getHeight());
        BufferedImage current = toRgb(image);
        if (shortSide <= maxShortSide) {
            return current;
        }
        double scale = (double) maxShortSide / shortSide;
        int targetW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(image.getHeight() * scale));

        int w = current.getWidth();
        int h = current.getHeight();
        while (w / 2 >= targetW && h / 2 >= targetH) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
        }
        if (w != targetW || h != targetH) {
            current = draw(current, targetW, targetH);
        }
        return current;
    }

    /** Rotates the pixels so the photo displays upright without its EXIF tag. */
    static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage rotated;
        Graphics2D g;
        switch (orientation) {
            case 3 -> { // upside down
                rotated = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                g = rotated.createGraphics();
                g.rotate(Math.PI, w / 2.0, h / 2.0);
            }
            case 6 -> { // needs 90 degrees clockwise
                rotated = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
                g = rotated.createGraphics();
                g.translate(h, 0);
                g.rotate(Math.PI / 2);
            }
            case 8 -> { // needs 90 degrees counter-clockwise
                rotated = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
                g = rotated.createGraphics();
                g.translate(0, w);
                g.rotate(-Math.PI / 2);
            }
            default -> {
                return image; // already upright (mirrored variants are rare and left as-is)
            }
        }
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rotated;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    /** Copies onto an opaque white RGB canvas (JPEG has no transparency). */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the resized photo as JPEG", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String toJpegName(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        return base + ".jpg";
    }

    /**
     * Reads the EXIF "Orientation" tag (0x0112) from a JPEG without an extra
     * library. Layout: JPEG segments -> APP1 segment starting "Exif\0\0" ->
     * TIFF header (byte order + offset) -> first directory of 12-byte entries.
     * Returns 1 (normal) whenever anything is missing or malformed.
     */
    static final class ExifOrientation {

        private ExifOrientation() {
        }

        static int read(byte[] d) {
            if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
                return 1; // not a JPEG (PNG has no EXIF orientation in practice)
            }
            int i = 2;
            while (i + 4 <= d.length) {
                if ((d[i] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = d[i + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return 1; // image data starts; no EXIF found before it
                }
                int length = u16(d, i + 2, false);
                int dataStart = i + 4;
                int segmentEnd = Math.min(d.length, i + 2 + length);
                if (marker == 0xE1 && segmentEnd - dataStart >= 14 && isExifHeader(d, dataStart)) {
                    return fromTiff(d, dataStart + 6, segmentEnd);
                }
                i += 2 + length;
            }
            return 1;
        }

        private static boolean isExifHeader(byte[] d, int p) {
            return d[p] == 'E' && d[p + 1] == 'x' && d[p + 2] == 'i' && d[p + 3] == 'f'
                    && d[p + 4] == 0 && d[p + 5] == 0;
        }

        private static int fromTiff(byte[] d, int tiff, int end) {
            boolean littleEndian = d[tiff] == 'I' && d[tiff + 1] == 'I';
            int ifd = tiff + u32(d, tiff + 4, littleEndian);
            if (ifd < tiff || ifd + 2 > end) {
                return 1;
            }
            int entries = u16(d, ifd, littleEndian);
            for (int k = 0; k < entries; k++) {
                int entry = ifd + 2 + k * 12;
                if (entry + 12 > end) {
                    return 1;
                }
                if (u16(d, entry, littleEndian) == 0x0112) {
                    int value = u16(d, entry + 8, littleEndian);
                    return (value >= 1 && value <= 8) ? value : 1;
                }
            }
            return 1;
        }

        private static int u16(byte[] d, int p, boolean le) {
            int a = d[p] & 0xFF;
            int b = d[p + 1] & 0xFF;
            return le ? (b << 8) | a : (a << 8) | b;
        }

        private static int u32(byte[] d, int p, boolean le) {
            int hi = u16(d, le ? p + 2 : p, le);
            int lo = u16(d, le ? p : p + 2, le);
            return (hi << 16) | lo;
        }
    }
}
