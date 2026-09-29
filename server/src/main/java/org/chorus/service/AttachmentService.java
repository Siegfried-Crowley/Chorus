package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.exception.ApiException;
import org.chorus.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 附件存储 — 保存上传文件到本地磁盘，返回可访问 URL。
 *
 * <p>安全约束:
 * <ul>
 *   <li>仅允许白名单内的文件类型(扩展名 + MIME 双重校验);</li>
 *   <li>二进制类型校验文件头魔数,文本类型校验不含 NUL 字节,阻止伪装上传/存活性 XSS;</li>
 *   <li>限制单个文件大小(默认 25MB);</li>
 *   <li>存储文件名使用 UUID 前缀,原文件名仅作展示,防目录穿越与脏字符。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    @Value("${app.storage.local-path:./data/uploads}")
    private String storagePath;

    @Value("${app.upload.max-size:26214400}")
    private long maxSize;

    /** 单文件默认上限 25MB */
    private static final long DEFAULT_MAX_SIZE = 25L * 1024 * 1024;

    @FunctionalInterface
    private interface MagicCheck {
        boolean matches(byte[] head);
    }

    /** 扩展名 -> (MIME, 魔数校验)。文本类型 magic 为 null,改用 NUL 字节检查。 */
    private static final Map<String, MagicCheck> ALLOWED_TYPES = new HashMap<>();
    private static final Map<String, String> EXT_MIME = new HashMap<>();

    static {
        // 图片
        putType("jpg",  "image/jpeg", head -> head.length >= 3 && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF);
        putType("jpeg", "image/jpeg", head -> head.length >= 3 && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF);
        putType("png",  "image/png",  head -> startsWith(head, hex("89504E470D0A1A0A")));
        putType("gif",  "image/gif",  head -> startsWith(head, hex("47494638")));
        putType("webp", "image/webp", head -> startsWith(head, hex("52494646"))
                && head.length >= 12 && startsWith(head, hex("57454250"), 8));
        // 音频
        putType("mp3",  "audio/mpeg", head -> startsWith(head, hex("494433"))
                || (head.length >= 2 && (head[0] & 0xFF) == 0xFF && ((head[1] & 0xFF) & 0xE0) == 0xE0));
        putType("ogg",  "audio/ogg",  head -> startsWith(head, hex("4F676753")));
        putType("wav",  "audio/wav",  head -> startsWith(head, hex("52494646"))
                && head.length >= 12 && startsWith(head, hex("57415645"), 8));
        putType("m4a",  "audio/mp4",  AttachmentService::hasFtypBox);
        // 视频
        putType("mp4",  "video/mp4",  AttachmentService::hasFtypBox);
        putType("webm", "video/webm", head -> startsWith(head, hex("1A45DFA3")));
        // 文档
        putType("pdf",  "application/pdf", head -> startsWith(head, hex("25504446")));
        // 纯文本(无魔数,校验不含 NUL)
        putType("txt",  "text/plain", head -> containsNoNul(head));
        putType("md",   "text/markdown", head -> containsNoNul(head));
        putType("json", "application/json", head -> containsNoNul(head));
    }

    private static void putType(String ext, String mime, MagicCheck check) {
        ALLOWED_TYPES.put(ext, check);
        EXT_MIME.put(ext, mime);
    }

    /** 保存上传文件，返回附件元数据（url 为相对路径，前端经 /uploads 代理访问） */
    public Map<String, Object> store(MultipartFile file) {
        long limit = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Empty file");
        }
        if (file.getSize() > limit) {
            throw new BadRequestException("File too large, max " + (limit / (1024 * 1024)) + "MB");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        // 防目录穿越 / 脏字符:只取文件名并剥离扩展名以外的路径
        String base = Paths.get(originalName).getFileName().toString();
        String ext = extensionOf(base);
        if (ext.isEmpty() || !ALLOWED_TYPES.containsKey(ext)) {
            throw new BadRequestException("File type not allowed");
        }

        // MIME 校验:客户端声明的 content-type 必须与白名单一致
        String declaredMime = file.getContentType();
        if (declaredMime != null && !declaredMime.isEmpty()) {
            String normalized = declaredMime.toLowerCase(Locale.ROOT).split(";")[0].trim();
            if (!EXT_MIME.get(ext).equals(normalized)
                    && !normalized.startsWith("application/octet-stream")) {
                throw new BadRequestException("File type mismatch");
            }
        }

        // 魔数校验:读文件头 64 字节
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(64);
        } catch (IOException e) {
            throw ApiException.internalError("Upload failed");
        }
        if (!ALLOWED_TYPES.get(ext).matches(head)) {
            throw new BadRequestException("File content does not match its type");
        }

        try {
            Path dir = Paths.get(storagePath).toAbsolutePath().normalize();
            Files.createDirectories(dir);

            String safeBase = sanitizeFileName(base);
            String storedName = UUID.randomUUID().toString().replace("-", "") + "_" + safeBase;
            Path target = dir.resolve(storedName);
            file.transferTo(target);

            Map<String, Object> res = new HashMap<>();
            res.put("url", "/uploads/" + storedName);
            res.put("filename", safeBase);
            res.put("content_type", EXT_MIME.get(ext));
            res.put("size", file.getSize());
            return res;
        } catch (IOException e) {
            throw ApiException.internalError("Upload failed");
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 仅保留安全字符,避免存储路径被注入 */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._\\-]", "_");
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return startsWith(head, magic, 0);
    }

    private static boolean startsWith(byte[] head, byte[] magic, int offset) {
        if (head.length < offset + magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (head[offset + i] != magic[i]) return false;
        }
        return true;
    }

    /** MP4/M4A:前 32 字节内存在 4 字节对齐的 'ftyp' 魔数 */
    private static boolean hasFtypBox(byte[] head) {
        for (int i = 0; i + 4 <= Math.min(head.length, 32); i += 4) {
            if (head[i] == 'f' && head[i + 1] == 't' && head[i + 2] == 'y' && head[i + 3] == 'p') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNoNul(byte[] head) {
        for (byte b : head) {
            if (b == 0) return false;
        }
        return true;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
