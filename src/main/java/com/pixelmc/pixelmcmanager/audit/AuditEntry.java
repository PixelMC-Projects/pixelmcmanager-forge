package com.pixelmc.pixelmcmanager.audit;

public final class AuditEntry {
    public long time;
    public String sourceType = "";
    public String sourceName = "";
    public String sourceUuid = "";
    public String action = "";
    public String args = "";
    public String result = "";
    public String detail = "";

    public AuditEntry() {}

    public AuditEntry(
            long time,
            String sourceType,
            String sourceName,
            String sourceUuid,
            String action,
            String args,
            String result,
            String detail) {
        this.time = time;
        this.sourceType = sourceType;
        this.sourceName = sourceName;
        this.sourceUuid = sourceUuid;
        this.action = action;
        this.args = args;
        this.result = result;
        this.detail = detail;
    }
}
