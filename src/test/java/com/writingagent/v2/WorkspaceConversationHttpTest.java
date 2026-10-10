package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class WorkspaceConversationHttpTest {
 @LocalServerPort int port;
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);r.add("spring.flyway.clean-disabled",()->true);}
 private final ObjectMapper json=new ObjectMapper();private final HttpClient client=HttpClient.newHttpClient();
 @Test void aWorkspaceCanHaveARetrievableConversationWithoutCreatingAnArticle()throws Exception{
  var ws=post("/workspaces","{\"displayName\":\"普通对话不强制建文\"}");assertEquals(200,ws.statusCode());String id=json.readTree(ws.body()).path("workspaceId").asText();
  var created=post("/workspaces/"+id+"/conversations","{}");assertEquals(200,created.statusCode(),created.body());String conv=json.readTree(created.body()).path("conversationId").asText();
  var read=get("/conversations/"+conv);assertEquals(200,read.statusCode());assertTrue(json.readTree(read.body()).path("projectId").isNull());assertEquals(0,json.readTree(read.body()).path("messages").size());
  assertEquals(0,json.readTree(get("/workspaces/"+id+"/projects").body()).path("items").size());
 }
 private String base(){return "http://127.0.0.1:"+port+"/api/writing/v2";}
 @Test void conversationListIsDurableAndRestrictedToItsWorkspace()throws Exception{
  String ws=json.readTree(post("/workspaces","{\"displayName\":\"多会话列表验收\"}").body()).path("workspaceId").asText();
  String other=json.readTree(post("/workspaces","{\"displayName\":\"另一空间\"}").body()).path("workspaceId").asText();
  String a=json.readTree(post("/workspaces/"+ws+"/conversations","{}").body()).path("conversationId").asText();
  String b=json.readTree(post("/workspaces/"+ws+"/conversations","{}").body()).path("conversationId").asText();
  post("/workspaces/"+other+"/conversations","{}");
  var response=get("/workspaces/"+ws+"/conversations");assertEquals(200,response.statusCode(),response.body());
  var items=json.readTree(response.body()).path("items");assertEquals(2,items.size());
  var ids=new java.util.HashSet<String>();items.forEach(c->{assertEquals(ws,c.path("workspaceId").asText());ids.add(c.path("conversationId").asText());});
  assertEquals(java.util.Set.of(a,b),ids);
  assertEquals(400,get("/workspaces/"+ws+"/conversations?offset=-1").statusCode());
 }
 @Test void conversationCannotSelectProjectFromAnotherWorkspace()throws Exception{
  String ws=json.readTree(post("/workspaces","{\"displayName\":\"会话空间\"}").body()).path("workspaceId").asText();
  String other=json.readTree(post("/workspaces","{\"displayName\":\"另一个空间\"}").body()).path("workspaceId").asText();
  String conv=json.readTree(post("/workspaces/"+ws+"/conversations","{}").body()).path("conversationId").asText();
  String project=json.readTree(post("/projects","{\"workspaceId\":\""+other+"\",\"title\":\"不可越空间\",\"researchGoal\":\"测试\"}").body()).path("projectId").asText();
  var result=client.send(HttpRequest.newBuilder(URI.create(base()+"/conversations/"+conv+"/active-project")).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"projectId\":\""+project+"\"}")).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(403,result.statusCode(),result.body());assertTrue(json.readTree(get("/conversations/"+conv).body()).path("projectId").isNull());
  assertEquals(400,post("/conversations/"+conv+"/turns","{\"clientActionId\":\"empty\",\"message\":\"\"}").statusCode());
 }
 private HttpResponse<String> post(String path,String body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(base()+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
 private HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(base()+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
}
