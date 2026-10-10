package com.writingagent.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP -> MVC converters -> capability -> real isolated PG; no mocked business. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class ManualWritingHttpTest {
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);
        r.add("spring.flyway.clean-disabled",()->true);
        r.add("wa2.agent-state-dir",()->"runtime/d2-http-test-agent-state");
    }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();
    @Test void browserShapedPlanJsonIsAcceptedAndReadBackAsJsonNotJacksonInternals() throws Exception {
        var workspace=post("/workspaces","{\"displayName\":\"HTTP-boundary\"}");
        assertEquals(200,workspace.statusCode());
        String workspaceId=JSON.readTree(workspace.body()).path("workspaceId").asText();
        var project=post("/projects","{\"workspaceId\":\""+workspaceId+"\",\"title\":\"HTTP手动计划\",\"researchGoal\":\"无模型\"}");
        assertEquals(200,project.statusCode());
        String id=JSON.readTree(project.body()).path("projectId").asText();
        var saved=post("/projects/"+id+"/plans","{\"clientActionId\":\""+UUID.randomUUID()+"\",\"expectedProjectVersion\":1,\"expectedPlanRevisionId\":null,\"payload\":{\"chapters\":[{\"title\":\"真实章节\"}]}}");
        assertEquals(200,saved.statusCode(),saved.body());
        var read=client.send(HttpRequest.newBuilder(URI.create(base()+"/projects/"+id+"/workbench")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,read.statusCode());
        assertEquals("真实章节",JSON.readTree(read.body()).path("plan").path("payload").path("chapters").path(0).path("title").asText());
    }
    private String base(){return "http://127.0.0.1:"+port+"/api/writing/v2";}
    private HttpResponse<String> post(String path,String body)throws Exception{
        return client.send(HttpRequest.newBuilder(URI.create(base()+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
