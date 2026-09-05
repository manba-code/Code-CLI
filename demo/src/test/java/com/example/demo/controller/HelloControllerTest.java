package com.example.demo.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void greet_withNormalName_returnsMessage() throws Exception {
        mockMvc.perform(get("/api/greet/Alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("你好, Alice!"));
    }

    @Test
    void greet_withNameContainingSpaces_returnsMessage() throws Exception {
        mockMvc.perform(get("/api/greet/{name}", "张三 丰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("你好, 张三 丰!"));
    }

    @Test
    void greet_withEmptyName_returns400() throws Exception {
        mockMvc.perform(get("/api/greet"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void greet_withBlankName_returns400() throws Exception {
        mockMvc.perform(get("/api/greet/{name}", "   "))
                .andExpect(status().isBadRequest());
    }
}
