package com.dataentry.repository;

import com.dataentry.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    Optional<MfaRecoveryCode> findByCodeHash(String codeHash);

    List<MfaRecoveryCode> findAllByUserId(Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);

    void deleteAllByUserId(Long userId);
}
