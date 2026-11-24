package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class Intelligence {
    private final Database db;
    private final URI endpoint;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    Intelligence(Database db, @Value("${SMARTEVAL_AI_URL:}") String endpoint,
                 @Value("${SMARTEVAL_INTERNAL_TOKEN:}") String token) {
        this.db = db;
        this.endpoint = Api.httpEndpoint(endpoint);
        this.token = token;
    }

    boolean enabled() { return endpoint != null && token.length() >= 32; }

    JsonNode post(String path,Object body,int timeoutSeconds){
        if(!enabled()) throw Api.error(503,"AI_UNAVAILABLE","智能服务未配置");
        HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization","Bearer "+token)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(db.encode(body),StandardCharsets.UTF_8)).build();
        try{
            var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200||response.body().length()>8*1024*1024)
                throw Api.error(503,"AI_UNAVAILABLE","智能服务未能完成当前阶段");
            try {
                return db.parse(response.body());
            } catch (IllegalStateException e) {
                throw Api.error(503,"AI_UNAVAILABLE","智能服务返回了无效响应");
            }
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw Api.error(503,"AI_UNAVAILABLE","智能服务调用被中断");
        }catch(java.io.IOException e){throw Api.error(503,"AI_UNAVAILABLE","智能服务暂不可用");}
    }
}
