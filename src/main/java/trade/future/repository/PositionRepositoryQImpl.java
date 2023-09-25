package trade.future.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.*;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class PositionRepositoryQImpl implements PositionRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<PositionEntity> getPositionByKlineEndTime(LocalDateTime endTime, String positionStatus) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QPositionEntity qPosition = QPositionEntity.positionEntity;
        QKlineEntity qKline = QKlineEntity.klineEntity;

        List<PositionEntity> result = queryFactory
                .selectFrom(qPosition)
                .join(qPosition.kline, qKline).fetchJoin()
                .where(
                    qPosition.kline.endTime.eq(endTime).or(qPosition.positionStatus.eq(positionStatus))
                )
                .fetch();
        //System.out.println(result);
        return result;
    }
}