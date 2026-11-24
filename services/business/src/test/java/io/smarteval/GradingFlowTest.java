package io.smarteval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties={
        "smarteval.secret=integration-secret-not-used-in-deployment",
        "smarteval.bootstrap-user=fixture-admin","smarteval.bootstrap-password=fixture-password-9",
        "smarteval.worker-enabled=false",
        "spring.test.mockmvc.print=NONE"
})
@AutoConfigureMockMvc
class GradingFlowTest {
    private static final String API = "/api/v2";
    @Autowired MockMvc mvc;
    @Autowired Database db;
    @Autowired GradingJobs jobs;
    @Autowired GradingEngine engine;
    @Autowired Webhooks webhooks;
    @Autowired GradingAssets assets;
    @Autowired CodeSimilarity similarity;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping routes;
    @MockitoBean Intelligence intelligence;
    @MockitoBean Judge judge;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws Exception {
        var directory = Files.createTempDirectory("smarteval-grading-test");
        registry.add("smarteval.blob-directory",directory::toString);
        String url = System.getenv("SMARTEVAL_TEST_MYSQL_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url",()->url);
            registry.add("spring.datasource.username",()->System.getenv("SMARTEVAL_TEST_MYSQL_USER"));
            registry.add("spring.datasource.password",()->System.getenv("SMARTEVAL_TEST_MYSQL_PASSWORD"));
        } else {
            registry.add("spring.datasource.url",()->"jdbc:h2:mem:grading;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.username",()->"sa");
            registry.add("spring.datasource.password",()->"");
        }
    }

    private String teacher;
    @BeforeEach
    void login() throws Exception {
        teacher = "Bearer " + json(mvc.perform(post(API+"/auth/login").contentType("application/json")
                .content("{\"username\":\"fixture-admin\",\"password\":\"fixture-password-9\"}"))
                .andExpect(status().isOk())).path("access_token").asText();
    }

    @Test
    void openApiDocumentsEveryBusinessControllerRoute() throws Exception {
        var contract = java.nio.file.Path.of("../../contracts/openapi/grading-v2.yaml");
        try (var source = Files.newBufferedReader(contract)) {
            Map<?,?> document = new org.yaml.snakeyaml.Yaml().load(source);
            var defined = new java.util.TreeSet<String>();
            ((Map<?,?>) document.get("paths")).forEach((path, value) -> ((Map<?,?>) value).forEach((method, operation) -> {
                if (List.of("get","post","patch","delete","put").contains(method)) {
                    defined.add(method.toString().toUpperCase(java.util.Locale.ROOT)+" "+(path.equals("/health")?"":API)+path);
                }
            }));
            var actual = new java.util.TreeSet<String>();
            routes.getHandlerMethods().forEach((mapping,handler) -> {
                if (!handler.getBeanType().getPackageName().equals("io.smarteval")) return;
                mapping.getMethodsCondition().getMethods().forEach(method ->
                        mapping.getPatternValues().forEach(path -> actual.add(method.name()+" "+path)));
            });
            assertThat(actual).isEqualTo(defined);
            System.out.printf("OpenAPI route parity: %d Spring operations, %d contract operations%n",actual.size(),defined.size());
        }
    }

    @Test
    void autoConfirmationValidatesInputAndQueuesWithoutTeacher() throws Exception {
        String client = key("auto");
        String exact = """
                {"title":"批量自动确认","auto_confirm":true,"questions":[{"id":"q","kind":"EXACT","prompt":"2+3?",
                     "max_score":3,"accepted":["5"],"answers":[{"submission_id":"a","text":"5"}]}]}
                """;
        create(client,exact.replace("\"accepted\":[\"5\"],",""),"invalid").andExpect(status().isUnprocessableEntity());
        create(client,exact.replace("\"auto_confirm\":true", "\"auto_confirm\":true,\"webhook_url\":\"http://127.0.0.1:8080/\""),
                "loopback").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_URL"));
        var first = json(create(client,exact,"automatic").andExpect(status().isAccepted()));
        assertThat(first.path("run").asInt()).isEqualTo(1);
        assertThat(first.path("status").asText()).isEqualTo("QUEUED");
        assertThat(json(create(client,exact,"automatic").andExpect(status().isAccepted())).path("id")).isEqualTo(first.path("id"));
        var worker = new GradingWorker(db,jobs,engine,webhooks,assets,similarity,24,8);
        try {
            assertThat(worker.prepareOne()).isFalse();
            assertThat(worker.gradeOne()).isTrue();
        } finally {
            worker.stop();
        }
        mvc.perform(get(API+"/grading-jobs").header("X-API-Key",client)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].counts.SCORED").value(1));
    }

    @Test
    void apiJobCanBeConfirmedAndGradedWithoutCourse() throws Exception {
        String client = key("first"), unrelated = key("second");
        String body = """
                {"title":"独立批改","questions":[
                  {"id":"q1","prompt":"2+3?","kind":"EXACT","max_score":5,"accepted":["5"],
                   "answers":[{"submission_id":"a","text":"5"},{"submission_id":"b","text":"4"}]},
                  {"id":"q2","prompt":"解释原理","kind":"TEXT","max_score":5,
                   "rubric":[{"id":"why","points":5,"description":"解释因果"}],
                   "answers":[{"submission_id":"a","text":"这里有原文"},{"submission_id":"b","text":"需要复核"}]}
                ]}
                """;
        String job = json(create(client,body,"test-independent").andExpect(status().isAccepted())).path("id").asText();
        assertThat(json(create(client,body,"test-independent").andExpect(status().isAccepted())).path("id").asText()).isEqualTo(job);
        create(client,body.replace("独立批改","新标题"),"test-independent").andExpect(status().isConflict());
        String route=API+"/grading-jobs/"+job;
        mvc.perform(get(route).header("X-API-Key",unrelated)).andExpect(status().isNotFound());

        var worker = new GradingWorker(db,jobs,engine,webhooks,assets,similarity,24,8);
        assertThat(worker.prepareOne()).isTrue();
        assertThat(worker.gradeOne()).isFalse();
        var draftReply = mvc.perform(get(route+"/draft").header("X-API-Key",client)).andExpect(status().isOk()).andReturn().getResponse();
        String originalTag = draftReply.getHeader("ETag");
        var draft = db.json.readTree(draftReply.getContentAsByteArray()).path("draft");
        ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("questions").get(1).path("rubric").get(0))
                .put("description","教师确认因果关系");
        String etag=mvc.perform(patch(route+"/draft").header("Authorization",teacher).header("If-Match",originalTag)
                .contentType("application/json").content(db.encode(draft))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mvc.perform(post(route+"/confirm").header("X-API-Key",client).header("If-Match",etag)).andExpect(status().isUnauthorized());
        mvc.perform(post(route+"/confirm").header("Authorization",teacher).header("If-Match",originalTag)).andExpect(status().isPreconditionFailed());
        when(intelligence.enabled()).thenReturn(true);
        when(intelligence.post(eq("/internal/v2/grading/model-configuration"),any(),anyInt()))
                .thenReturn(db.json.valueToTree(Map.of("role_models", Map.of())));
        mvc.perform(post(route+"/confirm").header("Authorization",teacher).header("If-Match",etag))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
        assertThat(worker.gradeOne()).isFalse();
        var model = Map.of("model","fixture-model","configuration_id","fixture-config");
        when(intelligence.post(eq("/internal/v2/grading/model-configuration"),any(),anyInt()))
                .thenReturn(db.json.valueToTree(Map.of("role_models",Map.of("Grader",model,"EvidenceReviewer",model))));
        var confirmed = json(mvc.perform(post(route+"/confirm").header("Authorization",teacher).header("If-Match",etag))
                .andExpect(status().isOk()));
        var createdAt = java.time.Instant.parse(confirmed.path("created_at").asText());
        var confirmedAt = java.time.Instant.parse(confirmed.path("confirmed_at").asText());
        assertThat(confirmedAt).isBetween(createdAt,createdAt.plusSeconds(300));
        mvc.perform(post(route+"/confirm").header("Authorization",teacher).header("If-Match",etag)).andExpect(status().isPreconditionFailed());

        when(intelligence.post(eq("/internal/v2/grading/score"),any(),anyInt())).thenAnswer(call -> {
            var input=db.json.valueToTree(call.getArgument(1));
            assertThat(input.path("item").path("rubric").get(0).path("description").asText()).isEqualTo("教师确认因果关系");
            String quote=input.path("item").path("answer").path("text").asText();
            return db.json.valueToTree(Map.of("criteria",List.of(Map.of("id","why","points",4,"reason","部分满足","quote",quote)),
                    "approved",quote.equals("这里有原文")));
        });
        for (int i=0; i<4; i++) assertThat(worker.gradeOne()).isTrue();
        var report = json(mvc.perform(get(route+"/report").header("X-API-Key",client)).andExpect(status().isOk()));
        assertThat(report.path("items").get(0).path("result").path("final_score").asInt()).isEqualTo(5);
        assertThat(report.path("items").get(1).path("result").path("final_score").asInt(-1)).isEqualTo(0);
        assertThat(report.path("items").get(2).path("result").path("criteria").get(0).path("start").asInt(-1)).isZero();
        assertThat(report.path("items").get(3).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        String itemTag=Api.etag(report.path("items").get(3).path("revision").asInt());
        mvc.perform(post(route+"/reviews/4").header("Authorization",teacher).header("If-Match",itemTag)
                .contentType("application/json").content("{\"run\":1,\"score\":3,\"reason\":\"教师核对原文\"}"))
                .andExpect(status().isNoContent());
        var history = json(mvc.perform(get(route+"/reviews/4?run=1").header("Authorization",teacher)).andExpect(status().isOk()));
        assertThat(history.get(0).path("revised").path("final_score").asInt()).isEqualTo(3);
        var saved = json(mvc.perform(post(route+"/templates").header("Authorization",teacher).contentType("application/json")
                .content("{\"title\":\"已确认模板\"}")).andExpect(status().isCreated()));
        assertThat(saved.path("questions").get(0).has("answers")).isFalse();
        String jobTag = mvc.perform(get(route).header("Authorization",teacher)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(post(route+"/regrades").header("Authorization",teacher).header("If-Match",jobTag)).andExpect(status().isOk());
        var oldReport = json(mvc.perform(get(route+"/report?run=1").header("X-API-Key",client)).andExpect(status().isOk()));
        assertThat(oldReport.path("latest_run").asInt()).isEqualTo(2);
        assertThat(oldReport.path("items").get(3).path("result").path("final_score").asInt()).isEqualTo(3);
        for (int i=0; i<4; i++) assertThat(worker.gradeOne()).isTrue();
        assertThat(json(mvc.perform(get(API+"/keys").header("Authorization",teacher)).andExpect(status().isOk())).size()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/courses").header("Authorization",teacher)).andExpect(status().isNotFound());
    }

    @Test
    void sourceUploadIsPrivateAndCodeUsesConfiguredJudge() throws Exception {
        String client=key("code"), other=key("foreign");
        var file = new org.springframework.mock.web.MockMultipartFile("file","answer.py","text/plain","print(2)".getBytes());
        var upload = json(mvc.perform(multipart(API+"/assets").file(file).header("X-API-Key",client)).andExpect(status().isCreated()));
        String asset=upload.path("asset_id").asText();
        mvc.perform(get(API+"/assets/"+asset).header("X-API-Key",other)).andExpect(status().isNotFound());
        mvc.perform(get(API+"/assets/"+asset).header("X-API-Key",client)).andExpect(status().isOk())
                .andExpect(content().bytes("print(2)".getBytes()));

        String body = """
                {"title":"编程题","questions":[{"id":"1","kind":"CODE","prompt":"输出 2","max_score":5,
                  "tests":[{"id":"sample","points":5,"input":"","expected":"2"}],
                  "answers":[{"submission_id":"a","text":"print(2)","language":"PYTHON"}]}]}
                """;
        when(judge.configuration()).thenReturn("fixture-v1");
        when(judge.trial(any(),any())).thenReturn(db.json.valueToTree(Map.of("verdict","AC","stdout","2\n")));
        String job = json(create(client,body,"test-code").andExpect(status().isAccepted())).path("id").asText();
        var worker = new GradingWorker(db,jobs,engine,webhooks,assets,similarity,24,8);
        assertThat(worker.prepareOne()).isTrue();
        String route=API+"/grading-jobs/"+job;
        String etag=mvc.perform(get(route+"/draft").header("Authorization",teacher)).andReturn().getResponse().getHeader("ETag");
        mvc.perform(post(route+"/confirm").header("Authorization",teacher).header("If-Match",etag))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("JUDGE_UNAVAILABLE"));
        assertThat(worker.gradeOne()).isFalse();
        when(judge.supports("PYTHON")).thenReturn(true);
        mvc.perform(post(route+"/confirm").header("Authorization",teacher).header("If-Match",etag)).andExpect(status().isOk());
        assertThat(worker.gradeOne()).isTrue();
        var report = json(mvc.perform(get(route+"/report").header("X-API-Key",client)).andExpect(status().isOk()));
        assertThat(report.path("items").get(0).path("result").path("final_score").asInt()).isEqualTo(5);
    }

    private String key(String name) throws Exception {
        return json(mvc.perform(post(API+"/keys").header("Authorization",teacher).contentType("application/json")
                .content(db.encode(Map.of("name",name,"expires_in_days",2))))
                .andExpect(status().isCreated())).path("api_key").asText();
    }

    private ResultActions create(String key,String data,String idem) throws Exception {
        return mvc.perform(post(API+"/grading-jobs").header("X-API-Key",key).header("Idempotency-Key",idem)
                .contentType("application/json").content(data));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return db.json.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }
}
