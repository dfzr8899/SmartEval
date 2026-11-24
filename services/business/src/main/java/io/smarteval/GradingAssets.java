package io.smarteval;

import static io.smarteval.Api.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
class GradingAssets {
    static final int LIMIT = 20 * 1024 * 1024;
    static final java.util.List<String> EXTENSIONS = java.util.List.of("pdf","docx","doc","jpg","jpeg","png","txt","py","java","cpp","c","js");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GradingAssets.class);
    private final Database db;
    private final BlobStore blobs;

    record Material(UUID id, UUID client, String name, String format, byte[] hash, int size) {
        String key(UUID workspace) { return "tenants/" + workspace + "/GRADING/" + id + "." + format; }
        Map<String,Object> reference(String role, String submission) {
            return Map.of("asset_id", id, "name", name, "format", format, "sha256", HexFormat.of().formatHex(hash),
                    "role", role, "submission_id", submission);
        }
        Map<String,Object> view() { return Map.of("asset_id",id,"name",name,"format",format,"size_bytes",size); }
    }

    GradingAssets(Database db, BlobStore blobs) { this.db = db; this.blobs = blobs; }

    Material find(UUID workspace, UUID id) {
        return db.required("SELECT * FROM grading_asset WHERE tenant_id=? AND id=? AND cleanup_claimed=FALSE",
                (r,i) -> new Material(id,Database.uuid(r,"client_id"),r.getString("name"),r.getString("format"),
                        r.getBytes("sha256"),r.getInt("size_bytes")),workspace,id);
    }

    Material owned(GradingAccess.Actor actor, UUID id) {
        Material file = find(actor.workspace(),id);
        if (actor.client() != null && !actor.client().equals(file.client())) throw missing();
        return file;
    }

    void lockForJob(UUID workspace, UUID id) {
        db.required("SELECT id FROM grading_asset WHERE tenant_id=? AND id=? AND cleanup_claimed=FALSE FOR UPDATE",
                (r,i) -> Database.uuid(r,"id"),workspace,id);
    }

    int cleanOrphans(Instant cutoff) {
        Instant now = Instant.now();
        var candidates = db.list("""
                SELECT a.id FROM grading_asset a WHERE a.created_at<?
                AND (a.cleanup_retry_at IS NULL OR a.cleanup_retry_at<=?)
                AND NOT EXISTS (SELECT 1 FROM grading_job_asset r WHERE r.asset_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM grading_collection_asset r WHERE r.asset_id=a.id)
                ORDER BY a.created_at,a.id LIMIT 100
                """,(r,i) -> Database.uuid(r,"id"),cutoff,now);
        int deleted = 0;
        for (UUID id : candidates) {
            try {
                String key = db.transaction(() -> claimOrphan(id,cutoff,now));
                if (key == null) continue;
                blobs.delete(key);
                deleted += db.write("DELETE FROM grading_asset WHERE id=? AND cleanup_claimed=TRUE",id);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not clean grading asset {}",id,e);
            }
        }
        return deleted;
    }

    private String claimOrphan(UUID id, Instant cutoff, Instant now) {
        String key = db.one("""
                SELECT tenant_id,format FROM grading_asset WHERE id=? AND created_at<?
                AND (cleanup_retry_at IS NULL OR cleanup_retry_at<=?) FOR UPDATE
                """,(r,i) -> "tenants/" + Database.uuid(r,"tenant_id") + "/GRADING/" + id + "." + r.getString("format"),
                id,cutoff,now);
        if (key == null || db.one("SELECT job_id FROM grading_job_asset WHERE asset_id=? LIMIT 1 FOR UPDATE",
                (r,i) -> Database.uuid(r,"job_id"),id) != null) return null;
        if (db.one("SELECT collection_id FROM grading_collection_asset WHERE asset_id=? LIMIT 1 FOR UPDATE",
                (r,i) -> Database.uuid(r,"collection_id"),id) != null) return null;
        db.write("UPDATE grading_asset SET cleanup_claimed=TRUE,cleanup_retry_at=? WHERE id=?",now.plusSeconds(3600),id);
        return key;
    }

    Object upload(GradingAccess.Actor actor, MultipartFile upload) throws IOException {
        return save(actor,upload).view();
    }

    Material save(GradingAccess.Actor actor, MultipartFile upload) throws IOException {
        if (upload.isEmpty() || upload.getSize() > LIMIT) throw error(422,"FILE_SIZE","文件大小须在 20 MB 内");
        return save(actor,String.valueOf(upload.getOriginalFilename()),upload.getBytes());
    }

    Material save(GradingAccess.Actor actor, String name, byte[] bytes) throws IOException {
        name = cleanName(name);
        Validated validated = validateAndNormalize(name, bytes);
        String format = validated.format();
        bytes = validated.bytes();
        byte[] hash = sha(bytes);
        var file = new Material(UUID.randomUUID(),actor.client(),name,format,hash,bytes.length);
        String key = file.key(actor.workspace());
        blobs.create(key,bytes);
        boolean tracked = org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
        if (tracked) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                try { blobs.delete(key); } catch (IOException e) { LOG.warn("Could not roll back asset {}",file.id(),e); }
                            }
                        }
                    });
        }
        try {
            db.write("INSERT INTO grading_asset(id,tenant_id,client_id,name,format,sha256,size_bytes,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    file.id(),actor.workspace(),actor.client(),name,format,hash,bytes.length,Instant.now());
        } catch (RuntimeException e) {
            if (!tracked) try { blobs.delete(key); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return file;
    }

    static String cleanName(String name) {
        name = name.replace('\\','/');
        return name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot+1).toLowerCase(Locale.ROOT);
    }

    static final int TEXT_LIMIT = 96_000;
    private static final java.nio.charset.Charset GB18030 = java.nio.charset.Charset.forName("GB18030");
    record Validated(String format, byte[] bytes) {}

    static String validate(String name, byte[] bytes) {
        return validateAndNormalize(name, bytes).format();
    }

    static Validated validateAndNormalize(String name, byte[] bytes) {
        if (name.isBlank() || name.length() > 255) throw error(422,"FILE_NAME","文件名无效");
        if (bytes.length==0 || bytes.length>LIMIT) throw error(422,"FILE_SIZE","文件大小须在 20 MB 内");
        String extension = extension(name);
        if (java.util.Set.of("txt","py","java","cpp","c","js").contains(extension)) {
            if (bytes.length > TEXT_LIMIT) throw error(422,"FILE_SIZE","文本或源码文件大小须在 96 KB 内");
            byte[] normalized = decodeTextToUtf8(bytes);
            if (normalized == null) throw error(422,"UNSUPPORTED_FILE","文本或源码编码不受支持或含非法二进制字节");
            if (normalized.length > TEXT_LIMIT) throw error(422,"FILE_SIZE","文本或源码文件大小须在 96 KB 内");
            return new Validated(extension, normalized);
        }
        String format = switch (extension) {
            case "pdf" -> starts(bytes,0x25,0x50,0x44,0x46,0x2d) ? "pdf" : null;
            case "docx" -> starts(bytes,0x50,0x4b,0x03,0x04) ? "docx" : null;
            case "doc" -> starts(bytes,0xd0,0xcf,0x11,0xe0) ? "doc" : null;
            case "jpg","jpeg" -> starts(bytes,0xff,0xd8,0xff) ? "jpg" : null;
            case "png" -> starts(bytes,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a) ? "png" : null;
            default -> null;
        };
        if (format == null) throw error(422,"UNSUPPORTED_FILE","格式不受支持或文件内容与扩展名不匹配");
        return new Validated(format, bytes);
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i=0;i<signature.length;i++) if ((bytes[i] & 255) != signature[i]) return false;
        return true;
    }

    private static byte[] decodeTextToUtf8(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return null;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return bytes;
        } catch (java.nio.charset.CharacterCodingException ignored) {}
        try {
            var decoded = GB18030.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return decoded.toString().getBytes(StandardCharsets.UTF_8);
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    ResponseEntity<byte[]> download(GradingAccess.Actor actor, UUID id) throws IOException {
        Material file = owned(actor,id);
        byte[] bytes = blobs.read(file.key(actor.workspace()),file.size());
        if (!java.security.MessageDigest.isEqual(sha(bytes),file.hash())) throw error(500,"ASSET_DAMAGED","原文件校验失败");
        MediaType type = switch (file.format()) {
            case "jpg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "pdf" -> MediaType.APPLICATION_PDF;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        boolean inline = !type.equals(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,(inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(file.name(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(bytes);
    }
}

@RestController
@RequestMapping("/api/v2/assets")
class GradingAssetsController {
    private final GradingAssets assets;
    private final GradingAccess access;
    private final Intelligence intelligence;
    private final GradingZip zip;

    GradingAssetsController(GradingAssets assets, GradingAccess access, Intelligence intelligence, GradingZip zip) {
        this.assets=assets; this.access=access; this.intelligence=intelligence;
        this.zip=zip;
    }

    @PostMapping(value="/import-zip", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> importZip(HttpServletRequest request, @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).body(zip.unpack(access.actor(request,"grading:submit"),file));
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(HttpServletRequest request, @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).body(assets.upload(access.actor(request,"grading:submit"),file));
    }

    @GetMapping("/{id}")
    ResponseEntity<?> download(HttpServletRequest request, @PathVariable UUID id) throws IOException {
        return assets.download(access.actor(request,"grading:read"),id);
    }

    @GetMapping("/{id}/pages/{page}")
    ResponseEntity<?> page(HttpServletRequest request, @PathVariable UUID id, @PathVariable int page) {
        var actor = access.actor(request,"grading:read");
        var asset = assets.owned(actor,id);
        if (!(asset.format().equals("pdf") && page>=1 && page<=20
                || (asset.format().equals("png") || asset.format().equals("jpg")) && page==1)) throw missing();
        var preview = intelligence.post("/internal/v2/grading/preview",Map.of("workspace",actor.workspace(),"asset_id",id,
                "sha256",HexFormat.of().formatHex(asset.hash()),"format",asset.format(),"page",page),15);
        byte[] jpeg;
        try {
            jpeg = java.util.Base64.getDecoder().decode(preview.path("jpeg").asText());
        } catch (IllegalArgumentException e) {
            throw error(503,"PREVIEW_UNAVAILABLE","该页预览不可用");
        }
        if (jpeg.length<3 || jpeg.length>4*1024*1024 || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216)
            throw error(503,"PREVIEW_UNAVAILABLE","该页预览不可用");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(jpeg);
    }
}
