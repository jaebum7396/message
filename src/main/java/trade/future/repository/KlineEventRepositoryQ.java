package trade.future.repository;

import trade.future.model.entity.KlineEventEntity;
import java.util.List;

public interface KlineEventRepositoryQ {
    List<KlineEventEntity> getNoneCheckKlineEvent(boolean goalPriceCheck);
}
