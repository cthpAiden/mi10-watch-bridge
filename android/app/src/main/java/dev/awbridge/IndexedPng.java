package dev.awbridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a PNG with a colour table (one byte per pixel) from plain pixels.
 *
 * Map tiles use well under 256 colours. Android can only write full colour
 * PNGs, which are two to three times larger for the same picture, and JPEG
 * smears the fine text. A colour table PNG is exact and the smallest of the
 * three, which matters because every tile crosses a slow Bluetooth link.
 *
 * Plain Java with no Android classes, so it can be tested on a desktop JVM.
 */
public final class IndexedPng {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private IndexedPng() {
    }

    /**
     * @param argb pixels row by row, as 0xAARRGGBB; alpha is ignored (tiles are opaque)
     * @return the PNG, or null when the picture has more than 256 colours
     */
    public static byte[] encode(int[] argb, int width, int height) {
        if (width <= 0 || height <= 0 || argb.length < width * height) return null;
        HashMap<Integer, Integer> index = new HashMap<>(512);
        byte[] palette = new byte[256 * 3];
        // Each row starts with a filter byte (0: the bytes are stored as they are).
        byte[] rows = new byte[(width + 1) * height];
        int at = 0;
        int last = 0;
        int lastIndex = -1;
        for (int y = 0; y < height; y++) {
            rows[at++] = 0;
            for (int x = 0; x < width; x++) {
                int colour = argb[y * width + x] & 0xffffff;
                int slot;
                if (lastIndex >= 0 && colour == last) {
                    slot = lastIndex;
                } else {
                    Integer known = index.get(colour);
                    if (known == null) {
                        slot = index.size();
                        if (slot == 256) return null;
                        index.put(colour, slot);
                        palette[slot * 3] = (byte) (colour >> 16);
                        palette[slot * 3 + 1] = (byte) (colour >> 8);
                        palette[slot * 3 + 2] = (byte) colour;
                    } else {
                        slot = known;
                    }
                    last = colour;
                    lastIndex = slot;
                }
                rows[at++] = (byte) slot;
            }
        }

        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(rows);
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream(rows.length / 4 + 64);
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            packed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream(packed.size() + 1024);
        out.write(SIGNATURE, 0, SIGNATURE.length);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;  // bits per pixel
        header[9] = 3;  // colour type: colour table
        chunk(out, "IHDR", header, header.length);
        chunk(out, "PLTE", palette, index.size() * 3);
        byte[] data = packed.toByteArray();
        chunk(out, "IDAT", data, data.length);
        chunk(out, "IEND", new byte[0], 0);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data, int length) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] size = new byte[4];
        putInt(size, 0, length);
        out.write(size, 0, 4);
        out.write(name, 0, 4);
        out.write(data, 0, length);
        CRC32 crc = new CRC32();
        crc.update(name, 0, 4);
        crc.update(data, 0, length);
        byte[] sum = new byte[4];
        putInt(sum, 0, (int) crc.getValue());
        out.write(sum, 0, 4);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
