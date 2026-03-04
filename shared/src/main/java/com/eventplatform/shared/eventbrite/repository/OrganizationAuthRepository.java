package com.eventplatform.shared.eventbrite.repository;

import com.eventplatform.shared.eventbrite.domain.OrganizationAuth;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationAuthRepository extends JpaRepository<OrganizationAuth, Long> {
    Optional<OrganizationAuth> findByOrganizationId(Long organizationId);
}
