package com.writingagent.v2;

import com.writingagent.v2.web.LegacyWriteGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class LegacyWriteGuardTest {
    @Test void oldWritesCannotBypassDocumentCasOrProposalConfirmation() throws Exception {
        for(String path : new String[]{"/projects/p/turns", "/projects/p/plans/r/confirm", "/projects/p/plans/r/edit",
                "/projects/p/articles/a/chapters/1/edit", "/projects/p/articles/a/complete"}) {
            var request=new MockHttpServletRequest("POST","/api/writing/v2"+path);
            var response=new MockHttpServletResponse();
            new LegacyWriteGuard().doFilter(request,response,(q,s)->fail("Legacy business must not execute"));
            assertEquals(409,response.getStatus());
            assertTrue(response.getContentAsString().contains("LEGACY_WRITE_ENDPOINT_DISABLED"));
        }
    }
    @Test void newDocumentWritesAndReadOnlyLegacyPathsRemainReachable() throws Exception {
        for(String path:new String[]{"/api/writing/v2/projects/p/plans", "/api/writing/v2/proposals/r/decision", "/api/writing/v2/projects/p/chapters/k"}) {
            var request=new MockHttpServletRequest("POST",path);
            var response=new MockHttpServletResponse();
            boolean[] reached={false};
            new LegacyWriteGuard().doFilter(request,response,(q,s)->reached[0]=true);
            assertTrue(reached[0]);
        }
    }
}
