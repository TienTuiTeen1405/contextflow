package com.contextflow.context.mapper;

import com.contextflow.context.dto.CompiledContextResult;
import com.contextflow.context.dto.ContextIndicatorResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ContextIndicatorMapper {
    @Mapping(source = "mandatoryRulesCount", target = "mandatoryRules")
    @Mapping(source = "overridableRulesCount", target = "overridableRules")
    @Mapping(source = "activeSkillsCount", target = "activeSkills")
    @Mapping(source = "activeWindowTurnsCount", target = "activeWindowTurns")
    ContextIndicatorResponse toResponse(CompiledContextResult compiled);
}
