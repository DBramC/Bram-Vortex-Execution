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
    @Column(name = "id", insertable = false, updatable = false)
    private String jobId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "validated_master_zip", columnDefinition = "bytea", insertable = false, updatable = false)
    private byte[] masterZip;

    // Βάζουμε ΜΟΝΟ Getters. Καθόλου Setters για να είναι 100% Read-Only!
    public String getJobId() { return jobId; }
    public byte[] getMasterZip() {
        return masterZip;
    }
}