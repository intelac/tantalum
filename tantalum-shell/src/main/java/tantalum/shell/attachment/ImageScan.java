package tantalum.shell.attachment;

import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.tiff.TIFF;
import com.twelvemonkeys.imageio.metadata.tiff.TIFFReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * 不解码像素，只走一遍文件结构：EXIF 方向、有没有元数据块、是不是多帧。规范化据此判断图片是不是“干净”的
 * （设计第 3 节）。结构走不通的文件当作坏图片。
 *
 * @param orientation EXIF 方向，1 到 8；没有或读不出时为 1
 * @param metadata 有元数据块：JPEG 的 APP1–APP15 和 COM；PNG 的 tEXt、iTXt、zTXt、eXIf、tIME；WebP 的 EXIF、XMP；
 *     GIF 的注释、应用和纯文字扩展
 * @param animated 多帧：PNG 的 acTL、WebP 的 ANIM、GIF 的第二帧
 */
record ImageScan(int orientation, boolean metadata, boolean animated) {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    private static final Set<String> PNG_METADATA = Set.of("tEXt", "iTXt", "zTXt", "eXIf", "tIME");

    /** 按文件头认四种图片；其余返回空。至少要 12 字节。 */
    static Optional<String> sniff(byte[] head) {
        if (startsWith(head, PNG)) {
            return Optional.of("image/png");
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (ascii(head, 0, 6).equals("GIF87a") || ascii(head, 0, 6).equals("GIF89a")) {
            return Optional.of("image/gif");
        }
        if (ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    static ImageScan of(ImageInputStream in, String mediaType) throws IOException {
        in.seek(0);
        try {
            return switch (mediaType) {
                case "image/jpeg" -> jpeg(in);
                case "image/png" -> png(in);
                case "image/webp" -> webp(in);
                case "image/gif" -> gif(in);
                default -> throw new IllegalArgumentException(mediaType);
            };
        } finally {
            in.setByteOrder(ByteOrder.BIG_ENDIAN);
        }
    }

    private static ImageScan jpeg(ImageInputStream in) throws IOException {
        in.skipBytes(2);
        boolean metadata = false;
        byte[] exif = null;
        while (true) {
            if (in.readUnsignedByte() != 0xFF) {
                throw new IOException("bad JPEG marker");
            }
            int marker = in.readUnsignedByte();
            while (marker == 0xFF) {
                marker = in.readUnsignedByte();
            }
            if (marker == 0x01 || marker >= 0xD0 && marker <= 0xD8) {
                continue; // 没有长度的标记
            }
            if (marker == 0xDA || marker == 0xD9) {
                break; // 扫描数据开始，后面没有元数据段了
            }
            int length = in.readUnsignedShort();
            if (length < 2) {
                throw new IOException("bad JPEG segment length");
            }
            long next = in.getStreamPosition() + length - 2;
            if (marker >= 0xE1 && marker <= 0xEF || marker == 0xFE) {
                metadata = true;
                if (marker == 0xE1 && exif == null && length - 2 > EXIF_HEADER.length) {
                    byte[] body = read(in, length - 2);
                    if (startsWith(body, EXIF_HEADER)) {
                        exif = Arrays.copyOfRange(body, EXIF_HEADER.length, body.length);
                    }
                }
            }
            in.seek(next);
        }
        return new ImageScan(orientation(exif), metadata, false);
    }

    private static ImageScan png(ImageInputStream in) throws IOException {
        in.skipBytes(PNG.length);
        boolean metadata = false;
        boolean animated = false;
        byte[] exif = null;
        while (true) {
            long length = in.readUnsignedInt();
            String type = ascii(read(in, 4), 0, 4);
            if (type.equals("IEND")) {
                break;
            }
            long next = in.getStreamPosition() + length + 4; // 加上 CRC
            if (PNG_METADATA.contains(type)) {
                metadata = true;
                if (type.equals("eXIf") && length < Integer.MAX_VALUE) {
                    exif = read(in, (int) length);
                }
            }
            animated |= type.equals("acTL");
            in.seek(next);
        }
        return new ImageScan(orientation(exif), metadata, animated);
    }

    private static ImageScan webp(ImageInputStream in) throws IOException {
        in.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        in.skipBytes(4);
        long end = 8 + in.readUnsignedInt();
        in.skipBytes(4);
        boolean metadata = false;
        boolean animated = false;
        byte[] exif = null;
        while (in.getStreamPosition() + 8 <= end) {
            String type = ascii(read(in, 4), 0, 4);
            long size = in.readUnsignedInt();
            long next = in.getStreamPosition() + size + (size & 1);
            if (type.equals("EXIF") || type.equals("XMP ")) {
                metadata = true;
                if (type.equals("EXIF") && size < Integer.MAX_VALUE) {
                    byte[] body = read(in, (int) size);
                    exif = startsWith(body, EXIF_HEADER) ? Arrays.copyOfRange(body, EXIF_HEADER.length, body.length) : body;
                }
            }
            animated |= type.equals("ANIM");
            in.seek(next);
        }
        return new ImageScan(orientation(exif), metadata, animated);
    }

    private static ImageScan gif(ImageInputStream in) throws IOException {
        in.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        in.skipBytes(6 + 4);
        int packed = in.readUnsignedByte();
        in.skipBytes(2);
        skipColorTable(in, packed);
        boolean metadata = false;
        int frames = 0;
        while (frames < 2) {
            int block = in.readUnsignedByte();
            if (block == 0x3B) {
                break;
            } else if (block == 0x21) {
                int label = in.readUnsignedByte();
                metadata |= label == 0xFE || label == 0xFF || label == 0x01; // 注释、应用（XMP 在这里）、纯文字
                skipSubBlocks(in);
            } else if (block == 0x2C) {
                frames++;
                in.skipBytes(8);
                skipColorTable(in, in.readUnsignedByte());
                in.skipBytes(1);
                skipSubBlocks(in);
            } else {
                throw new IOException("bad GIF block " + block);
            }
        }
        return new ImageScan(1, metadata, frames > 1);
    }

    private static void skipColorTable(ImageInputStream in, int packed) throws IOException {
        if ((packed & 0x80) != 0) {
            in.skipBytes(3 << ((packed & 0x07) + 1));
        }
    }

    private static void skipSubBlocks(ImageInputStream in) throws IOException {
        for (int size = in.readUnsignedByte(); size != 0; size = in.readUnsignedByte()) {
            in.skipBytes(size);
        }
    }

    /** EXIF 是一段 TIFF 结构，方向在第一个目录里。读不出来就当没有方向；元数据块照样算有，重新编码时去掉。 */
    private static int orientation(byte[] exif) {
        if (exif == null) {
            return 1;
        }
        try (ImageInputStream tiff = ImageIO.createImageInputStream(new ByteArrayInputStream(exif))) {
            Entry entry = new TIFFReader().read(tiff).getEntryById(TIFF.TAG_ORIENTATION);
            int value = entry == null ? 1 : ((Number) entry.getValue()).intValue();
            return value >= 1 && value <= 8 ? value : 1;
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    private static byte[] read(ImageInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String ascii(byte[] bytes, int from, int length) {
        return bytes.length < from + length ? "" : new String(bytes, from, length, StandardCharsets.ISO_8859_1);
    }
}
