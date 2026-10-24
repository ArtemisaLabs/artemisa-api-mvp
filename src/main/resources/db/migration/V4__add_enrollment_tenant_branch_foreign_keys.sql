ALTER TABLE enrollments
    ADD CONSTRAINT fk_enrollments_class_group_branch
        FOREIGN KEY (tenant_id, branch_id, class_group_id)
            REFERENCES class_groups (tenant_id, branch_id, id);