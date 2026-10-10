package com.writingagent.v2.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** One same-origin workbench entry for both manual writing and agent interaction. */
@Controller
public class WorkbenchPageController {
    @GetMapping("/")
    public String home() {
        return "redirect:/workbench/";
    }
    @GetMapping({"/workbench", "/workbench/"})
    public String workbench() {
        return "forward:/workbench/index.html";
    }
}
