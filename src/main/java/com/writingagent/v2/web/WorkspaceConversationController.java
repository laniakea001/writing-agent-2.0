package com.writingagent.v2.web;
import com.writingagent.v2.application.capability.WorkspaceConversations;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/writing/v2")
public class WorkspaceConversationController {
 private final WorkspaceConversations conversations;
 private final com.writingagent.v2.application.capability.StageAiRuns runs;
 public WorkspaceConversationController(WorkspaceConversations conversations,com.writingagent.v2.application.capability.StageAiRuns runs){this.conversations=conversations;this.runs=runs;}
 @PostMapping("/workspaces/{workspace}/conversations") public Map<String,Object> create(@PathVariable UUID workspace){return conversations.create(workspace);}
 @GetMapping("/workspaces/{workspace}/conversations") public Map<String,Object> list(@PathVariable UUID workspace,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return conversations.list(workspace,offset,limit);}
 @GetMapping("/conversations/{conversation}") public Map<String,Object> read(@PathVariable UUID conversation){return conversations.read(conversation);}
 @GetMapping("/conversations/{conversation}/confirmations") public Map<String,Object> confirmations(@PathVariable UUID conversation,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit){return conversations.confirmations(conversation,offset,limit);}
 public record ContextRequest(UUID projectId){}
 @PutMapping("/conversations/{conversation}/active-project") public Map<String,Object> select(@PathVariable UUID conversation,@RequestBody ContextRequest body){return conversations.select(conversation,body.projectId());}
 @PostMapping("/conversations/{conversation}/turns") public org.springframework.http.ResponseEntity<Map<String,Object>> turn(@PathVariable UUID conversation,@RequestBody com.writingagent.v2.application.capability.StageAiRuns.ConversationTurn request){var r=runs.submitConversation(conversation,request);return org.springframework.http.ResponseEntity.status(Set.of("QUEUED","RUNNING").contains(r.get("status"))?202:200).body(r);}
 @GetMapping("/conversations/{conversation}/turns/lookup") public Map<String,Object> lookup(@PathVariable UUID conversation,@RequestParam String clientActionId){return runs.lookupConversation(conversation,clientActionId);}
}
