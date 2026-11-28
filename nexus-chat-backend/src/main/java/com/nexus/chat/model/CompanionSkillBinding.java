package com.nexus.chat.model;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
@Entity @Table(name="companion_skill_bindings",uniqueConstraints=@UniqueConstraint(name="uq_role_skill",columnNames={"user_id","role_id","skill_id"})) @Data
public class CompanionSkillBinding {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="user_id",nullable=false) private Long userId;
 @Column(name="role_id",nullable=false) private Long roleId;
 @Column(name="skill_id",nullable=false) private Long skillId;
 @Column(nullable=false) private int revision;
 @Column(nullable=false) private boolean enabled=true;
 @Column(name="replace_identity",nullable=false) private boolean replaceIdentity;
 @Version private Long version;
 @UpdateTimestamp @Column(name="updated_at") private LocalDateTime updatedAt;
}
