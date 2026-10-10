package com.writingagent.v2;

import com.writingagent.v2.web.WorkbenchPageController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkbenchPageRouteTest {
    @Test void rootOpensTheOnlySupportedWorkbench() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WorkbenchPageController()).build();
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/workbench/"));
    }
    @Test void sameOriginEntryForwardsToPackagedUi() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WorkbenchPageController()).build();
        mvc.perform(get("/workbench/")).andExpect(forwardedUrl("/workbench/index.html"));
        mvc.perform(get("/workbench")).andExpect(forwardedUrl("/workbench/index.html"));
    }
}
