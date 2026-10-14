/**
 * Chapter 2 - agents produce work: a task's prompt is assembled from governed inputs, then answered by a
 * live Claude agent (via Google ADK) or by a recorded fixture. PATCH agents have scoped draft editing
 * and trusted validation tools; document/chat agents remain read-only. Agents cannot approve changes.
 * Depends on {@code scenario}, {@code candidate}, {@code governance} and {@code platform}.
 */
package dev.softwarefactory.generation;
