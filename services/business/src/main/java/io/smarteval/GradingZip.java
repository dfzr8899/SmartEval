package io.smarteval;

import static io.smarteval.Api.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class GradingZip {
    private static final long ARCHIVE_LIMIT = 100L * 1024 * 1024;
    private final Database db;
    private final GradingAssets assets;

    record File(ZipArchiveEntry entry, String path, String[] parts) {
        String filename() { return parts[parts.length-1]; }
        String submission(int offset) {
            return (parts.length - offset > 1 ? parts[offset] : filename().substring(0,filename().lastIndexOf('.'))).strip();
        }
    }

    GradingZip(Database db, GradingAssets assets) { this.db=db; this.assets=assets; }

    Object unpack(GradingAccess.Actor actor, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize()>ARCHIVE_LIMIT) throw error(422,"ZIP_SIZE","ZIP 须在 100 MB 内");
        if (!GradingAssets.extension(String.valueOf(file.getOriginalFilename())).equals("zip"))
            throw error(422,"INVALID_ZIP","请上传 ZIP 压缩包");
        var temp = Files.createTempFile("smarteval-import-", ".zip");
        try {
            file.transferTo(temp);
            if (Files.size(temp)>ARCHIVE_LIMIT) throw error(422,"ZIP_SIZE","ZIP 须在 100 MB 内");
            try (var archive = ZipFile.builder().setPath(temp).get()) {
                return read(actor,archive);
            } catch (IOException | UncheckedIOException e) {
                throw error(422,"INVALID_ZIP","ZIP 结构或内容无效");
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Object read(GradingAccess.Actor actor, ZipFile archive) throws IOException {
        var visible = new ArrayList<File>();
        var accepted = new ArrayList<File>();
        var skipped = new ArrayList<Map<String,String>>();
        var names = new HashSet<String>();
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int count = 0;
        var entries = archive.getEntries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (++count > 10_000) throw error(422,"ZIP_ENTRY_LIMIT","ZIP 条目过多");
            File file = safePath(entry);
            boolean include = !entry.isDirectory() && file.parts().length > 0 && java.util.Arrays.stream(file.parts())
                    .noneMatch(part -> part.startsWith(".") || part.equals("__MACOSX"));
            if (include) {
                if (!names.add(file.path())) throw error(422,"ZIP_DUPLICATE_PATH","ZIP 中的路径重复");
                visible.add(file);
            }
            boolean supported = include && GradingAssets.EXTENSIONS.contains(GradingAssets.extension(file.filename()));
            long maximum = GradingInput.MAX_MATERIAL_BYTES - total;
            if (entry.getSize()>maximum) throw error(422,"ZIP_EXPANDED_SIZE","解压总大小超过 200 MB");
            boolean bounded = supported && entry.getSize() <= GradingAssets.LIMIT;
            var candidate = bounded ? new ByteArrayOutputStream() : null;
            if (!archive.canReadEntryData(entry)) throw error(422,"INVALID_ZIP","ZIP 含无法读取的条目");
            var crc = new CRC32();
            long size = 0;
            try (var input = archive.getInputStream(entry)) {
                for (int n; (n=input.read(buffer))!=-1;) {
                    size += n;
                    if (size>maximum) throw error(422,"ZIP_EXPANDED_SIZE","解压总大小超过 200 MB");
                    if (size>entry.getSize()) throw error(422,"INVALID_ZIP","ZIP 条目声明的大小与内容不一致");
                    crc.update(buffer,0,n);
                    if (candidate != null) candidate.write(buffer,0,n);
                }
            }
            if (size!=entry.getSize() || crc.getValue()!=entry.getCrc()) throw error(422,"INVALID_ZIP","ZIP 条目校验失败");
            total += size;
            if (!include) continue;
            if (!supported || candidate==null) {
                skipped.add(skipped(file,supported ? "FILE_SIZE" : "UNSUPPORTED_EXTENSION"));
                continue;
            }
            try {
                GradingAssets.validate(file.filename(),candidate.toByteArray());
            } catch (Api.Failure e) {
                skipped.add(skipped(file,e.code));
                continue;
            }
            accepted.add(file);
            if (accepted.size()>GradingInput.MAX_MATERIALS)
                throw error(422,"ZIP_FILE_LIMIT","每个 ZIP 最多导入 100 个有效文件");
        }
        int offset = visible.isEmpty() ? 0 : topDirectory(visible);
        return db.transaction(() -> {
            var items = new ArrayList<Map<String,Object>>();
            for (File file : accepted) {
                String submission = file.submission(offset);
                if (submission.isEmpty() || submission.length()>100) {
                    skipped.add(skipped(file,"INVALID_SUBMISSION_ID"));
                    continue;
                }
                byte[] data;
                try (var input = archive.getInputStream(file.entry())) {
                    data = input.readNBytes(GradingAssets.LIMIT+1);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                try {
                    var material = assets.save(actor,file.filename(),data);
                    var view = new java.util.LinkedHashMap<>(material.view());
                    view.put("suggested_submission_id",submission);
                    items.add(view);
                } catch (IOException e) {
                    throw error(503,"ASSET_STORAGE_UNAVAILABLE","文件暂时无法保存");
                }
            }
            return Map.of("items",items,"skipped",skipped);
        });
    }

    private static final java.nio.charset.Charset GB18030 = java.nio.charset.Charset.forName("GB18030");

    private static String entryName(ZipArchiveEntry entry) {
        if (entry.getGeneralPurposeBit().usesUTF8ForNames()) return entry.getName();
        byte[] raw = entry.getRawName();
        if (raw == null || raw.length == 0) return entry.getName();
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException ignored) {
            return new String(raw, GB18030);
        }
    }

    private static File safePath(ZipArchiveEntry entry) {
        String name = entryName(entry).replace('\\','/');
        int type = entry.getUnixMode() & 0170000;
        if (entry.isUnixSymlink() || type!=0 && type!=0100000 && type!=0040000
                || name.indexOf(':')>=0 || name.startsWith("/") || name.matches("(?s).*[\\p{Cntrl}].*"))
            throw error(422,"UNSAFE_ZIP_ENTRY","ZIP 含绝对路径、链接或非法条目");
        String[] rawParts = name.split("/");
        if (rawParts.length == 0) throw error(422,"UNSAFE_ZIP_ENTRY","ZIP 含不安全路径");
        var normalized = new ArrayList<String>(rawParts.length);
        for (String part : rawParts) {
            if (part.equals("..") || part.isEmpty()) throw error(422,"UNSAFE_ZIP_ENTRY","ZIP 含不安全路径");
            if (part.equals(".")) continue;
            normalized.add(part);
        }
        if (normalized.isEmpty()) {
            if (entry.isDirectory()) return new File(entry, ".", new String[0]);
            throw error(422,"UNSAFE_ZIP_ENTRY","ZIP 含不安全路径");
        }
        String[] parts = normalized.toArray(String[]::new);
        return new File(entry, String.join("/", parts), parts);
    }

    private static int topDirectory(List<File> files) {
        String top = files.getFirst().parts()[0];
        return files.stream().allMatch(f -> f.parts().length>1 && f.parts()[0].equals(top)) ? 1 : 0;
    }

    private static Map<String,String> skipped(File file,String reason) { return Map.of("name",file.path(),"reason",reason); }
}
