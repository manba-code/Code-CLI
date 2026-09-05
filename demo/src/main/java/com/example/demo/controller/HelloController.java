package com.example.demo.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    @RequestMapping("/hello")
    public String hello() {
        return "Hello, Spring Boot!";
    }

    @RequestMapping("/hello2ge")
    public String hello2ge() {
        return "Hello, 二哥!";
    }

    @GetMapping(value = {"/greet", "/greet/{name}"})
    public ResponseEntity<Map<String, String>> greet(@PathVariable(required = false) String name) {
        if (name == null || name.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok(Map.of("message", "你好, " + name + "!"));
    }
}
