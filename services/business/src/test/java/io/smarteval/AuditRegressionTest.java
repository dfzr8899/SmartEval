package io.smarteval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties={
        "smarteval.secret=integration-secret-not-used-in-deployment",
        "smarteval.bootstrap-user=fixture-admin","smarteval.bootstrap-password=fixture-password-9",
        "smarteval.public-origin=http://localhost:8088/",
        "smarteval.worker-enabled=false",
        "spring.test.mockmvc.print=NONE"
})
@AutoConfigureMockMvc
class AuditRegressionTest {
    private static final String API = "/api/v2";
    @Autowired MockMvc mvc;
    @Autowired Database db;
    @Autowired GradingJobs jobs;
    @Autowired GradingEngine engine;
    @Autowired Webhooks webhooks;
    @Autowired GradingAssets assets;
    @Autowired CodeSimilarity similarity;
    @Autowired RateLimiter rateLimiter;
    @MockitoBean Intelligence intelligence;
    @MockitoBean Judge judge;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws Exception {
        var directory = Files.createTempDirectory("smarteval-audit-test");
        registry.add("smarteval.blob-directory", directory::toString);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:audit_regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    private String teacher;

    @BeforeEach
    void setup() throws Exception {
        rateLimiter.clear();
        teacher = "Bearer " + json(mvc.perform(post(API + "/auth/login")
                        .header("Origin", "http://localhost:8088")
                        .contentType("application/json")
                        .content("{\"username\":\"fixture-admin\",\"password\":\"fixture-password-9\"}"))
                .andExpect(status().isOk())).path("access_token").asText();
    }

    @Test
    void zipImportHandlesGbkFilenamesDotSlashAndRejectsUnsafe() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var zos = new ZipArchiveOutputStream(raw)) {
            zos.setEncoding("GB18030");
            zos.setUseLanguageEncodingFlag(false);
            var gbkEntry = new ZipArchiveEntry("./202401_张三.txt");
            zos.putArchiveEntry(gbkEntry);
            zos.write("中文GBK内容".getBytes(Charset.forName("GB18030")));
            zos.closeArchiveEntry();

            var dotEntry = new ZipArchiveEntry("./202402_李四.py");
            zos.putArchiveEntry(dotEntry);
            zos.write("print(42)\n".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        var upload = new MockMultipartFile("file", "hw.zip", "application/zip", raw.toByteArray());
        var result = json(mvc.perform(multipart(API + "/assets/import-zip").file(upload).header("Authorization", teacher))
                .andExpect(status().isCreated()));
        assertThat(result.path("items")).hasSize(2);
        assertThat(result.path("items").get(0).path("suggested_submission_id").asText()).isEqualTo("202401_张三");
        assertThat(result.path("items").get(1).path("suggested_submission_id").asText()).isEqualTo("202402_李四");

        ByteArrayOutputStream unsafe = new ByteArrayOutputStream();
        try (var zos = new ZipArchiveOutputStream(unsafe)) {
            zos.putArchiveEntry(new ZipArchiveEntry("../escape.txt"));
            zos.write("bad".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        var badZip = new MockMultipartFile("file", "bad.zip", "application/zip", unsafe.toByteArray());
        mvc.perform(multipart(API + "/assets/import-zip").file(badZip).header("Authorization", teacher))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSAFE_ZIP_ENTRY"));
    }

    @Test
    void assetsNormalizeGbkReportOversizeCodeAndHandleBadPreviewBase64() throws Exception {
        byte[] gbkSource = "# 中文注释\nprint('你好')\n".getBytes(Charset.forName("GB18030"));
        var gbkFile = new MockMultipartFile("file", "main.py", "text/plain", gbkSource);
        var uploaded = json(mvc.perform(multipart(API + "/assets").file(gbkFile).header("Authorization", teacher))
                .andExpect(status().isCreated()));
        byte[] downloaded = mvc.perform(get(API + "/assets/" + uploaded.path("asset_id").asText()).header("Authorization", teacher))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo("# 中文注释\nprint('你好')\n");

        byte[] bigText = "a".repeat(100_000).getBytes(StandardCharsets.UTF_8);
        var bigFile = new MockMultipartFile("file", "big.txt", "text/plain", bigText);
        mvc.perform(multipart(API + "/assets").file(bigFile).header("Authorization", teacher))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FILE_SIZE"));

        byte[] pngHeader = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        var png = new MockMultipartFile("file", "photo.png", "image/png", pngHeader);
        String pngId = json(mvc.perform(multipart(API + "/assets").file(png).header("Authorization", teacher))
                .andExpect(status().isCreated())).path("asset_id").asText();
        when(intelligence.post(eq("/internal/v2/grading/preview"), argThat(body -> {
            var map = (Map<?, ?>) body;
            return "png".equals(map.get("format"));
        }), anyInt())).thenReturn(db.json.valueToTree(Map.of("jpeg", "!!!not-base64!!!")));
        mvc.perform(get(API + "/assets/" + pngId + "/pages/1").header("Authorization", teacher))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"));
    }

    @Test
    void collectionsSupportNullPresetFieldsReturnEntryAndConvert() throws Exception {
        String createBody = """
                {"title":"阶段测验","description":"请按要求作答",
                 "preset_config":{"questions":[{"id":"q1","prompt":"1+1=?","kind":"EXACT","max_score":5,"accepted":["2"]}],
                                  "materials":null,"template":null}}
                """;
        var created = json(mvc.perform(post(API + "/collections").header("Authorization", teacher)
                        .contentType("application/json").content(createBody))
                .andExpect(status().isCreated()));
        String id = created.path("id").asText();
        String token = created.path("share_token").asText();

        mvc.perform(get(API + "/public/collections/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("阶段测验"));

        mvc.perform(multipart(API + "/public/collections/" + token + "/submit")
                        .param("submission_id", "S001")
                        .param("answer_text", "2")
                        .param("language", ""))
                .andExpect(status().isCreated());
        mvc.perform(multipart(API + "/public/collections/" + token + "/submit")
                        .param("submission_id", "S001")
                        .param("answer_text", "2"))
                .andExpect(status().isConflict());

        var detail = json(mvc.perform(get(API + "/collections/" + id).header("Authorization", teacher))
                .andExpect(status().isOk()));
        String entryId = detail.path("entries").get(0).path("id").asText();
        mvc.perform(delete(API + "/collections/" + id + "/entries/" + entryId).header("Authorization", teacher))
                .andExpect(status().isNoContent());

        mvc.perform(patch(API + "/collections/" + id).header("Authorization", teacher)
                        .contentType("application/json").content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());
        mvc.perform(multipart(API + "/public/collections/" + token + "/submit")
                        .param("submission_id", "S001").param("answer_text", "2"))
                .andExpect(status().isGone());

        mvc.perform(patch(API + "/collections/" + id).header("Authorization", teacher)
                        .contentType("application/json").content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());
        mvc.perform(multipart(API + "/public/collections/" + token + "/submit")
                        .param("submission_id", "S001").param("answer_text", "2"))
                .andExpect(status().isCreated());

        var converted = json(mvc.perform(post(API + "/collections/" + id + "/create-job").header("Authorization", teacher))
                .andExpect(status().isAccepted()));
        assertThat(converted.path("status").asText()).isEqualTo("PREPARING");
    }

    @Test
    void explicitNullFieldsCrlfQuoteJudgeDiagnosticsAndTransientRetryWork() throws Exception {
        String apiKey = createApiKey("regression-client");
        mvc.perform(get(API + "/auth/key").header("X-API-Key", apiKey)).andExpect(status().isNoContent());
        mvc.perform(get(API + "/auth/key").header("X-API-Key", "bad")).andExpect(status().isUnauthorized());

        int[] span = GradingEngine.findQuoteRange("第一行\r\n第二行\r\n第三行", "第一行\n第二行");
        assertThat(span).isNotNull();
        assertThat("第一行\r\n第二行\r\n第三行".substring(span[0], span[1])).isEqualTo("第一行\r\n第二行");
        assertThat(Judge.parseWallTimeMs(db.json.valueToTree(Map.of("wall_time", "", "time", "0.25")))).isEqualTo(250);
        assertThat(Judge.parseWallTimeMs(db.json.valueToTree(Map.of("wall_time", "bad")))).isEqualTo(0);

        String codeJobBody = """
                {"title":"代码重试与诊断","auto_confirm":true,"webhook_url":null,"materials":null,
                 "questions":[{"id":"q1","kind":"CODE","prompt":"打印2","max_score":5,
                   "tests":[{"id":"t1","points":5,"input":"","expected":"2"}],
                   "answers":[{"submission_id":"s1","text":"bad code","language":"JAVA"}]}]}
                """;
        when(judge.supports("JAVA")).thenReturn(true);
        when(judge.configuration()).thenReturn("judge-v1");
        AtomicInteger calls = new AtomicInteger();
        when(judge.trial(any(), argThat(limits -> limits.path("memory_kib").asInt() == 262144))).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) throw Api.error(503, "JUDGE_WAIT_TIMEOUT", "超时");
            return db.json.valueToTree(Map.of("verdict", "CE", "compile_output", "Main.java:1: error: ';' expected", "wall_time_ms", 0));
        });

        String jobId = json(mvc.perform(post(API + "/grading-jobs").header("X-API-Key", apiKey)
                        .header("Idempotency-Key", "null-fields-retry").contentType("application/json").content(codeJobBody))
                .andExpect(status().isAccepted())).path("id").asText();

        var worker = new GradingWorker(db, jobs, engine, webhooks, assets, similarity, 24, 4);
        try {
            assertThat(worker.gradeOne()).isTrue();
            var mid = json(mvc.perform(get(API + "/grading-jobs/" + jobId).header("X-API-Key", apiKey))
                    .andExpect(status().isOk()));
            assertThat(mid.path("status").asText()).isEqualTo("QUEUED");

            db.write("UPDATE grading_item SET available_at=? WHERE job_id=?", Instant.now().minusSeconds(1), Api.id(jobId));
            assertThat(worker.gradeOne()).isTrue();
        } finally {
            worker.stop();
        }

        var report = json(mvc.perform(get(API + "/grading-jobs/" + jobId + "/report").header("X-API-Key", apiKey))
                .andExpect(status().isOk()));
        assertThat(report.path("items").get(0).path("status").asText()).isEqualTo("SCORED");
        assertThat(report.path("items").get(0).path("result").path("criteria").get(0).path("detail").asText())
                .contains("error: ';' expected");
        mvc.perform(get(API + "/grading-jobs/" + jobId + "/webhooks").header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void webhooksEncryptSecretsAndBlockPrivateAddressesAndCsvHandlesEmptyItems() throws Exception {
        String secret = Identity.random();
        String encrypted = webhooks.encryptSecret(secret);
        assertThat(encrypted).startsWith("enc:").isNotEqualTo(secret);
        assertThat(webhooks.decryptSecret(encrypted)).isEqualTo(secret);
        assertThat(webhooks.decryptSecret(secret)).isEqualTo(secret);
        assertThat(Webhooks.publicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(Webhooks.publicAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(Webhooks.publicAddress(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(Webhooks.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();

        var emptyReport = db.json.valueToTree(Map.of(
                "items", List.of(),
                "submissions", List.of(Map.of("submission_id", "s1", "scored_items", 0, "final_items", 0))));
        byte[] csv = GradingCsv.encode(emptyReport);
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("submission_id");
    }

    @Test
    void refreshDoesNotKickOutSiblingTabAndLoginRateLimits() throws Exception {
        var loginResp = mvc.perform(post(API + "/auth/login").contentType("application/json")
                        .content("{\"username\":\"fixture-admin\",\"password\":\"fixture-password-9\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String firstToken = "Bearer " + db.json.readTree(loginResp.getContentAsByteArray()).path("access_token").asText();
        var refreshCookie = loginResp.getCookie("smarteval_refresh");
        assertThat(refreshCookie).isNotNull();

        var refreshed = json(mvc.perform(post(API + "/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk()));
        String secondToken = "Bearer " + refreshed.path("access_token").asText();

        mvc.perform(get(API + "/me").header("Authorization", firstToken)).andExpect(status().isOk());
        mvc.perform(get(API + "/me").header("Authorization", secondToken)).andExpect(status().isOk());
        mvc.perform(post(API + "/auth/logout").header("Authorization", secondToken)).andExpect(status().isNoContent());
        mvc.perform(get(API + "/me").header("Authorization", firstToken)).andExpect(status().isUnauthorized());

        for (int i = 0; i < 15; i++) {
            mvc.perform(post(API + "/auth/login").contentType("application/json")
                            .content("{\"username\":\"nonexistent-user\",\"password\":\"wrong-password-123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post(API + "/auth/login").contentType("application/json")
                        .content("{\"username\":\"nonexistent-user\",\"password\":\"wrong-password-123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void evidenceReviewerRevisionDeclaredTotalGateAnalyticsAndEvalExportWork() throws Exception {
        when(intelligence.post(eq("/internal/v2/grading/score"), any(), anyInt())).thenAnswer(inv -> {
            Map<?, ?> body = (Map<?, ?>) inv.getArgument(1);
            JsonNode item = db.json.valueToTree(body.get("item"));
            String subId = item.path("answer").path("submission_id").asText();
            if ("stu_alice".equals(subId)) {
                return db.json.valueToTree(Map.of(
                        "approved", true,
                        "reviewer_revised", true,
                        "review_summary", "初评漏算时间复杂度要点，已修订为满分",
                        "declared_total_score", 5,
                        "initial_criteria", List.of(Map.of("id", "c1", "points", 3, "reason", "初评遗漏", "quote", "O(n log n)")),
                        "criteria", List.of(Map.of("id", "c1", "points", 5, "reason", "准确给出归并排序复杂度", "quote", "O(n log n)")),
                        "trace", Map.of("roles", List.of("Grader", "EvidenceReviewer"), "review_mode", "revise", "usage", List.of())
                ));
            }
            return db.json.valueToTree(Map.of(
                    "approved", true,
                    "reviewer_revised", false,
                    "review_summary", "声明总分故意不一致",
                    "declared_total_score", 2,
                    "criteria", List.of(Map.of("id", "c1", "points", 4, "reason", "大部分正确", "quote", "分治")),
                    "trace", Map.of("roles", List.of("Grader", "EvidenceReviewer"), "review_mode", "revise", "usage", List.of())
            ));
        });
        when(intelligence.enabled()).thenReturn(true);
        when(intelligence.post(eq("/internal/v2/grading/model-configuration"), any(), anyInt())).thenReturn(db.json.valueToTree(Map.of(
                "role_models", Map.of(
                        "Grader", Map.of("model", "fixture-m", "configuration_id", "cfg-1"),
                        "EvidenceReviewer", Map.of("model", "fixture-m", "configuration_id", "cfg-1"))
        )));

        String jobBody = """
                {"title":"算法分析期中卷","auto_confirm":true,
                 "questions":[{"id":"q1","kind":"TEXT","prompt":"分析归并排序复杂度","max_score":5,
                   "rubric":[{"id":"c1","points":5,"description":"正确说明分治与 O(n log n)"}],
                   "answers":[
                     {"submission_id":"stu_alice","text":"归并排序采用分治策略，时间复杂度为 O(n log n)。"},
                     {"submission_id":"stu_bob","text":"归并排序采用分治策略实现排序。"}
                   ]}]}
                """;
        String jobId = json(mvc.perform(post(API + "/grading-jobs").header("Authorization", teacher)
                        .header("Idempotency-Key", "paper-analytics-job")
                        .contentType("application/json").content(jobBody))
                .andExpect(status().isAccepted())).path("id").asText();

        var worker = new GradingWorker(db, jobs, engine, webhooks, assets, similarity, 24, 4);
        try {
            assertThat(worker.gradeOne()).isTrue();
            assertThat(worker.gradeOne()).isTrue();
        } finally {
            worker.stop();
        }

        var report = json(mvc.perform(get(API + "/grading-jobs/" + jobId + "/report").header("Authorization", teacher))
                .andExpect(status().isOk()));
        var item0 = report.path("items").get(0);
        var item1 = report.path("items").get(1);
        assertThat(item0.path("status").asText()).isEqualTo("SCORED");
        assertThat(item0.path("result").path("final_score").asInt()).isEqualTo(5);
        assertThat(item0.path("result").path("reviewer_revised").asBoolean()).isTrue();
        assertThat(item0.path("result").path("risk_signals").toString()).contains("REVIEWER_REVISED");
        assertThat(item1.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(item1.path("result").path("final_score").isNull()).isTrue();
        assertThat(item1.path("result").path("risk_signals").toString()).contains("DECLARED_TOTAL_MISMATCH");

        // Teacher reviews item1 so both items have final scores
        mvc.perform(post(API + "/grading-jobs/" + jobId + "/reviews/" + item1.path("ordinal").asInt())
                        .header("Authorization", teacher)
                        .header("If-Match", "\"" + item1.path("revision").asInt() + "\"")
                        .contentType("application/json")
                        .content("{\"run\":1,\"score\":4,\"reason\":\"复核确认 4 分\"}"))
                .andExpect(status().isNoContent());

        var overview = json(mvc.perform(get(API + "/analytics/overview").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(overview.path("total_jobs").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(overview.path("human_reviewed_items").asInt()).isGreaterThanOrEqualTo(1);

        var jobStats = json(mvc.perform(get(API + "/grading-jobs/" + jobId + "/analytics").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(jobStats.path("submission_count").asInt()).isEqualTo(2);
        assertThat(jobStats.path("summary").path("mean_score").asDouble()).isEqualTo(4.5);
        assertThat(jobStats.path("review_stats").path("reviewer_revised").asInt()).isEqualTo(1);
        assertThat(jobStats.path("review_stats").path("teacher_reviewed").asInt()).isEqualTo(1);

        var dataset = json(mvc.perform(get(API + "/grading-jobs/" + jobId + "/eval-dataset").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(dataset.path("sample_count").asInt()).isEqualTo(2);
        assertThat(dataset.toString()).doesNotContain("stu_alice").doesNotContain("stu_bob");
        assertThat(dataset.path("samples").get(0).path("id").asText()).isEqualTo("q1-S001");
        assertThat(dataset.path("samples").get(0).path("human_gold_score").asInt()).isEqualTo(5);
        assertThat(dataset.path("samples").get(1).path("human_gold_score").asInt()).isEqualTo(4);
    }

    @Test
    void workspaceAdminMemberGovernanceAndAuditLogsWork() throws Exception {
        var initialMembers = json(mvc.perform(get(API + "/workspace/members").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(initialMembers).hasSize(1);
        String adminMemberId = initialMembers.get(0).path("member_id").asText();

        // Admin cannot disable self or remove the last SCHOOL_ADMIN role
        mvc.perform(patch(API + "/workspace/members/" + adminMemberId).header("Authorization", teacher)
                        .contentType("application/json").content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
        mvc.perform(patch(API + "/workspace/members/" + adminMemberId).header("Authorization", teacher)
                        .contentType("application/json").content("{\"roles\":[\"TEACHER\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));

        // Create a teacher account
        var created = json(mvc.perform(post(API + "/workspace/members").header("Authorization", teacher)
                        .contentType("application/json")
                        .content("{\"username\":\"teacher-wang\",\"display_name\":\"王老师\",\"password\":\"teacher-pass-123\",\"roles\":[\"TEACHER\"]}"))
                .andExpect(status().isCreated()));
        String teacherMemberId = created.path("member_id").asText();

        // Teacher can log in, but gets 403 on admin endpoints
        String teacherToken = "Bearer " + json(mvc.perform(post(API + "/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"teacher-wang\",\"password\":\"teacher-pass-123\"}"))
                .andExpect(status().isOk())).path("access_token").asText();
        mvc.perform(get(API + "/workspace/members").header("Authorization", teacherToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(API + "/workspace/audit-logs").header("Authorization", teacherToken))
                .andExpect(status().isForbidden());

        // Disable teacher -> active sessions revoked immediately
        mvc.perform(patch(API + "/workspace/members/" + teacherMemberId).header("Authorization", teacher)
                        .contentType("application/json").content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get(API + "/me").header("Authorization", teacherToken))
                .andExpect(status().isUnauthorized());

        // Audit logs record MEMBER_CREATED and MEMBER_UPDATED
        var logs = json(mvc.perform(get(API + "/workspace/audit-logs?action=MEMBER_CREATED").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).path("action").asText()).isEqualTo("MEMBER_CREATED");
    }

    @Test
    void userModelConfigEncryptsKeyBlocksSsrfAndSupportsClear() throws Exception {
        var initial = json(mvc.perform(get(API + "/me/model-config").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(initial.path("configured").asBoolean()).isFalse();

        // Loopback / internal address must be rejected with INVALID_MODEL_URL
        mvc.perform(put(API + "/me/model-config").header("Authorization", teacher)
                        .contentType("application/json")
                        .content("{\"provider\":\"custom\",\"base_url\":\"http://127.0.0.1:8000\",\"model_name\":\"m\",\"api_key\":\"sk-local-1234\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_MODEL_URL"));

        // Save valid public HTTPS endpoint: stored encrypted in DB and masked in API response
        var saved = json(mvc.perform(put(API + "/me/model-config").header("Authorization", teacher)
                        .contentType("application/json")
                        .content("{\"provider\":\"deepseek\",\"base_url\":\"https://1.1.1.1/v1\",\"model_name\":\"deepseek-chat\",\"api_key\":\"sk-secret-value-9876\"}"))
                .andExpect(status().isOk()));
        assertThat(saved.path("configured").asBoolean()).isTrue();
        assertThat(saved.path("api_key_masked").asText()).isEqualTo("sk-s••••9876");
        assertThat(saved.toString()).doesNotContain("sk-secret-value-9876");

        String storedRaw = db.one("SELECT api_key_encrypted FROM user_model_config LIMIT 1", (r, i) -> r.getString(1));
        assertThat(storedRaw).startsWith("enc:").doesNotContain("sk-secret-value-9876");

        // Clear key
        mvc.perform(delete(API + "/me/model-config").header("Authorization", teacher))
                .andExpect(status().isNoContent());
        var afterClear = json(mvc.perform(get(API + "/me/model-config").header("Authorization", teacher))
                .andExpect(status().isOk()));
        assertThat(afterClear.path("configured").asBoolean()).isFalse();
    }

    private String createApiKey(String name) throws Exception {
        return json(mvc.perform(post(API + "/keys").header("Authorization", teacher).contentType("application/json")
                        .content(db.encode(Map.of("name", name, "expires_in_days", 2))))
                .andExpect(status().isCreated())).path("api_key").asText();
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return db.json.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }
}
