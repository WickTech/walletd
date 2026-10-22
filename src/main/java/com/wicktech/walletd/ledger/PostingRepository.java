package com.wicktech.walletd.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    List<Posting> findByAccountIdOrderByIdDesc(UUID accountId, Pageable page);

    List<Posting> findByJournalEntryIdOrderById(UUID journalEntryId);
}
