package com.velora.api.seo.repository;

import com.velora.api.seo.domain.FaqItem;
import com.velora.api.seo.domain.FaqScope;
import com.velora.api.seo.domain.FaqStatus;
import com.velora.api.seo.domain.FaqTopic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FaqItemRepository extends JpaRepository<FaqItem, Long>, JpaSpecificationExecutor<FaqItem> {

    List<FaqItem> findByScopeAndTargetIdAndStatusOrderByPositionAscIdAsc(FaqScope scope, Long targetId,
                                                                          FaqStatus status);

    List<FaqItem> findByScopeAndStatusOrderByPositionAscIdAsc(FaqScope scope, FaqStatus status);

    List<FaqItem> findByScopeAndStatusAndTopicOrderByPositionAscIdAsc(FaqScope scope, FaqStatus status,
                                                                      FaqTopic topic);
}
