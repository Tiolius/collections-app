package app;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Маленькие PNG для тестов (4×4 одного цвета). */
final class TestImages {

    private TestImages() {}

    static byte[] png(int r, int g, int b) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            for (int y = 0; y < 4; y++) {
                raw.write(0);
                for (int x = 0; x < 4; x++) { raw.write(r); raw.write(g); raw.write(b); }
            }
            Deflater d = new Deflater();
            d.setInput(raw.toByteArray());
            d.finish();
            byte[] buf = new byte[1024];
            int n = d.deflate(buf);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(out);
            o.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            chunk(o, "IHDR", new byte[] {0, 0, 0, 4, 0, 0, 0, 4, 8, 2, 0, 0, 0});
            byte[] idat = new byte[n];
            System.arraycopy(buf, 0, idat, 0, n);
            chunk(o, "IDAT", idat);
            chunk(o, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void chunk(DataOutputStream o, String type, byte[] data) throws IOException {
        o.writeInt(data.length);
        byte[] t = type.getBytes();
        o.write(t);
        o.write(data);
        CRC32 c = new CRC32();
        c.update(t);
        c.update(data);
        o.writeInt((int) c.getValue());
    }
}
