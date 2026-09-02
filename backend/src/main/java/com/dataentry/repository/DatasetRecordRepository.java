package com.dataentry.repository;

import com.dataentry.model.DatasetRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetRecordRepository extends JpaRepository<DatasetRecord, Long> {
    List<DatasetRecord> findAllBySourceTicketIdIn(List<Long> sourceTicketIds);
    List<DatasetRecord> findAllByOrderByIdDesc(Pageable pageable);
    List<DatasetRecord> findAllByIdLessThanOrderByIdDesc(Long cursor, Pageable pageable);
}
