package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentServiceTest {
    @Test
    void numericIdAndLegacyUsernameResolveToSameOwner() {
        DocumentService service = new DocumentService();
        UserRepository users = mock(UserRepository.class);
        FileUploadRepository files = mock(FileUploadRepository.class);
        OrgTagCacheService tags = mock(OrgTagCacheService.class);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "orgTagCacheService", tags);
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(tags.getUserEffectiveOrgTags("alice")).thenReturn(List.of("team"));
        when(files.findAccessibleFilesWithTags("42", List.of("team"))).thenReturn(List.of());
        assertTrue(service.getAccessibleFiles("42", "team").isEmpty());
        assertTrue(service.getAccessibleFiles("alice", "team").isEmpty());
        verify(files, times(2)).findAccessibleFilesWithTags("42", List.of("team"));
    }
}
