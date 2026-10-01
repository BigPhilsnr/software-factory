/**
 * The chat surface (ADK dev UI): literal slash commands are routed to the same service as the web API;
 * plain questions get a read-only conversational answer. Model text never reaches a workflow action.
 * Depends on {@code operator.api}.
 */
package dev.softwarefactory.operator.chat;
