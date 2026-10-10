package com.writingagent.v2.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Narrow safety checks on explicit chapter references and literal suffixes, not an intent router. */
public final class ChapterInstructionContract {
    private ChapterInstructionContract() {}
    private static final String NUM = "[0-9一二三四五六七八九十百两]+";
    private static final Pattern CHAPTERS = Pattern.compile("第\\s*("+NUM+"(?:\\s*[、,，和及至到~～—-]\\s*(?:第\\s*)?"+NUM+")*)\\s*章");
    private static final Pattern FIRST = Pattern.compile("前\\s*("+NUM+")\\s*章");
    private static final Pattern SUFFIX = Pattern.compile("(?:结尾|末尾|最后)(?:处|中)?\\s*(?:添加|追加|加上|加|写入|加入|写上|附上)\\s*[:：]?\\s*([^\\n；;，]+)");

    public static Set<Integer> explicitChapters(String instruction) {
        if (instruction == null) return Set.of();
        // Negative references are not authorization to write that chapter.
        String positive = instruction.replaceAll("(?:不改|不要改|不修改|保持第)[^，。；;\\n]*", "");
        Set<Integer> result = new LinkedHashSet<>();
        var first = FIRST.matcher(positive);
        while (first.find()) for (int i=1,n=number(first.group(1));i<=n;i++) result.add(i);
        var chapters = CHAPTERS.matcher(positive);
        while (chapters.find()) {
            String group = chapters.group(1);
            String[] values = group.split("\\s*[、,，和及至到~～—-]\\s*(?:第\\s*)?");
            if (values.length==2 && group.matches(".*[至到~～—-].*")) {
                int start=number(values[0]),end=number(values[1]);
                if (end<start || end-start>200) throw new WritingRuleException(400,"CHAPTER_SCOPE_INVALID");
                for(int i=start;i<=end;i++)result.add(i);
            } else for(String value:values)result.add(number(value));
        }
        return Collections.unmodifiableSet(result);
    }
    public static void requireTargets(String instruction, Collection<Integer> targets, boolean exact) {
        Set<Integer> authorized=explicitChapters(instruction);
        if(!authorized.isEmpty() && (exact ? !authorized.equals(new HashSet<>(targets)) : !authorized.containsAll(targets)))
            throw new WritingRuleException(409,"CHAPTER_SCOPE_MISMATCH");
    }
    public static void requireAllAuthorized(String instruction) {
        if(!explicitChapters(instruction).isEmpty())throw new WritingRuleException(409,"CHAPTER_SCOPE_MISMATCH");
        if(instruction!=null && instruction.matches("(?s).*(不改|不要改|不修改|除外|除了|除第).*"))throw new WritingRuleException(409,"CHAPTER_SCOPE_REQUIRED");
        if(instruction==null || !instruction.matches("(?s).*(全部|所有|剩余|剩下|其余|全文|整篇).*"))
            throw new WritingRuleException(409,"CHAPTER_SCOPE_REQUIRED");
    }
    public static void validateCandidate(String instruction, JsonNode content, int chapterIndex) {
        validateText(instruction,plainText(content),chapterIndex);
    }
    public static void validateText(String instruction, String body, int chapterIndex) {
        if(instruction==null)return;
        var match=SUFFIX.matcher(instruction);
        String text=body==null?"":body.strip();
        while(match.find()) {
            String literal=match.group(1).strip().replaceAll("^[‘’“”\"'`]+|[‘’“”\"'`。！!]+$", "").strip();
            // Open-ended prose instructions are not automatically provable literal requirements.
            if(literal.isEmpty() || literal.length()>120 || literal.contains(" ") || literal.contains("并"))continue;
            literal=literal.replace("章节编号",Integer.toString(chapterIndex));
            if(!text.endsWith(literal))throw new WritingRuleException(422,"REWRITE_REQUIREMENT_NOT_MET");
        }
    }
    private static String plainText(JsonNode n) {
        if(n==null)return "";
        if("text".equals(n.path("type").asText()))return n.path("text").asText();
        StringBuilder s=new StringBuilder();
        for(JsonNode child:n.path("content")){s.append(plainText(child));if(!"text".equals(child.path("type").asText()))s.append('\n');}
        return s.toString();
    }
    private static int number(String value) {
        String s=value.strip();int result;
        if(s.matches("[0-9]+")){try{result=Integer.parseInt(s);}catch(NumberFormatException ex){throw new WritingRuleException(400,"CHAPTER_SCOPE_INVALID");}}
        else {int total=0,digit=0;for(char c:s.toCharArray()){int n="零一二三四五六七八九".indexOf(c);if(c=='两')n=2;if(n>=0)digit=n;else if(c=='十'||c=='百'){total+=(digit==0?1:digit)*(c=='十'?10:100);digit=0;}else throw new WritingRuleException(400,"CHAPTER_SCOPE_INVALID");}result=total+digit;}
        if(result<1||result>1000)throw new WritingRuleException(400,"CHAPTER_SCOPE_INVALID");return result;
    }
}
