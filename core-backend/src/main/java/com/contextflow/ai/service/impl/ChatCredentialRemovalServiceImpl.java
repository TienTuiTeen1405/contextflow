package com.contextflow.ai.service.impl;

import com.contextflow.ai.repository.ChatCredentialRemovalRepository;
import com.contextflow.ai.service.ChatCredentialRemovalService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatCredentialRemovalServiceImpl implements ChatCredentialRemovalService {
    private final ChatCredentialRemovalRepository repository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelAfterRemoval(UUID userId, UUID credentialId, long credentialVersion) {
        repository.invalidateDependentEpochs(userId, credentialId, credentialVersion);
        repository.cancelOpenRequests(userId);
    }
}
