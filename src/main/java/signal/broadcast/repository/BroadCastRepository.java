package signal.broadcast.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;
import signal.broadcast.model.entity.BroadCastEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface BroadCastRepository extends JpaRepository<BroadCastEntity, String>, QuerydslPredicateExecutor<BroadCastEntity>, BroadCastRepositoryQ {
    List<BroadCastEntity> findBySymbolAndBroadCastStatus(String symbol, String broad);
    Optional<BroadCastEntity> findByStreamId(int streamId);
}