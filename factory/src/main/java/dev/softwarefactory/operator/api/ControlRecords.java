package dev.softwarefactory.operator.api;

import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.ChatLedger;
import dev.softwarefactory.run.DurableRunStore;

/** The control-database views the operator surfaces read and write: runs, their audit trail, the chat ledger. */
public record ControlRecords(DurableRunStore runs, AuditTrail trail, ChatLedger chat) {}
