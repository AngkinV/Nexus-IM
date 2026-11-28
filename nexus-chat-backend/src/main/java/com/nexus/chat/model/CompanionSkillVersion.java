package com.nexus.chat.model;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
@Entity @Table(name="companion_skill_versions",uniqueConstraints=@UniqueConstraint(name="uq_skill_revision",columnNames={"skill_id","revision"})) @Data
public class CompanionSkillVersion {
 public enum Status { DRAFT, PUBLISHED }
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="user_id",nullable=false) private Long userId;
 @Column(name="skill_id",nullable=false) private Long skillId;
 @Column(nullable=false) private int revision;
 @Column(nullable=false,length=64) private String hash;
 @Column(name="package_json",columnDefinition="MEDIUMTEXT",nullable=false) private String packageJson;
 @Column(nullable=false,length=80) private String name;
 @Column(nullable=false,columnDefinition="TEXT") private String description;
 @Column(nullable=false,length=20) private String type;
 @Column(name="package_version",nullable=false,length=40) private String packageVersion;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status=Status.DRAFT;
 @Column(name="source_artifact_id",length=100) private String sourceArtifactId;
 @CreationTimestamp @Column(name="created_at",updatable=false) private LocalDateTime createdAt;
 @Column(name="published_at") private LocalDateTime publishedAt;
}
