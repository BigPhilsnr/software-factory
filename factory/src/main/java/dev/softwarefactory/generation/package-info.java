/**
 * Chapter 2 - agents produce work: a task's prompt is assembled from governed inputs, then answered by a
 * live Claude agent (via Google ADK, with bounded read-only tools) or by a recorded fixture. Agents only
 * return text; they cannot change code, validate or approve.
 * Depends on {@code scenario}, {@code candidate}, {@code governance} and {@code platform}.
 */
package dev.softwarefactory.generation;
