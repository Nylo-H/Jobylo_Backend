package com.example.TestAPI.Service.Message;

import com.example.TestAPI.DTO.Message.ConversationResponse;
import com.example.TestAPI.DTO.Message.MessageResponse;
import com.example.TestAPI.DTO.Message.NotificationEvent;
import com.example.TestAPI.DTO.Message.ReadReceiptEvent;
import com.example.TestAPI.DTO.Message.SendMessageRequest;
import com.example.TestAPI.Model.Conversation;
import com.example.TestAPI.Model.Enum.ActionType;
import com.example.TestAPI.Model.JobOffer;
import com.example.TestAPI.Model.Message;
import com.example.TestAPI.Model.User;
import com.example.TestAPI.Repository.ConversationRepository;
import com.example.TestAPI.Repository.JobOfferRepository;
import com.example.TestAPI.Repository.MessageRepository;
import com.example.TestAPI.Service.Audit.AuditService;
import com.example.TestAPI.Service.Audit.KycGuard;
import com.example.TestAPI.exception.BusinessException;
import com.example.TestAPI.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class MessageServiceImpl implements MessageService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final JobOfferRepository jobRepository;
    private final KycGuard kycGuard;
    private final AuditService auditService;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public MessageResponse startConversation(User sender, UUID jobId, String content) {
        kycGuard.requireVerified(sender);

        JobOffer job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("Offre non trouvée", ErrorCode.NOT_FOUND));

        if (job.getCreator().getId().equals(sender.getId())) {
            throw new BusinessException("Vous ne pouvez pas démarrer une conversation avec vous-même", ErrorCode.BAD_REQUEST);
        }

        User participant1 = sortParticipants(sender, job.getCreator());
        User participant2 = participant1.getId().equals(sender.getId()) ? job.getCreator() : sender;

        Conversation existing = conversationRepository
                .findByJobAndParticipant1AndParticipant2(job, participant1, participant2)
                .orElse(null);

        Conversation conversation;
        if (existing != null) {
            conversation = existing;
        } else {
            conversation = Conversation.builder()
                    .job(job)
                    .participant1(participant1)
                    .participant2(participant2)
                    .createdAt(new Date())
                    .lastMessageAt(new Date())
                    .lastMessageContent(content)
                    .build();
            conversation = conversationRepository.save(conversation);
        }

        User receiver = getOtherParticipant(sender, conversation);

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .job(job)
                .conversation(conversation)
                .content(content)
                .timestamp(new Date())
                .isRead(false)
                .build();

        message = messageRepository.save(message);

        conversation.setLastMessageAt(new Date());
        conversation.setLastMessageContent(content);
        conversationRepository.save(conversation);

        auditService.log(sender, ActionType.START_CONVERSATION, "Conv: " + conversation.getId() + " Job: " + jobId);

        MessageResponse response = toMessageResponse(message);

        messagingTemplate.convertAndSend("/topic/messages/" + conversation.getId(), response);
        messagingTemplate.convertAndSendToUser(sender.getId().toString(), "/queue/messages", response);
        messagingTemplate.convertAndSendToUser(receiver.getId().toString(), "/queue/messages", response);

        long receiverUnread = messageRepository.countByConversationAndReceiverAndIsReadFalse(conversation, receiver);
        long senderUnread = messageRepository.countByConversationAndReceiverAndIsReadFalse(conversation, sender);

        pushNotification(conversation, sender, receiver, response, receiverUnread);
        pushNotification(conversation, sender, sender, response, senderUnread);

        return response;
    }

    @Override
    public MessageResponse sendMessage(User sender, SendMessageRequest request) {
        kycGuard.requireVerified(sender);

        Conversation conversation = conversationRepository.findById(request.conversationId())
                .orElseThrow(() -> new BusinessException("Conversation introuvable", ErrorCode.NOT_FOUND));

        verifyParticipant(sender, conversation);

        User receiver = getOtherParticipant(sender, conversation);

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .job(conversation.getJob())
                .conversation(conversation)
                .content(request.content())
                .timestamp(new Date())
                .isRead(false)
                .build();

        message = messageRepository.save(message);

        conversation.setLastMessageAt(new Date());
        conversation.setLastMessageContent(request.content());
        conversationRepository.save(conversation);

        auditService.log(sender, ActionType.SEND_MESSAGE, "Conv: " + conversation.getId());

        MessageResponse response = toMessageResponse(message);

        messagingTemplate.convertAndSend("/topic/messages/" + conversation.getId(), response);
        messagingTemplate.convertAndSendToUser(sender.getId().toString(), "/queue/messages", response);
        messagingTemplate.convertAndSendToUser(receiver.getId().toString(), "/queue/messages", response);

        long receiverUnread = messageRepository.countByConversationAndReceiverAndIsReadFalse(conversation, receiver);
        long senderUnread = messageRepository.countByConversationAndReceiverAndIsReadFalse(conversation, sender);

        pushNotification(conversation, sender, receiver, response, receiverUnread);
        pushNotification(conversation, sender, sender, response, senderUnread);

        return response;
    }

    private void pushNotification(Conversation conversation, User sender, User target, MessageResponse msg, long unreadCount) {
        NotificationEvent event = new NotificationEvent(
                "NEW_MESSAGE",
                conversation.getId(),
                conversation.getJob().getId(),
                conversation.getJob().getTitle(),
                sender.getId(),
                sender.getUsername(),
                target.getId(),
                msg.content(),
                msg.timestamp(),
                unreadCount
        );
        messagingTemplate.convertAndSend("/topic/notifications/" + target.getId(), event);
    }

    @Override
    public Page<MessageResponse> getMessagesByConversation(User currentUser, UUID conversationId, int page, int size) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("Conversation introuvable", ErrorCode.NOT_FOUND));

        verifyParticipant(currentUser, conversation);

        Pageable pageable = PageRequest.of(page, size);
        return messageRepository.findByConversationOrderByTimestampAsc(conversation, pageable)
                .map(this::toMessageResponse);
    }

    @Override
    public void markAsRead(User currentUser, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException("Message non trouvé", ErrorCode.NOT_FOUND));

        if (!message.getReceiver().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous ne pouvez pas marquer ce message comme lu", ErrorCode.FORBIDDEN);
        }

        message.setRead(true);
        messageRepository.save(message);

        pushReadReceipt(message.getConversation(), currentUser);
    }

    @Override
    @Transactional
    public Map<String, Object> markAllConversationAsRead(User currentUser, UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("Conversation introuvable", ErrorCode.NOT_FOUND));

        verifyParticipant(currentUser, conversation);

        int updated = messageRepository.markAllAsReadByConversationAndReceiver(conversation, currentUser);

        if (updated > 0) {
            pushReadReceipt(conversation, currentUser);
        }

        return Map.of("markedRead", updated);
    }

    private void pushReadReceipt(Conversation conversation, User reader) {
        ReadReceiptEvent event = new ReadReceiptEvent(
                conversation.getId(),
                reader.getId(),
                reader.getUsername(),
                new Date()
        );

        messagingTemplate.convertAndSend("/topic/read/" + conversation.getId(), event);
    }

    @Override
    public List<ConversationResponse> getConversations(User currentUser) {
        List<Conversation> conversations = conversationRepository
                .findByParticipantOrderByLastMessageAtDesc(currentUser);

        return conversations.stream().map(conv -> {
            User otherUser = getOtherParticipant(currentUser, conv);
            long unreadCount = messageRepository.countByConversationAndReceiverAndIsReadFalse(conv, currentUser);

            return new ConversationResponse(
                    conv.getId(),
                    conv.getJob().getId(),
                    conv.getJob().getTitle(),
                    otherUser.getId(),
                    otherUser.getUsername(),
                    conv.getLastMessageContent(),
                    conv.getLastMessageAt(),
                    unreadCount
            );
        }).collect(Collectors.toList());
    }

    @Override
    public long getUnreadCount(User currentUser) {
        return messageRepository.countByReceiverAndIsReadFalse(currentUser);
    }

    private void verifyParticipant(User user, Conversation conversation) {
        boolean isP1 = conversation.getParticipant1().getId().equals(user.getId());
        boolean isP2 = conversation.getParticipant2().getId().equals(user.getId());
        if (!isP1 && !isP2) {
            throw new BusinessException("Vous n'êtes pas participant à cette conversation", ErrorCode.FORBIDDEN);
        }
    }

    private User getOtherParticipant(User currentUser, Conversation conversation) {
        if (conversation.getParticipant1().getId().equals(currentUser.getId())) {
            return conversation.getParticipant2();
        }
        return conversation.getParticipant1();
    }

    private User sortParticipants(User a, User b) {
        return a.getId().compareTo(b.getId()) < 0 ? a : b;
    }

    private MessageResponse toMessageResponse(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getSender().getId(),
                message.getSender().getUsername(),
                message.getReceiver().getId(),
                message.getReceiver().getUsername(),
                message.getJob().getId(),
                message.getContent(),
                message.getTimestamp(),
                message.isRead()
        );
    }
}
