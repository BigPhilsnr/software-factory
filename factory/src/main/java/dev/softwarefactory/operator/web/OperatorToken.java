package dev.softwarefactory.operator.web;

/** Page-issued capability for local operator mutations. Never a provider API key. */
public record OperatorToken(String value) {}
