package com.example.urlshortener.controller;

import com.example.urlshortener.model.Url;
import com.example.urlshortener.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;

    @PostMapping("/shorten")
    public ResponseEntity<String> shorten(@RequestParam String url) {
        String shortCode = urlService.createShortUrl(url);
        return ResponseEntity.ok("http://localhost:8080/" + shortCode);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = urlService.getOriginalUrl(shortCode);
        return ResponseEntity
                .status(302)
                .location(URI.create(originalUrl))
                .build();
    }

    @GetMapping
    public ResponseEntity<List<Url>> getAll(){
        List<Url> allUrls= urlService.getAll();
        return ResponseEntity.ok(allUrls);
    }
}