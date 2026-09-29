package org.chorus.repository;

import org.chorus.entity.Relationship;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RelationshipRepository extends JpaRepository<Relationship, Long> {
    List<Relationship> findByFromId(Long fromId);
    List<Relationship> findByToId(Long toId);
    Optional<Relationship> findByFromIdAndToId(Long fromId, Long toId);
    void deleteByFromIdAndToId(Long fromId, Long toId);
}
