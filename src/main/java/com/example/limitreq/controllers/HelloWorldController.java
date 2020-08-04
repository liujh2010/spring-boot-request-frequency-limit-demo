package com.example.limitreq.controllers;

import com.example.limitreq.annotation.RequestFrequencyLimit;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("test")
public class HelloWorldController {

    @RequestFrequencyLimit(15000)
    @GetMapping("hello")
    public String sayHello() {
        return "hello,world!";
    }

    @RequestFrequencyLimit(9000)
    @PostMapping("hi")
    public String sayFuck() {
        return "Hi!";
    }

    @GetMapping("yeah")
    public String sayYeah() {
        return "Yeah!";
    }

    @RequestFrequencyLimit(15000)
    @GetMapping("talk/{something}/right")
    public String sayShit(@PathVariable String something) {
        return "You say " + something + " right.";
    }
}
