package com.contextflow.conversation.mapper;

import com.contextflow.conversation.dto.MainReferenceResponse;
import com.contextflow.conversation.dto.QuoteSavedResponse;
import com.contextflow.conversation.dto.TurnResponse;
import com.contextflow.conversation.entity.Turn;
import com.contextflow.conversation.entity.TurnMainReference;
import com.contextflow.conversation.entity.TurnQuoteReference;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        uses = TurnPayloadConverter.class)
public interface TurnMapper {
    @Mapping(
            target = "userContentBlocks",
            source = "turn.userContentBlocks",
            qualifiedByName = "contentBlocks")
    @Mapping(target = "quotes", source = "quotes")
    @Mapping(target = "mainReferences", source = "mainReferences")
    TurnResponse toResponse(
            Turn turn, List<TurnQuoteReference> quotes, List<TurnMainReference> mainReferences);

    @Mapping(target = "quoteRefId", source = "id")
    @Mapping(target = "locator", source = "locator", qualifiedByName = "locator")
    @Mapping(target = "contentHashSha256Hex", source = "contentHash", qualifiedByName = "sha256Hex")
    QuoteSavedResponse toQuoteResponse(TurnQuoteReference quote);

    MainReferenceResponse toMainReferenceResponse(TurnMainReference reference);
}
