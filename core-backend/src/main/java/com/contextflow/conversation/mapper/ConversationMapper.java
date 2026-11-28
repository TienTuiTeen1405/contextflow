package com.contextflow.conversation.mapper;

import com.contextflow.conversation.dto.ConversationResponse;
import com.contextflow.conversation.dto.StageOutlineItem;
import com.contextflow.conversation.entity.Conversation;
import com.contextflow.conversation.entity.MainStage;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ConversationMapper {
    @Mapping(target = "mainStageId", source = "mainStageId")
    ConversationResponse toResponse(Conversation conversation, UUID mainStageId);

    StageOutlineItem toStageOutlineItem(MainStage stage);
}
