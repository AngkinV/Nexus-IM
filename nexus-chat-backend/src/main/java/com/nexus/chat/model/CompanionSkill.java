package com.nexus.chat.model;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
@Entity @Table(name="companion_skills", indexes=@Index(name="idx_skills_owner",columnList="user_id,deleted")) @Data
public class CompanionSkill {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="user_id",nullable=false) private Long userId;
 @Column(nullable=false,length=80) private String name;
 @Column(columnDefinition="TEXT",nullable=false) private String description;
 @Column(name="latest_revision",nullable=false) private int latestRevision;
 @Column(nullable=false) private boolean deleted;
 @Version private Long version;
 @CreationTimestamp @Column(name="created_at",updatable=false) private LocalDateTime createdAt;
 @UpdateTimestamp @Column(name="updated_at") private LocalDateTime updatedAt;
}
