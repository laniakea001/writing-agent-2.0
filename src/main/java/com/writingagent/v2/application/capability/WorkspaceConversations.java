package com.writingagent.v2.application.capability;

import com.writingagent.v2.application.port.AiRunPort;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.domain.WritingRuleException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkspaceConversations {
 private final WritingCapability writing;private final AiRunPort runs;private final ConversationPort messages;private final TransactionTemplate tx;private final com.writingagent.v2.runtime.RunJournal journal;
 public WorkspaceConversations(WritingCapability writing,AiRunPort runs,ConversationPort messages,TransactionTemplate tx,com.writingagent.v2.runtime.RunJournal journal){this.writing=writing;this.runs=runs;this.messages=messages;this.tx=tx;this.journal=journal;}
 public Map<String,Object> create(UUID ws){writing.requireWorkspace(ws);return view(tx.execute(s->runs.createConversation(ws)));}
 public Map<String,Object> list(UUID ws,int offset,int limit){writing.requireWorkspace(ws);if(offset<0||limit<1||limit>100)throw new WritingRuleException(400,"INVALID_REQUEST");var items=runs.conversations(ws,offset,limit);return Map.of("items",items,"nextOffset",items.size()==limit?offset+limit:-1);}
 public AiRunPort.Conversation require(UUID conv){var c=runs.conversationInfo(conv);writing.requireWorkspace(c.workspaceId());return c;}
 public Map<String,Object> read(UUID conv){var c=require(conv);var v=view(c);v.put("messages",messages.listMessages(conv));v.put("confirmations",confirmations(conv,0,50).get("items"));return v;}
 public Map<String,Object> confirmations(UUID conv,int offset,int limit){require(conv);if(offset<0||limit<1||limit>100)throw new WritingRuleException(400,"INVALID_REQUEST");var items=writing.conversationProposals(conv,offset,limit);return Map.of("items",items,"nextOffset",items.size()==limit?offset+limit:-1);}
 public Map<String,Object> select(UUID conv,UUID project){var c=require(conv);if(project!=null&&!c.workspaceId().equals(writing.project(project).get("workspaceId")))throw new WritingRuleException(403,"TARGET_SCOPE_REJECTED");return tx.execute(s->{runs.selectProject(conv,project);return view(runs.conversationInfo(conv));});}
 private Map<String,Object> view(AiRunPort.Conversation c){var v=new LinkedHashMap<String,Object>();v.put("workspaceId",c.workspaceId());v.put("conversationId",c.conversationId());v.put("projectId",c.projectId());v.put("contextVersion",c.contextVersion());return v;}
}
