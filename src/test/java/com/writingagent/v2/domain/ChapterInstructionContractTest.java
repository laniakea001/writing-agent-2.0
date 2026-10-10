package com.writingagent.v2.domain;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChapterInstructionContractTest {
 @Test void explicitReferencesDoNotIncludeTheSuffixOrUntouchedChapter(){
  assertEquals(Set.of(2,3),ChapterInstructionContract.explicitChapters("修改第2章和第3章，结尾添加@#002，不改第4章"));
  assertEquals(Set.of(2,3),ChapterInstructionContract.explicitChapters("生成第2、3章"));
  assertEquals(Set.of(1,2,3),ChapterInstructionContract.explicitChapters("重写前三章"));
  assertEquals(Set.of(2,3,4),ChapterInstructionContract.explicitChapters("重写第2至4章"));
 }
 @Test void anExplicitSubsetCannotAuthorizeTheAllChaptersTool(){
  assertEquals("CHAPTER_SCOPE_MISMATCH",assertThrows(WritingRuleException.class,()->ChapterInstructionContract.requireAllAuthorized("生成第2章和第3章")).code());
  assertDoesNotThrow(()->ChapterInstructionContract.requireAllAuthorized("继续完成剩余全部章节"));
  assertThrows(WritingRuleException.class,()->ChapterInstructionContract.requireAllAuthorized("写完全部，但不改第2章"));
 }
 @Test void markerMustBeAtTheEndAndIsCheckedWithoutMarkdownEscapes(){
  assertDoesNotThrow(()->ChapterInstructionContract.validateText("修改第4章，结尾添加@#003","正文\n@#003",4));
  assertThrows(WritingRuleException.class,()->ChapterInstructionContract.validateText("结尾添加@#003","@#003 后面仍有正文",4));
  assertThrows(WritingRuleException.class,()->ChapterInstructionContract.validateText("结尾添加@#003","正文",4));
  assertDoesNotThrow(()->ChapterInstructionContract.validateText("末尾加上章节编号-hjj1009","正文\n4-hjj1009",4));
 }
 @Test void batchCannotSilentlyDropOneRequestedChapter(){
  assertThrows(WritingRuleException.class,()->ChapterInstructionContract.requireTargets("修改第2章和第3章",List.of(2),true));
 }
}
