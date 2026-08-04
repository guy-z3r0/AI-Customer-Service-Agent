package com.ulab.agent.api;

import com.ulab.agent.utils.Lang;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hands the panel every word it displays.
 *
 * The rule is that no English or Bangla text is written into the JavaScript, so
 * the panel asks for its whole vocabulary once at start-up and looks strings up
 * by key. Switching language is then a second call to this endpoint.
 */
@RestController
@RequestMapping("/api/lang")
public class LangController {

    @GetMapping
    public Map<String, String> strings(@RequestParam(defaultValue = "en") String lang) {
        return Lang.ui(lang);
    }
}
