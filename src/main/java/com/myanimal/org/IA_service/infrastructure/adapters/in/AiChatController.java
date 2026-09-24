package com.myanimal.org.IA_service.infrastructure.adapters.in;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.InvalidChatRequestException;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.attachment.AttachmentProcessor;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatUseCase chatUseCase;
    private final UserContextProvider userContextProvider;
    private final AttachmentProcessor attachmentProcessor;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatResult> chat(
            @RequestParam(value = "conversationId", required = false) UUID conversationId,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {

        boolean noMessage = message == null || message.isBlank();
        boolean noFiles = files == null || files.length == 0;
        if (noMessage && noFiles) {
            throw new InvalidChatRequestException("Debes enviar un mensaje, al menos un archivo, o ambos.");
        }

        List<UploadedFile> attachments = attachmentProcessor.process(files);
        UserContext userContext = userContextProvider.current();
        ChatResult result = chatUseCase.chat(userContext, conversationId, message, attachments);
        return ResponseEntity.ok(result);
    }
}
