package de.kaliburg.morefair.chat;

import de.kaliburg.morefair.account.entity.Account;
import de.kaliburg.morefair.account.service.AccountService;
import de.kaliburg.morefair.account.type.AccountAccessRole;
import de.kaliburg.morefair.dto.ChatDTO;
import de.kaliburg.morefair.ladder.Ranker;
import de.kaliburg.morefair.ladder.RankerService;
import de.kaliburg.morefair.websockets.messages.WSEmptyMessage;
import de.kaliburg.morefair.websockets.messages.WSMetaMessage;
import de.kaliburg.morefair.utils.RequestThrottler;
import de.kaliburg.morefair.utils.WSUtils;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.util.HtmlUtils;

import java.util.UUID;

@Log4j2
@Controller
public class ChatController {
    public static final String CHAT_DESTINATION = "/queue/chat/";
    public static final String CHAT_UPDATE_DESTINATION = "/topic/chat/";
    private final MessageService messageService;
    private final AccountService accountService;
    private final RankerService rankerService;
    private final WSUtils wsUtils;
    private final RequestThrottler throttler;

    public ChatController(MessageService messageService, AccountService accountService, RankerService rankerService, WSUtils wsUtils, RequestThrottler throttler) {
        this.messageService = messageService;
        this.accountService = accountService;
        this.rankerService = rankerService;
        this.wsUtils = wsUtils;
        this.throttler = throttler;
    }

    @MessageMapping("/chat/init/{number}")
    public void initChat(SimpMessageHeaderAccessor sha, WSEmptyMessage wsMessage, @DestinationVariable("number") Integer number) {
        try {
            String uuid = StringEscapeUtils.escapeJava(wsMessage.getUuid());
            log.debug("/app/chat/init/{} from {}", number, uuid);
            Account account = accountService.findAccountByUUID(UUID.fromString(uuid));
            if (account == null || account.getAccessRole().equals(AccountAccessRole.BANNED_PLAYER)) {
                wsUtils.convertAndSendToUser(sha, CHAT_DESTINATION, HttpStatus.FORBIDDEN);
                return;
            }

            Ranker ranker = rankerService.findHighestActiveRankerByAccount(account);

            if (ranker == null) {
                rankerService.getLadderSem().acquire();
                try {
                    ranker = rankerService.createNewActiveRankerForAccountOnLadder(account, 1);
                } finally {
                    rankerService.getLadderSem().release();
                }
            }

            if (account.getAccessRole().equals(AccountAccessRole.OWNER) || account.getAccessRole().equals(AccountAccessRole.MODERATOR)
                    || number <= ranker.getLadder().getNumber()) {
                ChatDTO c = messageService.getChat(number);
                wsUtils.convertAndSendToUser(sha, CHAT_DESTINATION, c);
            } else {
                wsUtils.convertAndSendToUser(sha, CHAT_DESTINATION, HttpStatus.INTERNAL_SERVER_ERROR);
            }

        } catch (IllegalArgumentException e) {
            wsUtils.convertAndSendToUser(sha, CHAT_DESTINATION, HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            wsUtils.convertAndSendToUser(sha, CHAT_DESTINATION, HttpStatus.INTERNAL_SERVER_ERROR);
            log.error(e.getMessage());
            e.printStackTrace();
        }
    }

    @MessageMapping("/chat/post/{number}")
    public void postChat(WSMetaMessage wsMessage, @DestinationVariable("number") Integer number) {
        try {
            String uuid = StringEscapeUtils.escapeJava(wsMessage.getUuid());
            String message = wsMessage.getContent();
            String metadata = wsMessage.getMetadata();
            message = message.trim();
            if (message.length() > 140) message = message.substring(0, 140);
            message = StringEscapeUtils.escapeJava(message);

            Account account = accountService.findAccountByUUID(UUID.fromString(uuid));
            if (account == null || account.getAccessRole().equals(AccountAccessRole.MUTED_PLAYER) || account.getAccessRole().equals(AccountAccessRole.BANNED_PLAYER))
                return;
            if (account.getAccessRole().equals(AccountAccessRole.MODERATOR) || account.getAccessRole().equals(AccountAccessRole.OWNER)
                    || (number <= rankerService.findHighestActiveRankerByAccount(account).getLadder().getNumber()
                    && throttler.canPostMessage(account.getUuid()))) {
                Message answer = messageService.writeMessage(account, number, message, metadata);
                wsUtils.convertAndSendToAll(CHAT_UPDATE_DESTINATION + number, answer.convertToDTO());
            }
        } catch (Exception e) {
            log.error(e.getMessage());
            e.printStackTrace();
        }
    }
}
