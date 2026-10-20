package com.example.calc;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * A minimal PNG writer (8-bit RGBA). The services run on the phone too, where java.awt and
 * javax.imageio don't exist, so this uses only java.util.zip.
 */
final class Png {

    private Png() {
    }

    /** @param rgba width * height * 4 bytes (red, green, blue, alpha), row by row */
    static byte[] encodeRgba(int width, int height, byte[] rgba) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});   // PNG signature

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(header);
        h.writeInt(width);
        h.writeInt(height);
        h.write(new byte[] {8, 6, 0, 0, 0});   // 8 bits, RGBA, deflate, no filter method, no interlace
        chunk(out, "IHDR", header.toByteArray());

        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(pixels)) {
            int row = width * 4;
            for (int y = 0; y < height; y++) {
                z.write(0);                     // filter type "none" for each row
                z.write(rgba, y * row, row);
            }
        }
        chunk(out, "IDAT", pixels.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return file.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes("US-ASCII");
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt(data.length);
        out.write(t);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }
}
