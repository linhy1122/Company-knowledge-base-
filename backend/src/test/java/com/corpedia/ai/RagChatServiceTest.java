package com.corpedia.ai;
import com.corpedia.config.RagProperties;
import com.corpedia.mapper.MessageMapper;
import com.corpedia.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagChatServiceTest {
    private final RagRetrieveService retrieve = mock(RagRetrieveService.class);
    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
    private final RagProperties cfg = new RagProperties();
    private final RagChatService service = new RagChatService(retrieve,cfg,builder,permissions,mock(MessageMapper.class),access,mock(AmapWeatherTool.class));

    @Test void noEvidenceRefusesWithoutInvokingModel() {
        when(retrieve.retrieve("unknown",10,null)).thenReturn(List.of());
        var answer = service.chat(1L,null,"unknown");
        assertFalse(answer.answered()); assertTrue(answer.sources().isEmpty());
        verify(builder.build(),never()).prompt();
    }
    @Test void revokedDocumentIsExcludedBeforePromptConstruction() {
        var hit = new RetrievedChunk(4L,"机密文件","doc-4-0","不得泄漏的文本",0.99,null,null);
        when(retrieve.retrieve("policy",10,null)).thenReturn(List.of(hit));
        when(access.canUseSource(4L)).thenReturn(false);
        assertFalse(service.chat(1L,null,"policy").answered());
        verify(builder.build(),never()).prompt();
    }
    @Test void emptyModelResponseIsRecordedAsRefusal() {
        var hit = new RetrievedChunk(4L,"员工手册","doc-4-0","年假说明",0.99,null,null);
        when(retrieve.retrieve("policy",10,null)).thenReturn(List.of(hit));
        when(access.canUseSource(4L)).thenReturn(true);
        when(retrieve.rerank(List.of(hit),3)).thenReturn(List.of(hit));
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content()).thenReturn(" ");
        var result = service.chat(1L,null,"policy");
        assertFalse(result.answered()); assertTrue(result.sources().isEmpty());
    }
}
