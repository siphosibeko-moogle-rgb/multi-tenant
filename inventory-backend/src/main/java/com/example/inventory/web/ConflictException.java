package com.example.inventory.web;

public class ConflictException extends RuntimeException {

    private final String problemType;

    public ConflictException(String message, String problemType) {
        super(message);
        this.problemType = problemType;
    }

    public String problemType() {
        return problemType;
    }
}
