/**
 * The spine of the story - how a run progresses: {@code RunEngine} starts a run, advances its task graph
 * one committed transition at a time, and applies operator decisions (approve, clarify, revise). Each task
 * kind has one executor; failures become a retry, a required revision, an infrastructure pause or a safe stop.
 * Depends on {@code scenario}, {@code generation}, {@code candidate}, {@code validation},
 * {@code governance}, {@code audit} and {@code platform}.
 */
package dev.softwarefactory.run;
