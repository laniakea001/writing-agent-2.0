package com.writingagent.v2.web;

import com.writingagent.v2.domain.WritingRuleException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes={DocumentController.class,WritingWorkbenchController.class,StageAiController.class,WorkspaceConversationController.class})
public class WritingErrorAdvice {
    @ExceptionHandler(WritingRuleException.class)
    public ResponseEntity<Map<String,Object>> rejected(WritingRuleException e) {return error(e.status(),e.code());}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> malformed(Exception e) {return error(400,"INVALID_REQUEST");}
    private ResponseEntity<Map<String,Object>> error(int status,String code) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",code,"retryable",false,"requestId",UUID.randomUUID().toString()));
    }
}
