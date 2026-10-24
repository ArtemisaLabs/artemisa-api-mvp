CREATE TABLE attendance_events
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    branch_id                UUID         NOT NULL,
    class_group_id           UUID         NOT NULL,
    student_id               UUID         NOT NULL,
    attendance_date          DATE         NOT NULL,
    event_type               VARCHAR(20)  NOT NULL,
    event_at                 TIMESTAMPTZ  NOT NULL,
    client_reported_at       TIMESTAMPTZ,
    device_id                VARCHAR(120),
    idempotency_key          VARCHAR(128) NOT NULL,
    recorded_by_membership_id UUID        NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_attendance_events_tenant_branch_id
        UNIQUE (tenant_id, branch_id, id),
    CONSTRAINT uq_attendance_events_daily_type
        UNIQUE (tenant_id, branch_id, student_id, attendance_date, event_type),
    CONSTRAINT uq_attendance_events_idempotency
        UNIQUE (tenant_id, branch_id, idempotency_key),
    CONSTRAINT fk_attendance_event_group
        FOREIGN KEY (tenant_id, branch_id, class_group_id)
            REFERENCES class_groups (tenant_id, branch_id, id),
    CONSTRAINT fk_attendance_event_student
        FOREIGN KEY (tenant_id, branch_id, student_id)
            REFERENCES students (tenant_id, branch_id, id),
    CONSTRAINT fk_attendance_event_recorder
        FOREIGN KEY (tenant_id, recorded_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_attendance_event_type
        CHECK (event_type IN ('CHECK_IN', 'CHECK_OUT'))
);

CREATE INDEX idx_attendance_events_group_date
    ON attendance_events (tenant_id, branch_id, class_group_id, attendance_date, event_at);

CREATE INDEX idx_attendance_events_student_date
    ON attendance_events (tenant_id, branch_id, student_id, attendance_date, event_type);

CREATE TABLE attendance_corrections
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    branch_id                UUID         NOT NULL,
    attendance_event_id      UUID         NOT NULL,
    previous_event_at        TIMESTAMPTZ  NOT NULL,
    corrected_event_at       TIMESTAMPTZ  NOT NULL,
    reason                   VARCHAR(500) NOT NULL,
    corrected_by_membership_id UUID       NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_attendance_correction_event
        FOREIGN KEY (tenant_id, branch_id, attendance_event_id)
            REFERENCES attendance_events (tenant_id, branch_id, id),
    CONSTRAINT fk_attendance_correction_editor
        FOREIGN KEY (tenant_id, corrected_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_attendance_correction_reason
        CHECK (length(trim(reason)) > 0)
);

CREATE INDEX idx_attendance_corrections_latest
    ON attendance_corrections (tenant_id, branch_id, attendance_event_id, created_at DESC);
