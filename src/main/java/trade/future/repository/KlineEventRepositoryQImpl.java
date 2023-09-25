package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.KlineEventEntity;
import trade.future.model.entity.QKlineEntity;
import trade.future.model.entity.QKlineEventEntity;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.util.List;

import static trade.future.model.entity.QKlineEventEntity.klineEventEntity;

@Repository
public class KlineEventRepositoryQImpl implements KlineEventRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    public List<KlineEventEntity> findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(String symbol, BigDecimal currentPrice) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QKlineEventEntity qKlineEvent = QKlineEventEntity.klineEventEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;

        List<KlineEventEntity> result = queryFactory
            .selectFrom(qKlineEvent)
            .join(qKlineEvent.kline, qKline).fetchJoin()
            .where(
                qKlineEvent.kline.symbol.eq(symbol),
                qKlineEvent.goalPriceCheck.eq(false),
                qKlineEvent.plusGoalPrice.lt(currentPrice)
            )
            .fetch();
        return result;
    }

    public List<KlineEventEntity> findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(String symbol, BigDecimal currentPrice) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QKlineEventEntity qKlineEvent = QKlineEventEntity.klineEventEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;

        List<KlineEventEntity> result = queryFactory
            .selectFrom(qKlineEvent)
            .join(qKlineEvent.kline, qKline).fetchJoin()
            .where(
                qKlineEvent.kline.symbol.eq(symbol),
                qKlineEvent.goalPriceCheck.eq(false),
                qKlineEvent.minusGoalPrice.gt(currentPrice)
            )
            .fetch();

        return result;
    }
}