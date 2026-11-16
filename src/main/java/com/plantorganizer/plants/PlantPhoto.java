package com.plantorganizer.plants;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A photo kept in a plant's health history (F05), so later checks can compare against it. */
@Entity
public class PlantPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long plantId;
    /** Stored as bytea (works in H2 and PostgreSQL; H2 in PostgreSQL mode has no BLOB type). */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(columnDefinition = "bytea")
    @Basic(fetch = FetchType.LAZY)
    private byte[] data;
    private String contentType;
    private LocalDateTime uploadedAt;

    protected PlantPhoto() {
    }

    public PlantPhoto(long plantId, byte[] data, String contentType, LocalDateTime uploadedAt) {
        this.plantId = plantId;
        this.data = data;
        this.contentType = contentType;
        this.uploadedAt = uploadedAt;
    }

    public Long getId() {
        return id;
    }

    public long getPlantId() {
        return plantId;
    }

    public byte[] getData() {
        return data;
    }

    public String getContentType() {
        return contentType;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
}
