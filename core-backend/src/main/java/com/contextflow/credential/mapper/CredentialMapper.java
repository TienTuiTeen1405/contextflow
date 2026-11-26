package com.contextflow.credential.mapper;

import com.contextflow.credential.dto.PrivacyAcknowledgementResponse;
import com.contextflow.credential.dto.UserCredentialStatusResponse;
import com.contextflow.credential.entity.AiCredential;
import com.contextflow.credential.entity.PrivacyDisclosureAcknowledgement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CredentialMapper {
    @Mapping(target = "configured", constant = "true")
    @Mapping(target = "credentialId", source = "credential.id")
    @Mapping(target = "disclosureRequired", source = "disclosureRequired")
    UserCredentialStatusResponse toStatus(AiCredential credential, boolean disclosureRequired);

    PrivacyAcknowledgementResponse toReceipt(PrivacyDisclosureAcknowledgement receipt);
}
