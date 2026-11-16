package com.plantorganizer.identification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.plantorganizer.common.InvalidInputException;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void shrinksLargePhotoSoShorterSideIs1280AndOutputsJpeg() throws IOException {
        PlantImage input = new PlantImage(encode(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_ARGB), "png"),
                "big.png", "image/png");

        PlantImage output = processor.resize(input);
        BufferedImage decoded = decode(output.data());

        assertThat(output.contentType()).isEqualTo("image/jpeg");
        assertThat(output.filename()).isEqualTo("big.jpg");
        assertThat(decoded.getHeight()).isEqualTo(1280);
        assertThat(decoded.getWidth()).isEqualTo(1707);
    }

    @Test
    void neverEnlargesSmallPhotos() throws IOException {
        PlantImage input = new PlantImage(encode(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "jpg"),
                "small.jpg", "image/jpeg");

        BufferedImage decoded = decode(processor.resize(input).data());

        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(480);
    }

    @Test
    void rotatesPortraitPhoneShotsUsingExifOrientation() throws IOException {
        byte[] jpeg = encode(new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), "jpg");
        byte[] withExif = insertExifOrientation(jpeg, 6); // "rotate 90 degrees clockwise"

        assertThat(ImageProcessor.ExifOrientation.read(withExif)).isEqualTo(6);
        BufferedImage decoded = decode(processor.resize(new PlantImage(withExif, "p.jpg", "image/jpeg")).data());

        assertThat(decoded.getWidth()).isEqualTo(300);
        assertThat(decoded.getHeight()).isEqualTo(400);
    }

    @Test
    void rejectsFilesThatAreNotImages() {
        PlantImage notAnImage = new PlantImage("hello".getBytes(), "notes.txt", "text/plain");

        assertThatThrownBy(() -> processor.resize(notAnImage))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("JPEG or PNG");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    /** Inserts a minimal big-endian EXIF APP1 segment with one Orientation entry after the JPEG SOI marker. */
    private static byte[] insertExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,             // APP1 marker, length 34
                'E', 'x', 'i', 'f', 0x00, 0x00,                    // "Exif\0\0"
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,      // TIFF header, big-endian, IFD at 8
                0x00, 0x01,                                        // 1 entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,    // tag 0x0112, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,              // value
                0x00, 0x00, 0x00, 0x00                             // next IFD = none
        };
        // Java's encoder writes SOI then a JFIF APP0 segment, which must stay first; insert after it.
        int at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        byte[] result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, at);
        System.arraycopy(app1, 0, result, at, app1.length);
        System.arraycopy(jpeg, at, result, at + app1.length, jpeg.length - at);
        return result;
    }
}
