package com.writingagent.v2.domain.document;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChapterDocumentCodecTest {
    @Test
    void tiptapOrderedListDefaultAttributesAreAcceptedWithoutLosingText() {
        String list="{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"start\":1,\"type\":null},\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文\"}]}]}]}]}";
        assertEquals("1. 正文",new ChapterDocumentCodec().encode(list,true).bodyMarkdown());
    }
    @Test
    void manualEmptyDraftIsValidButAiRequiresText() {
        var codec = new ChapterDocumentCodec();
        String empty = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}";
        assertFalse(codec.encode(empty, false).hasText());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(empty, true));
    }

    @Test
    void hashIgnoresObjectKeyOrderButPreservesParagraphOrder() {
        var codec = new ChapterDocumentCodec();
        String a = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"first\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"second\"}]}]}";
        String reorderedKeys = "{\"content\":[{\"content\":[{\"text\":\"first\",\"type\":\"text\"}],\"type\":\"paragraph\"},{\"content\":[{\"text\":\"second\",\"type\":\"text\"}],\"type\":\"paragraph\"}],\"type\":\"doc\"}";
        assertEquals(codec.encode(a, true).documentHash(), codec.encode(reorderedKeys, true).documentHash());
        assertNotEquals(codec.encode(a, true).documentHash(), codec.encode(a.replace("first", "swap").replace("second", "first").replace("swap", "second"), true).documentHash());
        assertEquals("first\n\nsecond", codec.encode(a, true).bodyMarkdown());
    }

    @Test
    void unsupportedMediaAndJavascriptLinkAreRejected() {
        var codec = new ChapterDocumentCodec();
        var image = assertThrows(IllegalArgumentException.class, () -> codec.encode("{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"src\":\"x\"}}]}", false));
        assertEquals("UNSUPPORTED_DOCUMENT_NODE", image.getMessage());
        var link = assertThrows(IllegalArgumentException.class, () -> codec.encode("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"click\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:alert(1)\"}}]}]}]}", true));
        assertEquals("DOCUMENT_LINK_REJECTED", link.getMessage());
    }

    @Test
    void legalTableAndBoldArePreservedInCanonicalDocument() {
        var codec = new ChapterDocumentCodec();
        String input = "{\"type\":\"doc\",\"content\":[{\"type\":\"table\",\"content\":[{\"type\":\"tableRow\",\"content\":[{\"type\":\"tableHeader\",\"attrs\":{\"colspan\":1,\"rowspan\":1,\"colwidth\":null},\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"能力\",\"marks\":[{\"type\":\"bold\"}]}]}]}]}]}]}";
        var encoded = codec.encode(input, true);
        assertTrue(encoded.contentJson().contains("tableHeader"));
        assertEquals("| **能力** |\n| --- |", encoded.bodyMarkdown());
    }

    @Test
    void codeBlockMustNotSilentlyDropInlineFormatting() {
        var codec = new ChapterDocumentCodec();
        assertThrows(IllegalArgumentException.class, () -> codec.encode("{\"type\":\"doc\",\"content\":[{\"type\":\"codeBlock\",\"content\":[{\"type\":\"text\",\"text\":\"code\",\"marks\":[{\"type\":\"bold\"}]}]}]}", true));
    }
}
