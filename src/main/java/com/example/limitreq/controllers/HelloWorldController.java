package com.example.limitreq.controllers;

import com.example.limitreq.annotation.RequestFrequencyLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("test")
public class HelloWorldController {

    @RequestFrequencyLimit(15000)
    @GetMapping("hello")
    public String sayHello() {
        return "hello,world!";
    }

    @RequestFrequencyLimit(9000)
    @PostMapping("fuck")
    public String sayFuck() {
        return "Fuck!";
    }

    @GetMapping("yeah")
    public String sayYeah() {
        return "Yeah!";
    }
}
