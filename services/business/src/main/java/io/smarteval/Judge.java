package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class Judge {
    private final Database db;
    private final URI url;
    private final String token;
    private final Map<String,Integer> languages;
    private final int pollSeconds;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    Judge(Database db,@Value("${SMARTEVAL_JUDGE_URL:}") String url,
          @Value("${SMARTEVAL_JUDGE_TOKEN:}") String token,@Value("${SMARTEVAL_JUDGE_LANGUAGES:}") String languages,
          @Value("${SMARTEVAL_JUDGE_POLL_SECONDS:25}") int pollSeconds){
        this.db=db;this.url=httpEndpoint(url);this.token=token;
        this.pollSeconds = Math.max(5, Math.min(pollSeconds, 55));
        var parsed=new HashMap<String,Integer>();
        // The configuration format is CPP=54,JAVA=62,PYTHON=71,C=50,JAVASCRIPT=63. Values come from the deployed instance.
        if(!languages.isBlank()) for(String link:languages.split(",")){
            String[] row=link.trim().split("=",-1);
            if(row.length!=2||!Set.of("CPP","JAVA","PYTHON","C","JAVASCRIPT").contains(row[0]))throw new IllegalArgumentException("Invalid Judge language mapping");
            int id = Integer.parseInt(row[1]);
            if (id < 1 || parsed.putIfAbsent(row[0],id) != null) throw new IllegalArgumentException("Invalid Judge language mapping");
        }
        this.languages=Map.copyOf(parsed);
    }
    boolean available(){return url!=null&&!languages.isEmpty();}
    List<String> readyLanguages() {
        if (!available()) return List.of();
        try {
            var described = call("GET","/languages",null);
            if (!described.isArray()) return List.of();
            var ids = new HashSet<Integer>();
            described.forEach(language -> ids.add(language.path("id").asInt(-1)));
            return languages.entrySet().stream().filter(e->ids.contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        } catch (Api.Failure failure) { return List.of(); }
    }
    boolean supports(String language){return available()&&languages.containsKey(language);}
    String configuration(){return available()?HexFormat.of().formatHex(sha(url+"|"+new TreeMap<>(languages))):null;}

    JsonNode trial(JsonNode answer,JsonNode limits){
        Integer language=languages.get(answer.path("language").asText());
        if(!available()||language==null)throw error(503,"JUDGE_UNAVAILABLE","判题服务未配置此语言");
        var params=new LinkedHashMap<String,Object>();
        params.put("language_id",language);params.put("source_code",b64(answer.path("source").asText()));
        params.put("stdin",b64(answer.path("stdin").asText()));params.put("enable_network",false);
        params.put("cpu_time_limit",Math.min(10,limits.path("cpu_time_ms").asInt()/1000.0));
        params.put("wall_time_limit",Math.min(15,limits.path("wall_time_ms").asInt()/1000.0));
        params.put("memory_limit",limits.path("memory_kib").asInt());
        String token=call("POST","/submissions?base64_encoded=true&wait=false",params).path("token").asText();
        if(!token.matches("[A-Za-z0-9-]{8,100}"))throw error(503,"JUDGE_ERROR","判题服务返回了无效编号");
        Instant stop=Instant.now().plusSeconds(pollSeconds);
        while(Instant.now().isBefore(stop)){
            var answerData=call("GET","/submissions/"+token+"?base64_encoded=true&fields=status,stdout,stderr,compile_output,time,wall_time,memory",null);
            int code=answerData.path("status").path("id").asInt();
            if(code<=2){
                try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();throw error(503,"JUDGE_ERROR","试运行已中断");}
                continue;
            }
            String verdict=switch(code){case 3,4 -> "AC";case 5 -> "TLE";case 6 -> "CE";case 7,8,9,10,11,12 -> "RE";default -> "SE";};
            if(verdict.equals("SE"))throw error(503,"JUDGE_ERROR","判题执行出现服务错误");
            var outcome=db.json.createObjectNode().put("verdict",verdict).put("memory_kib",answerData.path("memory").asInt());
            outcome.put("wall_time_ms",parseWallTimeMs(answerData));
            for(String field:List.of("stdout","stderr","compile_output")){
                var value=answerData.path(field);
                if(!value.isTextual()){outcome.putNull(field);continue;}
                try{
                    String text=new String(Base64.getMimeDecoder().decode(value.asText()),StandardCharsets.UTF_8);
                    outcome.put(field,text.substring(0,Math.min(text.length(),65536)));
                }catch(IllegalArgumentException e){outcome.putNull(field);}
            }
            return outcome;
        }
        throw error(503,"JUDGE_WAIT_TIMEOUT","判题服务未在限定时间内返回");
    }

    static int parseWallTimeMs(JsonNode data){
        for(String field:List.of("wall_time","time")){
            JsonNode node=data.path(field);
            if(node.isNumber())return (int)Math.max(0,Math.round(node.asDouble()*1000));
            if(node.isTextual()&&!node.asText().isBlank()){
                try{
                    double seconds=Double.parseDouble(node.asText().strip());
                    if(Double.isFinite(seconds))return (int)Math.max(0,Math.round(seconds*1000));
                }catch(NumberFormatException ignored){}
            }
        }
        return 0;
    }

    private String b64(String input){return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));}
    static String normalize(String input){return input.replace("\r\n","\n").replace('\r','\n').replaceAll("(?m)[\\t ]+$","").replaceAll("\\n+$","");}
    private JsonNode call(String method,String path,Object data){
        HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(8));
        if(!token.isBlank())r.header("X-Auth-Token",token);
        if(data==null)r.GET();
        else r.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(db.encode(data),StandardCharsets.UTF_8));
        try{
            var response=http.send(r.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()/100!=2||response.body().length()>2*1024*1024)throw error(503,"JUDGE_ERROR","判题服务异常");
            return db.parse(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw error(503,"JUDGE_ERROR","判题服务调用中断");}
        catch(java.io.IOException e){throw error(503,"JUDGE_ERROR","判题服务暂不可用");}
    }
}

