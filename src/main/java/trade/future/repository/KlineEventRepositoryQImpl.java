package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.KlineEventEntity;
import trade.future.model.entity.QKlineEntity;
import trade.future.model.entity.QKlineEventEntity;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;

@Repository
public class KlineEventRepositoryQImpl implements KlineEventRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<KlineEventEntity> getNoneCheckKlineEvent(boolean goalPriceCheck) {
        // JPAQueryFactory를 사용하여 주문 정보를 조회합니다.
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QKlineEventEntity qKlineEvent = QKlineEventEntity.klineEventEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;

        List<KlineEventEntity> klineEventArray = queryFactory
                .selectFrom(qKlineEvent)
                .leftJoin(qKlineEvent.kline, qKline).fetchJoin()
                .where(qKlineEvent.goalPriceCheck.eq(goalPriceCheck))
                .fetch();

        return klineEventArray;
    }
}