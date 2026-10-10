package com.writingagent.v2.web;

import com.writingagent.v2.application.capability.WritingCapability;
import com.writingagent.v2.application.capability.WritingCommands.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** HTTP is only an adapter; Tool adapters invoke the same capability, never this controller. */
@RestController
@RequestMapping("/api/writing/v2")
public class DocumentController {
    private final WritingCapability writing;
    public DocumentController(WritingCapability writing) { this.writing=writing; }
    @GetMapping("/actions/lookup")
    public Map<String,Object> lookup(@RequestParam UUID workspaceId,@RequestParam String scopeKey,@RequestParam String clientActionId) {return writing.lookup(workspaceId,scopeKey,clientActionId);}
    @GetMapping("/workspaces/{workspaceId}/projects")
    public Map<String,Object> projects(@PathVariable UUID workspaceId) {return Map.of("items",writing.projects(workspaceId));}
    @GetMapping("/projects/{projectId}")
    public Map<String,Object> project(@PathVariable UUID projectId) {return writing.project(projectId);}
    @PostMapping("/projects/{projectId}/plans")
    public Map<String,Object> savePlan(@PathVariable UUID projectId,@RequestBody PlanSave body) {return writing.savePlan(projectId,body);}
    @GetMapping("/projects/{projectId}/plans/{planRevisionId}")
    public Map<String,Object> plan(@PathVariable UUID projectId,@PathVariable UUID planRevisionId) {return writing.plan(projectId,planRevisionId);}
    @GetMapping("/projects/{projectId}/article")
    public Map<String,Object> article(@PathVariable UUID projectId) {return writing.article(projectId);}
    @GetMapping("/projects/{projectId}/chapters/{chapterKey}")
    public Map<String,Object> chapter(@PathVariable UUID projectId,@PathVariable UUID chapterKey) {return writing.chapter(projectId,chapterKey);}
    @PutMapping("/projects/{projectId}/chapters/{chapterKey}")
    public Map<String,Object> saveChapter(@PathVariable UUID projectId,@PathVariable UUID chapterKey,@RequestBody ChapterSave body) {return writing.saveChapter(projectId,chapterKey,body);}
    @PutMapping("/projects/{projectId}/article/metadata")
    public Map<String,Object> saveMetadata(@PathVariable UUID projectId,@RequestBody MetadataSave body) {return writing.saveMetadata(projectId,body);}
    @PostMapping("/projects/{projectId}/proposals")
    public Map<String,Object> prepare(@PathVariable UUID projectId,@RequestBody ProposalPrepare body) {return writing.prepare(projectId,body);}
    @GetMapping("/proposals/{proposalId}")
    public Map<String,Object> proposal(@PathVariable UUID proposalId) {return writing.proposal(proposalId);}
    @PostMapping("/proposals/{proposalId}/decision")
    public Map<String,Object> decide(@PathVariable UUID proposalId,@RequestBody ProposalDecision body) {return writing.decide(proposalId,body);}
}
