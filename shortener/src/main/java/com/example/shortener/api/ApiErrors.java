package com.example.shortener.api;

import com.example.shortener.domain.ShortenerService;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    @ExceptionHandler(ShortenerController.NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
    }

    @ExceptionHandler(ShortenerService.AliasConflictException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "alias_conflict"));
    }

    @ExceptionHandler({DataAccessException.class, ShortenerService.CapacityException.class})
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "temporarily_unavailable"));
    }
}
