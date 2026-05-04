package com.christos_bramis.bram_vortex_execution.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "validator_jobs")
public class ValidatorJob {

    @Id
    @Column(name = "analysis_job_id", insertable = false, updatable = false)
    private String analysisJobId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "validated_master_zip", columnDefinition = "bytea", insertable = false, updatable = false)
    private byte[] masterZip;

    // Βάζουμε ΜΟΝΟ Getters. Καθόλου Setters για να είναι 100% Read-Only!
    public String getJobId() { return analysisJobId; }
    public byte[] getMasterZip() {
        return masterZip;
    }
}