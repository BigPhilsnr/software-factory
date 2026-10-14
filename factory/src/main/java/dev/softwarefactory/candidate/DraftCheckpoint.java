package dev.softwarefactory.candidate;

/** Durable identity and integrity of an unapproved drafting workspace. */
public record DraftCheckpoint(String directory, String baseCommit, String inputHash, String diffHash) {}
