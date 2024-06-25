package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.*;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class TradingRepositoryQImpl implements TradingRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<TradingEntity> findByUserCd(String userCd) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QEventEntity qKlineEvent = QEventEntity.eventEntity;
        QTradingEntity qTradingEntity = QTradingEntity.tradingEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;
        QPositionEntity qPosition = QPositionEntity.positionEntity;

        List<TradingEntity> result =
                queryFactory
                        .selectFrom(qTradingEntity)
                        .where(
                            qTradingEntity.userCd.eq(userCd)
                        )
                        .orderBy(qTradingEntity.insertDt.desc())
                        .fetch();
        return result;
    }
}